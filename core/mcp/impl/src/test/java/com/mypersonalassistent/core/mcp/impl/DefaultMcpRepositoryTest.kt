package com.mypersonalassistent.core.mcp.impl

import com.mypersonalassistent.core.credentials.api.CredentialWriteResult
import com.mypersonalassistent.core.credentials.api.McpSecretRepository
import com.mypersonalassistent.core.credentials.api.McpSecrets
import com.mypersonalassistent.core.database.api.McpStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredChatMcpPermission
import com.mypersonalassistent.core.database.api.StoredMcpServer
import com.mypersonalassistent.core.mcp.api.McpResult
import com.mypersonalassistent.core.mcp.api.McpError
import com.mypersonalassistent.core.mcp.api.McpOperationCoordinator
import com.mypersonalassistent.core.mcp.api.McpToolCall
import com.mypersonalassistent.core.mcp.api.McpToolId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.json.put

class DefaultMcpRepositoryTest {
    @Test fun `sensitive argument is rejected before persistence and display masks secret-shaped fields`() = runBlocking {
        val secrets = RecordingSecrets(McpSecrets(token = "configured-token", apiKey = "configured-key"))
        val repository = DefaultMcpRepository(FakeStorage(), secrets)

        val rejected = repository.prepareCall(McpToolCall(
            McpToolId("server", "lookup"),
            kotlinx.serialization.json.buildJsonObject {
                put("nested", kotlinx.serialization.json.buildJsonObject { put("value", "configured-token") })
            },
            "call-1",
        ))
        val prepared = repository.prepareCall(McpToolCall(
            McpToolId("server", "lookup"),
            kotlinx.serialization.json.buildJsonObject {
                put("password", "do-not-show")
                put("query", "weather")
            },
            "call-2",
        ))

        assertEquals(McpResult.Failure(McpError.SENSITIVE_ARGUMENT), rejected)
        val display = (prepared as McpResult.Success).value.maskedDisplayArguments
        assertFalse(display.contains("do-not-show"))
        assertTrue(display.contains("••••"))
        assertTrue(display.contains("weather"))
    }

    @Test fun `malformed absolute https endpoint is rejected without metadata write`() = runBlocking {
        val storage = CountingStorage()

        val result = DefaultMcpRepository(storage, EmptySecrets).save(
            com.mypersonalassistent.core.mcp.api.McpServerDraft(name = "Broken", endpoint = "https:/not-a-host"),
        )

        assertEquals(McpResult.Failure(McpError.VALIDATION), result)
        assertEquals(0, storage.writes)
    }

    @Test fun `delete cancels active operation before removing metadata and reports secret cleanup failure`() = runBlocking {
        val events = mutableListOf<String>()
        val storage = EventStorage(events)
        val secrets = FailingDeleteSecrets(events)
        val operations = object : McpOperationCoordinator {
            override fun register(serverId: String, job: Job, invalidateBeforeCancellation: () -> Unit, normalizeAfterCancellation: suspend () -> Unit) = Unit
            override fun unregister(serverId: String, job: Job) = Unit
            override suspend fun cancelAndJoin(serverId: String) { events += "cancel:$serverId" }
        }

        val result = DefaultMcpRepository(storage, secrets, operations = operations).delete("server")

        assertEquals(McpResult.Failure(McpError.VALIDATION), result)
        assertEquals(listOf("cancel:server", "metadata:server", "secret:server"), events)
        assertTrue(storage.deleted)
    }

    @Test fun `delete joins real in flight operation and normalizes unknown before metadata removal`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = DefaultMcpOperationCoordinator()
        val operation = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        val secondOperation = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        coordinator.register("server", operation, invalidateBeforeCancellation = { events += "invalidate-1" }) { events += "normalize-unknown-1" }
        coordinator.register("server", secondOperation, invalidateBeforeCancellation = { events += "invalidate-2" }) { events += "normalize-unknown-2" }
        val storage = EventStorage(events)

        val result = DefaultMcpRepository(storage, RecordingSecrets(null), operations = coordinator).delete("server")

        assertTrue(operation.isCancelled)
        assertTrue(secondOperation.isCancelled)
        assertTrue(result is McpResult.Success)
        assertEquals(listOf("invalidate-1", "invalidate-2", "normalize-unknown-1", "normalize-unknown-2", "metadata:server"), events)
    }

    @Test fun `reconcile retries durable secret cleanup without restoring deleted catalog row`() = runBlocking {
        val storage = EmptyCatalogStorage()
        val secrets = FailingDeleteSecrets(mutableListOf())
        val repository = DefaultMcpRepository(storage, secrets)

        repository.observe().first()

        assertEquals(1, secrets.cleanupRetries)
        assertTrue(storage.rows.isEmpty())
    }
    @Test fun `reconcile discards a durable staged credential without a Room intent`() = runBlocking {
        val secrets = RecordingSecrets(active = null, staged = true, stagedServerId = "orphan")

        DefaultMcpRepository(EmptyCatalogStorage(), secrets).observe().first()

        assertEquals(1, secrets.discarded)
        assertTrue(secrets.stagedServerIds().isEmpty())
    }
    @Test fun `discovery accepts only JSON or SSE response content types`() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                """{"jsonrpc":"2.0","id":"init-server","result":{"protocolVersion":"2025-11-25"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        })

        val result = DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat")

        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.PROTOCOL), result)
    }

    @Test fun `discovery rejects declared oversized response before parsing`() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(
                "x".repeat(2 * 1024 * 1024 + 1),
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.ContentLength to listOf((2 * 1024 * 1024 + 1).toString()),
                ),
            )
        })

        val result = DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat")

        assertEquals(McpResult.Failure(McpError.PROTOCOL), result)
        assertEquals(1, requests)
    }

    @Test fun `discovery stops a chunked response at the byte budget`() = runBlocking {
        var requests = 0
        val oversized = initialize() + " ".repeat(2 * 1024 * 1024 + 1)
        val client = HttpClient(MockEngine {
            requests++
            respond(oversized, HttpStatusCode.OK, responseHeaders())
        })

        val result = DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat")

        assertEquals(McpResult.Failure(McpError.PROTOCOL), result)
        assertEquals(1, requests)
    }

    @Test fun `configured token and API key are sent only in their fixed headers`() = runBlocking {
        var turn = 0
        val observed = mutableListOf<Pair<String?, String?>>()
        val client = HttpClient(MockEngine { request ->
            observed += request.headers[HttpHeaders.Authorization] to request.headers["X-API-Key"]
            when (++turn) {
                1 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "session"))
                2 -> respond("", HttpStatusCode.Accepted)
                else -> respond(toolList("lookup"), HttpStatusCode.OK, responseHeaders())
            }
        })

        val result = DefaultMcpRepository(FakeStorage(), RecordingSecrets(McpSecrets("token-value", "api-value")), client).tools("chat")

        assertTrue(result is McpResult.Success)
        assertTrue(observed.all { it == ("Bearer token-value" to "api-value") })
    }

    @Test fun `discovery parses SSE data records with the requested RPC ids`() = runBlocking {
        var turn = 0
        val client = HttpClient(MockEngine {
            turn += 1
            when (turn) {
                1 -> respond("data: {\"jsonrpc\":\"2.0\",\"id\":\"init-server\",\"result\":{\"protocolVersion\":\"2025-11-25\"}}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
                2 -> respond("", HttpStatusCode.Accepted)
                3 -> respond("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"tools-server-first\",\"result\":{\"tools\":[{\"name\":\"from-sse\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
                else -> error("unexpected request")
            }
        })

        val result = DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat")

        assertEquals(listOf("from-sse"), (result as McpResult.Success).value.map { it.id.name })
    }

    @Test fun `expired discovery cache starts a fresh session without reusing the old id`() = runBlocking {
        var now = 1L
        var turn = 0
        val requestSessions = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            requestSessions += request.headers["Mcp-Session-Id"]
            turn += 1
            val generation = if (turn <= 3) "one" else "two"
            when ((turn - 1) % 3) {
                0 -> respond("""{"jsonrpc":"2.0","id":"init-server","result":{"protocolVersion":"2025-11-25"}}""", HttpStatusCode.OK, responseHeaders(session = "session-$generation"))
                1 -> respond("", HttpStatusCode.Accepted)
                else -> respond("""{"jsonrpc":"2.0","id":"tools-server-first","result":{"tools":[{"name":"tool-$generation","inputSchema":{"type":"object"}}]}}""", HttpStatusCode.OK, responseHeaders())
            }
        })
        val repository = DefaultMcpRepository(FakeStorage(), EmptySecrets, client, now = { now })

        assertEquals(listOf("tool-one"), (repository.tools("chat") as McpResult.Success).value.map { it.id.name })
        now += 300_000
        assertEquals(listOf("tool-two"), (repository.tools("chat") as McpResult.Success).value.map { it.id.name })

        assertEquals(listOf(null, "session-one", "session-one", null, "session-two", "session-two"), requestSessions)
    }

    @Test fun `404 with an active discovery session reinitializes and lists once`() = runBlocking {
        var turn = 0
        val sessions = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            sessions += request.headers["Mcp-Session-Id"]
            when (++turn) {
                1 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "expired"))
                2 -> respond("", HttpStatusCode.Accepted)
                3 -> respond("", HttpStatusCode.NotFound)
                4 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "fresh"))
                5 -> respond("", HttpStatusCode.Accepted)
                6 -> respond(toolList("fresh-tool"), HttpStatusCode.OK, responseHeaders())
                else -> error("unexpected retry")
            }
        })

        val result = DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat")

        assertEquals(listOf("fresh-tool"), (result as McpResult.Success).value.map { it.id.name })
        assertEquals(listOf(null, "expired", "expired", null, "fresh", "fresh"), sessions)
        assertEquals(6, turn)
    }

    @Test fun `404 after tools call is never retried`() = runBlocking {
        var turn = 0
        val client = HttpClient(MockEngine {
            when (++turn) {
                1 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "session"))
                2 -> respond("", HttpStatusCode.Accepted)
                3 -> respond(toolList("lookup"), HttpStatusCode.OK, responseHeaders())
                4 -> respond("", HttpStatusCode.NotFound)
                else -> error("tools call must not be retried")
            }
        })
        val repository = DefaultMcpRepository(FakeStorage(), EmptySecrets, client)
        repository.tools("chat")

        val result = repository.call(com.mypersonalassistent.core.mcp.api.McpToolCall(com.mypersonalassistent.core.mcp.api.McpToolId("server", "lookup"), kotlinx.serialization.json.buildJsonObject {}, "call-1"))

        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.PROTOCOL), result)
        assertEquals(4, turn)
    }

    @Test fun `SSE list changed notification invalidates discovery cache and response id is correlated`() = runBlocking {
        var turn = 0
        val client = HttpClient(MockEngine {
            val generation = if (turn < 3) "one" else "two"
            when (++turn) {
                1, 4 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "session-$generation"))
                2, 5 -> respond("", HttpStatusCode.Accepted)
                3 -> respond(
                    "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n" +
                        "data: ${toolList("tool-one")}\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
                6 -> respond(toolList("tool-two"), HttpStatusCode.OK, responseHeaders())
                else -> error("unexpected request")
            }
        })
        val repository = DefaultMcpRepository(FakeStorage(), EmptySecrets, client)

        assertEquals(listOf("tool-one"), (repository.tools("chat") as McpResult.Success).value.map { it.id.name })
        assertEquals(listOf("tool-two"), (repository.tools("chat") as McpResult.Success).value.map { it.id.name })
        assertEquals(6, turn)
    }

    @Test fun `auth malformed redirect timeout and network responses are classified without retries`() = runBlocking {
        val unauthorized = DefaultMcpRepository(FakeStorage(), EmptySecrets, HttpClient(MockEngine {
            respond("", HttpStatusCode.Unauthorized)
        })).tools("chat")
        val forbidden = DefaultMcpRepository(FakeStorage(), EmptySecrets, HttpClient(MockEngine {
            respond("", HttpStatusCode.Forbidden)
        })).tools("chat")
        val malformed = DefaultMcpRepository(FakeStorage(), EmptySecrets, HttpClient(MockEngine {
            respond("{", HttpStatusCode.OK, responseHeaders())
        })).tools("chat")
        val redirect = DefaultMcpRepository(FakeStorage(), EmptySecrets, HttpClient(MockEngine {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test/mcp"))
        })).tools("chat")
        val timeout = DefaultMcpRepository(FakeStorage(), EmptySecrets, HttpClient(MockEngine {
            throw SocketTimeoutException("fixture")
        })).tools("chat")
        val network = DefaultMcpRepository(FakeStorage(), EmptySecrets, HttpClient(MockEngine {
            throw IOException("fixture")
        })).tools("chat")

        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.AUTHORIZATION), unauthorized)
        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.AUTHORIZATION), forbidden)
        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.PROTOCOL), malformed)
        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.TRANSPORT), redirect)
        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.TIMEOUT), timeout)
        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.TRANSPORT), network)
    }

    @Test fun `failed metadata write restores existing active secret and discards stage`() = runBlocking {
        val storage = SagaStorage(failFirstWrite = true)
        val secrets = RecordingSecrets(active = McpSecrets(token = "old"))
        val repository = DefaultMcpRepository(storage, secrets)

        val result = repository.save(com.mypersonalassistent.core.mcp.api.McpServerDraft(id = "server", name = "Server", endpoint = "https://example.test/mcp", token = "new"))

        assertTrue(result is McpResult.Failure)
        assertEquals(McpSecrets(token = "old"), secrets.active)
        assertEquals(1, secrets.discarded)
        assertEquals("ACTIVE", storage.server.secretState)
    }

    @Test fun `pending metadata is activated deterministically before permissions are exposed`() = runBlocking {
        val storage = SagaStorage(pending = true)
        val secrets = RecordingSecrets(active = null, staged = true)
        val repository = DefaultMcpRepository(storage, secrets)

        val permissions = repository.permissions("chat")

        assertEquals(1, secrets.activations)
        assertEquals("ACTIVE", storage.server.secretState)
        assertEquals(listOf("server"), permissions.map { it.serverId })
    }

    @Test fun `failed final metadata marker removes newly activated secret and pending row`() = runBlocking {
        val storage = NewServerStorage(failWrite = 2)
        val secrets = RecordingSecrets(active = null)
        val repository = DefaultMcpRepository(storage, secrets, now = { 10 })

        val result = repository.save(com.mypersonalassistent.core.mcp.api.McpServerDraft(name = "Server", endpoint = "https://example.test/mcp", token = "new"))

        assertTrue(result is McpResult.Failure)
        assertEquals(null, secrets.active)
        assertEquals(1, secrets.deleted)
        assertTrue(storage.servers.isEmpty())
    }

    @Test fun `catalog permission and per server tool limits fail closed at their boundaries`() = runBlocking {
        val fifty = (1..50).map { server("server-$it") }
        assertEquals(
            McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.LIMIT),
            DefaultMcpRepository(LimitStorage(fifty), EmptySecrets).save(com.mypersonalassistent.core.mcp.api.McpServerDraft(name = "51", endpoint = "https://51.test/mcp")),
        )

        val eleven = (1..11).map { server("server-$it") }
        val permissionStorage = LimitStorage(eleven, enabled = eleven.take(10).map { it.id }.toSet())
        assertEquals(
            McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.LIMIT),
            DefaultMcpRepository(permissionStorage, EmptySecrets).setEnabled("chat", eleven.last().id, true),
        )

        var turn = 0
        val tools = (1..101).joinToString(",") { "{\"name\":\"tool-$it\",\"inputSchema\":{\"type\":\"object\"}}" }
        val client = HttpClient(MockEngine {
            when (++turn) {
                1 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "session"))
                2 -> respond("", HttpStatusCode.Accepted)
                else -> respond("{\"jsonrpc\":\"2.0\",\"id\":\"tools-server-first\",\"result\":{\"tools\":[$tools]}}", HttpStatusCode.OK, responseHeaders())
            }
        })
        assertEquals(
            McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.LIMIT),
            DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat"),
        )
    }

    @Test fun `provider schema budget counts UTF8 bytes from input and output`() = runBlocking {
        var turn = 0
        val metadata = "я".repeat(140_000)
        val client = HttpClient(MockEngine {
            when (++turn) {
                1 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "session"))
                2 -> respond("", HttpStatusCode.Accepted)
                else -> respond(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"tools-server-first\",\"result\":{\"tools\":[{\"name\":\"large\",\"inputSchema\":{\"type\":\"object\",\"description\":\"$metadata\"},\"outputSchema\":{\"type\":\"object\",\"description\":\"$metadata\"}}]}}",
                    HttpStatusCode.OK,
                    responseHeaders(),
                )
            }
        })

        val result = DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat")

        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.LIMIT), result)
    }

    @Test fun `malformed tool schema is isolated from a valid adjacent tool`() = runBlocking {
        var turn = 0
        val client = HttpClient(MockEngine {
            when (++turn) {
                1 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "session"))
                2 -> respond("", HttpStatusCode.Accepted)
                else -> respond(
                    """{"jsonrpc":"2.0","id":"tools-server-first","result":{"tools":[{"name":"valid","inputSchema":{"type":"object"}},{"name":"malformed","inputSchema":{"type":"object","title":{}}}]}}""",
                    HttpStatusCode.OK,
                    responseHeaders(),
                )
            }
        })

        val result = DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat")

        assertEquals(listOf("valid"), (result as McpResult.Success).value.map { it.id.name })
    }

    @Test fun `tool declared error is a domain failure that triggers agent fallback`() = runBlocking {
        var turn = 0
        val client = HttpClient(MockEngine {
            when (++turn) {
                1 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "session"))
                2 -> respond("", HttpStatusCode.Accepted)
                3 -> respond(toolList("lookup"), HttpStatusCode.OK, responseHeaders())
                else -> respond("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"unsafe detail\"}],\"isError\":true}}", HttpStatusCode.OK, responseHeaders())
            }
        })
        val repository = DefaultMcpRepository(FakeStorage(), EmptySecrets, client)
        repository.tools("chat")

        val result = repository.call(com.mypersonalassistent.core.mcp.api.McpToolCall(com.mypersonalassistent.core.mcp.api.McpToolId("server", "lookup"), kotlinx.serialization.json.buildJsonObject {}, "call-1"))

        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.TOOL), result)
    }

    @Test fun `oversized arguments and malformed or unsupported results fail closed`() = runBlocking {
        val oversizedArguments = kotlinx.serialization.json.buildJsonObject { put("value", kotlinx.serialization.json.JsonPrimitive("x".repeat(65 * 1024))) }
        assertEquals(
            McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.INVALID_ARGUMENTS),
            DefaultMcpRepository(FakeStorage(), EmptySecrets, HttpClient(MockEngine { error("network must not run") }))
                .call(com.mypersonalassistent.core.mcp.api.McpToolCall(com.mypersonalassistent.core.mcp.api.McpToolId("server", "lookup"), oversizedArguments, "call-1")),
        )

        suspend fun callWith(resultBody: String): McpResult<com.mypersonalassistent.core.mcp.api.McpToolResult> {
            var turn = 0
            val client = HttpClient(MockEngine {
                when (++turn) {
                    1 -> respond(initialize(), HttpStatusCode.OK, responseHeaders(session = "session"))
                    2 -> respond("", HttpStatusCode.Accepted)
                    3 -> respond(toolList("lookup"), HttpStatusCode.OK, responseHeaders())
                    else -> respond(resultBody, HttpStatusCode.OK, responseHeaders())
                }
            })
            val repository = DefaultMcpRepository(FakeStorage(), EmptySecrets, client)
            repository.tools("chat")
            return repository.call(com.mypersonalassistent.core.mcp.api.McpToolCall(com.mypersonalassistent.core.mcp.api.McpToolId("server", "lookup"), kotlinx.serialization.json.buildJsonObject {}, "call-1"))
        }

        assertEquals(
            McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.PROTOCOL),
            callWith("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"result\":{}}"),
        )
        assertEquals(
            McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.UNSUPPORTED_CONTENT),
            callWith("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"result\":{\"content\":[{\"type\":\"image\",\"data\":\"x\"}]}}"),
        )
        val tooLarge = "x".repeat(1024 * 1024 + 1)
        assertEquals(
            McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.INVALID_RESULT),
            callWith("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"$tooLarge\"}]}}"),
        )
    }
    @Test fun `discovery initializes session notifies and follows cursor pagination`() = runBlocking {
        val requests = mutableListOf<String?>()
        var turn = 0
        val client = HttpClient(MockEngine { request ->
            requests += request.headers["Mcp-Session-Id"]
            turn += 1
            when (turn) {
                1 -> respond("""{"jsonrpc":"2.0","id":"init-server","result":{"protocolVersion":"2025-11-25"}}""", HttpStatusCode.OK, responseHeaders(session = "session-1"))
                2 -> respond("", HttpStatusCode.Accepted)
                3 -> respond("""{"jsonrpc":"2.0","id":"tools-server-first","result":{"tools":[{"name":"first","inputSchema":{"type":"object"}}],"nextCursor":"page-2"}}""", HttpStatusCode.OK, responseHeaders())
                4 -> respond("""{"jsonrpc":"2.0","id":"tools-server-page-2","result":{"tools":[{"name":"second","inputSchema":{"type":"object"}}]}}""", HttpStatusCode.OK, responseHeaders())
                else -> error("unexpected request")
            }
        })
        val repository = DefaultMcpRepository(FakeStorage(), EmptySecrets, client)

        val result = repository.tools("chat")

        assertTrue(result is McpResult.Success)
        assertEquals(listOf("first", "second"), (result as McpResult.Success).value.map { it.id.name })
        assertEquals(listOf(null, "session-1", "session-1", "session-1"), requests)
    }

    @Test fun `discovery rejects a response whose JSON RPC id does not match request`() = runBlocking {
        val client = HttpClient(MockEngine {
            respond("""{"jsonrpc":"2.0","id":"wrong-id","result":{"protocolVersion":"2025-11-25"}}""", HttpStatusCode.OK, responseHeaders())
        })

        val result = DefaultMcpRepository(FakeStorage(), EmptySecrets, client).tools("chat")

        assertEquals(McpResult.Failure(com.mypersonalassistent.core.mcp.api.McpError.PROTOCOL), result)
    }

    private class FakeStorage : McpStorage {
        private val server = StoredMcpServer("server", "Server", "https://example.test/mcp", "https://example.test/mcp", 1, 1)
        override fun observeMcpServers(): Flow<List<StoredMcpServer>> = flowOf(listOf(server))
        override suspend fun readMcpServer(id: String): StoredMcpServer? = server.takeIf { it.id == id }
        override suspend fun upsertMcpServer(server: StoredMcpServer) = StorageResult.Success
        override suspend fun deleteMcpServer(id: String) = StorageResult.Success
        override suspend fun permissions(chatId: String) = listOf(StoredChatMcpPermission(chatId, server.id, true, 1))
        override suspend fun setPermission(permission: StoredChatMcpPermission) = StorageResult.Success
    }
    private fun responseHeaders(session: String? = null) = headersOf(
        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
        *(session?.let { arrayOf("Mcp-Session-Id" to listOf(it)) } ?: emptyArray()),
    )
    private fun initialize() = """{"jsonrpc":"2.0","id":"init-server","result":{"protocolVersion":"2025-11-25"}}"""
    private fun toolList(name: String) = """{"jsonrpc":"2.0","id":"tools-server-first","result":{"tools":[{"name":"$name","inputSchema":{"type":"object"}}]}}"""
    private fun server(id: String) = StoredMcpServer(id, id, "https://$id.test/mcp", "https://$id.test/mcp", 1, 1)
    private object EmptySecrets : McpSecretRepository {
        override suspend fun read(serverId: String): McpSecrets? = null
        override suspend fun save(serverId: String, secrets: McpSecrets) = CredentialWriteResult.Success
        override suspend fun delete(serverId: String) = CredentialWriteResult.Success
    }
    private class SagaStorage(private val failFirstWrite: Boolean = false, pending: Boolean = false) : McpStorage {
        var writes = 0
        var server = StoredMcpServer("server", "Server", "https://old.test/mcp", "https://old.test/mcp", 1, 1, if (pending) "PENDING_ACTIVATION" else "ACTIVE")
        override fun observeMcpServers(): Flow<List<StoredMcpServer>> = flowOf(listOf(server))
        override suspend fun readMcpServer(id: String): StoredMcpServer? = server.takeIf { it.id == id }
        override suspend fun upsertMcpServer(server: StoredMcpServer): StorageResult { writes++; if (failFirstWrite && writes == 1) return StorageResult.Failure; this.server = server; return StorageResult.Success }
        override suspend fun deleteMcpServer(id: String) = StorageResult.Success
        override suspend fun permissions(chatId: String) = emptyList<StoredChatMcpPermission>()
        override suspend fun setPermission(permission: StoredChatMcpPermission) = StorageResult.Success
    }
    private class RecordingSecrets(var active: McpSecrets?, staged: Boolean = false, stagedServerId: String = "server") : McpSecretRepository {
        private var stagedValue: McpSecrets? = if (staged) McpSecrets(token = "staged") else null
        private var stagedId: String? = stagedServerId.takeIf { staged }
        var activations = 0; var discarded = 0; var deleted = 0
        override suspend fun read(serverId: String) = active
        override suspend fun save(serverId: String, secrets: McpSecrets): CredentialWriteResult { active = secrets; return CredentialWriteResult.Success }
        override suspend fun delete(serverId: String): CredentialWriteResult { deleted++; active = null; return CredentialWriteResult.Success }
        override suspend fun stage(serverId: String, secrets: McpSecrets): CredentialWriteResult { stagedValue = secrets; stagedId = serverId; return CredentialWriteResult.Success }
        override suspend fun activateStage(serverId: String): CredentialWriteResult { activations++; if (stagedId == serverId) stagedValue?.let { active = it }; stagedValue = null; stagedId = null; return CredentialWriteResult.Success }
        override suspend fun discardStage(serverId: String): CredentialWriteResult { discarded++; if (stagedId == serverId) { stagedValue = null; stagedId = null }; return CredentialWriteResult.Success }
        override suspend fun hasStage(serverId: String) = stagedId == serverId
        override suspend fun stagedServerIds() = setOfNotNull(stagedId)
    }
    private class NewServerStorage(private val failWrite: Int) : McpStorage {
        val servers = mutableListOf<StoredMcpServer>()
        private var writes = 0
        override fun observeMcpServers(): Flow<List<StoredMcpServer>> = flowOf(servers.toList())
        override suspend fun readMcpServer(id: String) = servers.firstOrNull { it.id == id }
        override suspend fun upsertMcpServer(server: StoredMcpServer): StorageResult {
            writes++
            if (writes == failWrite) return StorageResult.Failure
            servers.removeAll { it.id == server.id }; servers += server
            return StorageResult.Success
        }
        override suspend fun deleteMcpServer(id: String): StorageResult { servers.removeAll { it.id == id }; return StorageResult.Success }
        override suspend fun permissions(chatId: String) = emptyList<StoredChatMcpPermission>()
        override suspend fun setPermission(permission: StoredChatMcpPermission) = StorageResult.Success
    }
    private class LimitStorage(
        initial: List<StoredMcpServer>,
        private val enabled: Set<String> = emptySet(),
    ) : McpStorage {
        private val servers = initial.toMutableList()
        override fun observeMcpServers(): Flow<List<StoredMcpServer>> = flowOf(servers.toList())
        override suspend fun readMcpServer(id: String) = servers.firstOrNull { it.id == id }
        override suspend fun upsertMcpServer(server: StoredMcpServer): StorageResult { servers.removeAll { it.id == server.id }; servers += server; return StorageResult.Success }
        override suspend fun deleteMcpServer(id: String): StorageResult { servers.removeAll { it.id == id }; return StorageResult.Success }
        override suspend fun permissions(chatId: String) = enabled.map { StoredChatMcpPermission(chatId, it, true, 1) }
        override suspend fun setPermission(permission: StoredChatMcpPermission) = StorageResult.Success
    }
    private class CountingStorage : McpStorage {
        var writes = 0
        override fun observeMcpServers(): Flow<List<StoredMcpServer>> = flowOf(emptyList())
        override suspend fun readMcpServer(id: String): StoredMcpServer? = null
        override suspend fun upsertMcpServer(server: StoredMcpServer): StorageResult { writes++; return StorageResult.Success }
        override suspend fun deleteMcpServer(id: String) = StorageResult.Success
        override suspend fun permissions(chatId: String) = emptyList<StoredChatMcpPermission>()
        override suspend fun setPermission(permission: StoredChatMcpPermission) = StorageResult.Success
    }
    private class EventStorage(private val events: MutableList<String>) : McpStorage {
        var deleted = false
        private val row = StoredMcpServer("server", "Server", "https://example.test/mcp", "https://example.test/mcp", 1, 1)
        override fun observeMcpServers(): Flow<List<StoredMcpServer>> = flowOf(if (deleted) emptyList() else listOf(row))
        override suspend fun readMcpServer(id: String): StoredMcpServer? = row.takeUnless { deleted }
        override suspend fun upsertMcpServer(server: StoredMcpServer) = StorageResult.Success
        override suspend fun deleteMcpServer(id: String): StorageResult { events += "metadata:$id"; deleted = true; return StorageResult.Success }
        override suspend fun permissions(chatId: String) = emptyList<StoredChatMcpPermission>()
        override suspend fun setPermission(permission: StoredChatMcpPermission) = StorageResult.Success
    }
    private class EmptyCatalogStorage : McpStorage {
        val rows = mutableListOf<StoredMcpServer>()
        override fun observeMcpServers(): Flow<List<StoredMcpServer>> = flowOf(rows)
        override suspend fun readMcpServer(id: String): StoredMcpServer? = null
        override suspend fun upsertMcpServer(server: StoredMcpServer) = StorageResult.Success
        override suspend fun deleteMcpServer(id: String) = StorageResult.Success
        override suspend fun permissions(chatId: String) = emptyList<StoredChatMcpPermission>()
        override suspend fun setPermission(permission: StoredChatMcpPermission) = StorageResult.Success
    }
    private class FailingDeleteSecrets(private val events: MutableList<String>) : McpSecretRepository {
        var cleanupRetries = 0
        override suspend fun read(serverId: String): McpSecrets? = null
        override suspend fun save(serverId: String, secrets: McpSecrets) = CredentialWriteResult.Success
        override suspend fun delete(serverId: String): CredentialWriteResult { events += "secret:$serverId"; return CredentialWriteResult.Failure }
        override suspend fun retryPendingDeletes(): CredentialWriteResult { cleanupRetries++; return CredentialWriteResult.Success }
    }
}
