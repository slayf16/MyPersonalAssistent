package com.mypersonalassistent.core.mcp.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonElement

data class McpServer(val id: String, val name: String, val endpoint: String, val createdAt: Long, val updatedAt: Long)
/** Null secret fields preserve the encrypted value during edit; clearing is always explicit. */
data class McpServerDraft(
    val id: String? = null,
    val name: String,
    val endpoint: String,
    val token: String? = null,
    val apiKey: String? = null,
    val clearToken: Boolean = false,
    val clearApiKey: Boolean = false,
)
data class McpPermission(val serverId: String, val enabled: Boolean, val name: String = serverId)
data class McpToolId(val serverId: String, val name: String)
data class McpToolDefinition(val id: McpToolId, val description: String?, val inputSchema: JsonElement, val outputSchema: JsonElement? = null)
data class McpToolCall(val id: McpToolId, val arguments: JsonElement, val callId: String)
data class McpToolResult(val callId: String, val text: String, val structuredContent: JsonElement? = null, val isError: Boolean = false)
data class McpPreparedCall(val maskedDisplayArguments: String)
enum class McpError { VALIDATION, LIMIT, AUTHORIZATION, TIMEOUT, TRANSPORT, PROTOCOL, UNSUPPORTED_SCHEMA, INVALID_ARGUMENTS, INVALID_RESULT, UNSUPPORTED_CONTENT, SENSITIVE_ARGUMENT, TOOL }
sealed interface McpResult<out T> { data class Success<T>(val value: T) : McpResult<T>; data class Failure(val error: McpError) : McpResult<Nothing> }

interface McpCatalogRepository {
    fun observe(): Flow<List<McpServer>>
    suspend fun save(draft: McpServerDraft): McpResult<McpServer>
    suspend fun delete(id: String): McpResult<Unit>
}
interface ChatMcpRepository {
    suspend fun permissions(chatId: String): List<McpPermission>
    suspend fun setEnabled(chatId: String, serverId: String, enabled: Boolean): McpResult<Unit>
}
interface McpToolGateway {
    suspend fun tools(chatId: String): McpResult<List<McpToolDefinition>>
    /** Local-only validation before any call proposal can be persisted or displayed. */
    suspend fun prepareCall(call: McpToolCall): McpResult<McpPreparedCall> = McpResult.Success(McpPreparedCall(call.arguments.toString()))
    suspend fun call(call: McpToolCall): McpResult<McpToolResult>
}

/** Coordinates a lifecycle-owned chat operation with catalog deletion. */
interface McpOperationCoordinator {
    fun register(
        serverId: String,
        job: Job,
        invalidateBeforeCancellation: () -> Unit = {},
        normalizeAfterCancellation: suspend () -> Unit,
    )
    fun unregister(serverId: String, job: Job)
    suspend fun cancelAndJoin(serverId: String)
}
