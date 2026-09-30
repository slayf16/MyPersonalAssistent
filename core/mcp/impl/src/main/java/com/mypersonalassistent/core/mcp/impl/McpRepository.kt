package com.mypersonalassistent.core.mcp.impl

import com.mypersonalassistent.core.credentials.api.CredentialWriteResult
import com.mypersonalassistent.core.credentials.api.McpSecretRepository
import com.mypersonalassistent.core.credentials.api.McpSecrets
import com.mypersonalassistent.core.database.api.McpStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredChatMcpPermission
import com.mypersonalassistent.core.database.api.StoredMcpServer
import com.mypersonalassistent.core.mcp.api.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Job

internal class DefaultMcpRepository(
    private val storage: McpStorage,
    private val secrets: McpSecretRepository,
    private val client: HttpClient = HttpClient(OkHttp) { followRedirects = false; install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 } },
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val now: () -> Long = System::currentTimeMillis,
    private val operations: McpOperationCoordinator = NoopMcpOperationCoordinator,
) : McpCatalogRepository, ChatMcpRepository, McpToolGateway {
    private val cache = mutableMapOf<String, Pair<Long, List<McpToolDefinition>>>()
    /** Session IDs are transport-only and must never reach Room, checkpoints, or logs. */
    private val sessions = mutableMapOf<String, String>()
    /** A notification seen while a response is being consumed must win over a cache write. */
    private val listChangedServers = mutableSetOf<String>()
    /** Serializes the credential/Room saga with startup compensation. */
    private val sagaMutex = Mutex()
    override fun observe(): Flow<List<McpServer>> = storage.observeMcpServers()
        .onStart { reconcile() }
        .map { rows -> rows.filter { it.secretState == ACTIVE }.map { McpServer(it.id, it.name, it.endpoint, it.createdAt, it.updatedAt) } }
    override suspend fun save(draft: McpServerDraft): McpResult<McpServer> = sagaMutex.withLock {
        val name = draft.name.trim()
        val endpoint = normalizeEndpoint(draft.endpoint) ?: return McpResult.Failure(McpError.VALIDATION)
        if (name.isEmpty() || name.length > 80) return McpResult.Failure(McpError.VALIDATION)
        val id = draft.id ?: UUID.randomUUID().toString(); val time = now()
        val current = storage.readMcpServer(id)
        if (current == null && storage.observeMcpServers().first().size >= 50) return McpResult.Failure(McpError.LIMIT)
        val currentSecrets = secrets.read(id)
        val hasSecretChange = draft.token != null || draft.apiKey != null || draft.clearToken || draft.clearApiKey
        val secretsResult = if (hasSecretChange) {
            secrets.stage(id, McpSecrets(
                token = if (draft.clearToken) null else draft.token?.trim()?.takeIf(String::isNotEmpty) ?: currentSecrets?.token,
                apiKey = if (draft.clearApiKey) null else draft.apiKey?.trim()?.takeIf(String::isNotEmpty) ?: currentSecrets?.apiKey,
            ))
        } else CredentialWriteResult.Success
        if (secretsResult !is CredentialWriteResult.Success) return McpResult.Failure(McpError.VALIDATION)
        val row = StoredMcpServer(id, name, endpoint, endpoint.lowercase(), current?.createdAt ?: time, time, if (hasSecretChange) PENDING else ACTIVE)
        return if (storage.upsertMcpServer(row) is StorageResult.Success && (!hasSecretChange || secrets.activateStage(id) is CredentialWriteResult.Success) && (storage.upsertMcpServer(row.copy(secretState = ACTIVE)) is StorageResult.Success)) McpResult.Success(McpServer(row.id, row.name, row.endpoint, row.createdAt, row.updatedAt)) else {
            // The encrypted write is committed before Room so a failed metadata transaction must
            // restore the previous active secret (or remove a newly created one).  This avoids a
            // configuration pointing at an old server with credentials for a failed edit.
            if (current == null) {
                // Activation may already have promoted the staged bytes before the final Room
                // marker failed. Remove both active and staged material for a new server.
                secrets.discardStage(id)
                secrets.delete(id)
                storage.deleteMcpServer(id)
            } else {
                secrets.discardStage(id)
                secrets.save(id, currentSecrets ?: McpSecrets())
                storage.upsertMcpServer(current)
            }
            McpResult.Failure(McpError.VALIDATION)
        }
    }
    override suspend fun delete(id: String): McpResult<Unit> {
        operations.cancelAndJoin(id)
        cache.remove(id)
        sessions.remove(id)
        if (storage.deleteMcpServer(id) !is StorageResult.Success) return McpResult.Failure(McpError.VALIDATION)
        return if (secrets.delete(id) is CredentialWriteResult.Success) McpResult.Success(Unit) else McpResult.Failure(McpError.VALIDATION)
    }
    override suspend fun permissions(chatId: String): List<McpPermission> {
        reconcile()
        val enabled = storage.permissions(chatId).associateBy { it.serverId }
        return storage.observeMcpServers().first().asSequence()
            .filter { it.secretState == ACTIVE }
            .map { server -> McpPermission(server.id, enabled[server.id]?.enabled ?: false, server.name) }
            .toList()
    }
    override suspend fun setEnabled(chatId: String, serverId: String, enabled: Boolean): McpResult<Unit> {
        if (storage.readMcpServer(serverId) == null) return McpResult.Failure(McpError.VALIDATION)
        val active = storage.permissions(chatId).count { it.enabled && it.serverId != serverId }
        if (enabled && active >= 10) return McpResult.Failure(McpError.LIMIT)
        return if (storage.setPermission(StoredChatMcpPermission(chatId, serverId, enabled, now())) is StorageResult.Success) McpResult.Success(Unit) else McpResult.Failure(McpError.VALIDATION)
    }
    override suspend fun tools(chatId: String): McpResult<List<McpToolDefinition>> {
        reconcile()
        val activeIds = storage.observeMcpServers().first().asSequence()
            .filter { it.secretState == ACTIVE }
            .map { it.id }
            .toSet()
        val ids = storage.permissions(chatId).asSequence().filter { it.enabled && it.serverId in activeIds }.map { it.serverId }.toList()
        val result = mutableListOf<McpToolDefinition>()
        for (id in ids) {
            when (val tools = discover(id)) { is McpResult.Success -> result += tools.value; is McpResult.Failure -> return tools }
        }
        val schemaBytes = result.sumOf { tool ->
            tool.inputSchema.toString().toByteArray().size +
                (tool.outputSchema?.toString()?.toByteArray()?.size ?: 0)
        }
        return if (schemaBytes > MAX_PROVIDER_SCHEMA_BYTES) McpResult.Failure(McpError.LIMIT) else McpResult.Success(result)
    }
    override suspend fun call(call: McpToolCall): McpResult<McpToolResult> {
        if (call.arguments.toString().toByteArray().size > 64 * 1024) return McpResult.Failure(McpError.INVALID_ARGUMENTS)
        val server = storage.readMcpServer(call.id.serverId) ?: return McpResult.Failure(McpError.VALIDATION)
        val tool = (discover(server.id) as? McpResult.Success)?.value?.firstOrNull { it.id == call.id } ?: return McpResult.Failure(McpError.UNSUPPORTED_SCHEMA)
        if (!JsonSchemaSubsetValidator.validateArguments(tool.inputSchema, call.arguments)) return McpResult.Failure(McpError.INVALID_ARGUMENTS)
        val request = buildJsonObject { put("jsonrpc", "2.0"); put("id", call.callId); put("method", "tools/call"); put("params", buildJsonObject { put("name", call.id.name); put("arguments", call.arguments) }) }
        return when (val rpc = rpc(server, request)) {
            is RpcResult.Failure -> McpResult.Failure(rpc.error)
            RpcResult.SessionExpired -> {
                // The external action may already have happened. Never retry tools/call.
                sessions.remove(server.id)
                cache.remove(server.id)
                McpResult.Failure(McpError.PROTOCOL)
            }
            is RpcResult.Success -> {
                val response = rpc.value["result"] as? JsonObject ?: return McpResult.Failure(McpError.PROTOCOL)
                val declaredError = response["isError"]
                if (declaredError != null && (declaredError as? JsonPrimitive)?.booleanOrNull == null) return McpResult.Failure(McpError.PROTOCOL)
                if ((declaredError as? JsonPrimitive)?.booleanOrNull == true) return McpResult.Failure(McpError.TOOL)
                val content = response["content"] as? JsonArray ?: return McpResult.Failure(McpError.PROTOCOL)
                if (content.any { item ->
                        val obj = item as? JsonObject ?: return@any true
                        (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull != "text" ||
                            (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == null
                    }) return McpResult.Failure(McpError.UNSUPPORTED_CONTENT)
                val text = content.joinToString("\n") { ((it as JsonObject)["text"] as JsonPrimitive).content }
                val structured = response["structuredContent"]
                if (text.toByteArray().size + (structured?.toString()?.toByteArray()?.size ?: 0) > MAX_TOOL_RESULT_BYTES) return McpResult.Failure(McpError.INVALID_RESULT)
                if (structured != null && structured !is JsonObject && structured !is JsonArray || structured != null && !JsonSchemaSubsetValidator.validateOutput(tool.outputSchema, structured)) return McpResult.Failure(McpError.INVALID_RESULT)
                McpResult.Success(McpToolResult(call.callId, text, structured))
            }
        }
    }
    override suspend fun prepareCall(call: McpToolCall): McpResult<McpPreparedCall> {
        if (call.arguments.toString().toByteArray().size > 64 * 1024) return McpResult.Failure(McpError.INVALID_ARGUMENTS)
        if (storage.readMcpServer(call.id.serverId) == null) return McpResult.Failure(McpError.VALIDATION)
        val configured = secrets.read(call.id.serverId)
        val protectedValues = setOfNotNull(configured?.token?.takeIf(String::isNotBlank), configured?.apiKey?.takeIf(String::isNotBlank))
        if (containsProtectedValue(call.arguments, protectedValues)) return McpResult.Failure(McpError.SENSITIVE_ARGUMENT)
        return McpResult.Success(McpPreparedCall(maskedDisplay(call.arguments)))
    }
    private suspend fun discover(id: String): McpResult<List<McpToolDefinition>> {
        cache[id]?.takeIf { now() - it.first < 300_000 }?.let { return McpResult.Success(it.second) }
        val server = storage.readMcpServer(id) ?: return McpResult.Failure(McpError.VALIDATION)
        return discoverFresh(server, retryExpiredSession = true)
    }
    private suspend fun discoverFresh(server: StoredMcpServer, retryExpiredSession: Boolean): McpResult<List<McpToolDefinition>> {
        val id = server.id
        sessions.remove(id)
        listChangedServers.remove(id)
        val initializationId = "init-$id"
        val initialized = rpc(server, buildJsonObject { put("jsonrpc", "2.0"); put("id", initializationId); put("method", "initialize"); put("params", buildJsonObject { put("protocolVersion", "2025-11-25"); put("capabilities", buildJsonObject {}); put("clientInfo", buildJsonObject { put("name", "MyPersonalAssistent"); put("version", "1") }) }) })
        val initialization = when (initialized) {
            is RpcResult.Success -> initialized.value
            is RpcResult.Failure -> return McpResult.Failure(initialized.error)
            RpcResult.SessionExpired -> return retryDiscoveryAfterExpiry(server, retryExpiredSession)
        }
        if ((((initialization["result"] as? JsonObject)?.get("protocolVersion") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) != "2025-11-25") return McpResult.Failure(McpError.PROTOCOL)
        val notification = rpc(server, buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, expectsResponse = false)
        when (notification) {
            is RpcResult.Failure -> return McpResult.Failure(notification.error)
            RpcResult.SessionExpired -> return retryDiscoveryAfterExpiry(server, retryExpiredSession)
            is RpcResult.Success -> Unit
        }
        val parsed = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val requestId = "tools-$id-${cursor ?: "first"}"
            val listed = rpc(server, buildJsonObject { put("jsonrpc", "2.0"); put("id", requestId); put("method", "tools/list"); put("params", buildJsonObject { cursor?.let { put("cursor", it) } }) })
            val root = when (listed) {
                is RpcResult.Success -> listed.value
                is RpcResult.Failure -> return McpResult.Failure(listed.error)
                RpcResult.SessionExpired -> return retryDiscoveryAfterExpiry(server, retryExpiredSession)
            }
            val result = root["result"] as? JsonObject ?: return McpResult.Failure(McpError.PROTOCOL)
            val raw = result["tools"] as? JsonArray ?: return McpResult.Failure(McpError.PROTOCOL)
            if (parsed.size + raw.size > 100) return McpResult.Failure(McpError.LIMIT)
            parsed += raw.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
                val schema = obj["inputSchema"] ?: return@mapNotNull null
                val description = obj["description"]
                if (description != null && (description as? JsonPrimitive)?.isString != true) return@mapNotNull null
                if (!JsonSchemaSubsetValidator.isSupported(schema, true) || !JsonSchemaSubsetValidator.isSupported(obj["outputSchema"], false)) null
                else McpToolDefinition(McpToolId(id, name), (description as? JsonPrimitive)?.contentOrNull, schema, obj["outputSchema"])
            }
            val nextCursor = result["nextCursor"]
            if (nextCursor != null && (nextCursor as? JsonPrimitive)?.isString != true) return McpResult.Failure(McpError.PROTOCOL)
            cursor = (nextCursor as? JsonPrimitive)?.contentOrNull
            if (cursor != null && !seenCursors.add(cursor!!)) return McpResult.Failure(McpError.PROTOCOL)
        } while (cursor != null)
        if (!listChangedServers.remove(id)) cache[id] = now() to parsed
        return McpResult.Success(parsed)
    }
    private suspend fun retryDiscoveryAfterExpiry(server: StoredMcpServer, allowed: Boolean): McpResult<List<McpToolDefinition>> {
        sessions.remove(server.id)
        cache.remove(server.id)
        return if (allowed) discoverFresh(server, retryExpiredSession = false) else McpResult.Failure(McpError.PROTOCOL)
    }
    /** Completes the idempotent secret activation after process death, or hides failed pending metadata. */
    private suspend fun reconcile() = sagaMutex.withLock {
        secrets.retryPendingDeletes()
        val rows = storage.observeMcpServers().first()
        val pendingIds = rows.asSequence().filter { it.secretState == PENDING }.map { it.id }.toSet()
        // stage() is durable before the Room marker. A crash in that window leaves an orphan
        // that must be compensated before catalog state is exposed.
        secrets.stagedServerIds().filterNot { it in pendingIds }.forEach { orphanId ->
            secrets.discardStage(orphanId)
        }
        rows.filter { it.secretState == PENDING }.forEach { row ->
            if (secrets.activateStage(row.id) is CredentialWriteResult.Success) storage.upsertMcpServer(row.copy(secretState = ACTIVE))
        }
    }
    private suspend fun rpc(server: StoredMcpServer, payload: JsonObject, expectsResponse: Boolean = true): RpcResult = try {
        val secret = secrets.read(server.id)
        val requestSession = sessions[server.id]
        val response = client.post(server.endpoint) { contentType(ContentType.Application.Json); header(HttpHeaders.Accept, "application/json, text/event-stream"); sessions[server.id]?.let { header("Mcp-Session-Id", it) }; secret?.token?.let { header("Authorization", "Bearer $it") }; secret?.apiKey?.let { header("X-API-Key", it) }; setBody(payload.toString()) }
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden -> RpcResult.Failure(McpError.AUTHORIZATION)
            response.status == HttpStatusCode.NotFound && requestSession != null -> RpcResult.SessionExpired
            !response.status.isSuccess() -> RpcResult.Failure(McpError.TRANSPORT)
            !expectsResponse -> RpcResult.Success(buildJsonObject {})
            else -> {
                val contentType = response.contentType()
                if (contentType?.match(ContentType.Application.Json) != true && contentType?.match(ContentType.Text.EventStream) != true) {
                    RpcResult.Failure(McpError.PROTOCOL)
                } else {
                    response.headers["Mcp-Session-Id"]?.takeIf(String::isNotBlank)?.let { sessions[server.id] = it }
                    val expectedId = payload["id"]?.jsonPrimitive?.contentOrNull
                    if (expectedId == null) RpcResult.Failure(McpError.PROTOCOL)
                    else {
                        val body = readBoundedResponse(response)
                        if (body == null) RpcResult.Failure(McpError.PROTOCOL)
                        else parseResponse(server.id, body, contentType, expectedId)
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: HttpRequestTimeoutException) { RpcResult.Failure(McpError.TIMEOUT)
    } catch (_: SocketTimeoutException) { RpcResult.Failure(McpError.TIMEOUT)
    } catch (_: Throwable) { RpcResult.Failure(McpError.TRANSPORT) }
    private suspend fun readBoundedResponse(response: HttpResponse): String? {
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.let { value ->
            value.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        }
        if (declaredLength != null && declaredLength > MAX_RPC_RESPONSE_BYTES) return null
        val channel = response.bodyAsChannel()
        val output = ByteArrayOutputStream(minOf(declaredLength?.toInt() ?: 8_192, MAX_RPC_RESPONSE_BYTES))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer, 0, minOf(buffer.size, MAX_RPC_RESPONSE_BYTES + 1 - total))
            if (read == -1) break
            if (read == 0) continue
            total += read
            if (total > MAX_RPC_RESPONSE_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
    private fun parseResponse(serverId: String, body: String, contentType: ContentType, expectedId: String): RpcResult {
        return try {
            val documents = if (contentType.match(ContentType.Text.EventStream)) parseSseDocuments(body) else listOf(body)
            var correlated: JsonObject? = null
            for (document in documents) {
                val root = json.parseToJsonElement(document).jsonObject
                if (root["jsonrpc"]?.jsonPrimitive?.contentOrNull != "2.0") return RpcResult.Failure(McpError.PROTOCOL)
                when {
                    root["method"]?.jsonPrimitive?.contentOrNull == "notifications/tools/list_changed" && root["id"] == null -> {
                        cache.remove(serverId)
                        listChangedServers += serverId
                    }
                    root["id"]?.jsonPrimitive?.contentOrNull == expectedId -> {
                        if (correlated != null) return RpcResult.Failure(McpError.PROTOCOL)
                        correlated = root
                    }
                }
            }
            correlated?.let(RpcResult::Success) ?: RpcResult.Failure(McpError.PROTOCOL)
        } catch (_: Throwable) {
            RpcResult.Failure(McpError.PROTOCOL)
        }
    }
    private fun parseSseDocuments(body: String): List<String> {
        val events = mutableListOf<String>()
        val data = mutableListOf<String>()
        fun finishEvent() {
            if (data.isNotEmpty()) events += data.joinToString("\n")
            data.clear()
        }
        body.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> finishEvent()
                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
            }
        }
        finishEvent()
        return events
    }
    private fun containsProtectedValue(value: JsonElement, protectedValues: Set<String>): Boolean = when (value) {
        is JsonObject -> value.values.any { containsProtectedValue(it, protectedValues) }
        is JsonArray -> value.any { containsProtectedValue(it, protectedValues) }
        is JsonPrimitive -> value.isString && value.content in protectedValues
    }
    private fun maskedDisplay(value: JsonElement): String {
        fun mask(element: JsonElement, key: String? = null): JsonElement = when {
            key != null && SENSITIVE_DISPLAY_KEY.containsMatchIn(key) -> JsonPrimitive("••••")
            element is JsonObject -> JsonObject(element.mapValues { (childKey, child) -> mask(child, childKey) })
            element is JsonArray -> JsonArray(element.map { child -> mask(child) })
            else -> element
        }
        val full = canonicalJson(mask(value))
        return if (full.codePointCount(0, full.length) <= MAX_DISPLAY_ARGUMENT_CODE_POINTS) full else
            full.substring(0, full.offsetByCodePoints(0, MAX_DISPLAY_ARGUMENT_CODE_POINTS)) + "…"
    }
    private fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, child) -> JsonPrimitive(key).toString() + ":" + canonicalJson(child) }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> value.toString()
    }
    private fun normalizeEndpoint(value: String): String? = runCatching {
        val raw = value.trim()
        URI(raw).let { uri ->
            if (uri.scheme !in setOf("http", "https") || !uri.isAbsolute || uri.host.isNullOrBlank() || uri.rawAuthority.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || raw.length > 2048) null
            else uri.normalize().toString()
        }
    }.getOrNull()
}

private sealed interface RpcResult {
    data class Success(val value: JsonObject) : RpcResult
    data class Failure(val error: McpError) : RpcResult
    data object SessionExpired : RpcResult
}
private const val ACTIVE = "ACTIVE"
private const val PENDING = "PENDING_ACTIVATION"
private const val MAX_PROVIDER_SCHEMA_BYTES = 512 * 1024
private const val MAX_TOOL_RESULT_BYTES = 1024 * 1024
private const val MAX_RPC_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_DISPLAY_ARGUMENT_CODE_POINTS = 8_192
private val SENSITIVE_DISPLAY_KEY = Regex("(?i)(token|key|secret|password|authorization)")
private object NoopMcpOperationCoordinator : McpOperationCoordinator {
    override fun register(serverId: String, job: Job, invalidateBeforeCancellation: () -> Unit, normalizeAfterCancellation: suspend () -> Unit) = Unit
    override fun unregister(serverId: String, job: Job) = Unit
    override suspend fun cancelAndJoin(serverId: String) = Unit
}
