package com.mypersonalassistent.core.llm.impl

import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.core.credentials.api.CredentialWriteResult
import com.mypersonalassistent.core.llm.api.LlmError
import com.mypersonalassistent.core.llm.api.LlmMessage
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.llm.api.LlmResult
import com.mypersonalassistent.core.llm.api.LlmRole
import com.mypersonalassistent.core.llm.api.LlmToolCall
import com.mypersonalassistent.core.llm.api.LlmToolExchange
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DeepSeekLlmTest {
    @Test fun executeMapsAuthorizedDeepSeekResponse() = runBlocking {
        val client = mockClient(HttpStatusCode.OK, """{"choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}""") { request ->
            assertEquals("Bearer key", request.headers[HttpHeaders.Authorization])
            assertEquals("POST", request.method.value)
            val payload = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(true, payload.contains("\"model\":\"deepseek-flash\""))
            assertEquals(true, payload.contains("\"stream\":false"))
            assertEquals(true, payload.contains("\"thinking\":{\"type\":\"disabled\"}"))
            assertEquals(true, payload.contains("\"max_tokens\":2048"))
        }
        val result = DeepSeekLlm(FakeCredentials, client = client).execute(request("question"))
        assertEquals(LlmResult.Success("answer", "stop"), result)
    }
    @Test fun executeMapsUnauthorizedToDomainError() = runBlocking {
        val result = DeepSeekLlm(FakeCredentials, client = mockClient(HttpStatusCode.Unauthorized, "{}")).execute(request("question"))
        assertEquals(LlmResult.Failure(LlmError.AUTHORIZATION, 401), result)
    }
    @Test fun executeMapsBalanceRateLimitAndProviderFailures() = runBlocking {
        assertFailure(HttpStatusCode.PaymentRequired, LlmError.BALANCE)
        assertFailure(HttpStatusCode.TooManyRequests, LlmError.RATE_LIMIT)
        assertFailure(HttpStatusCode.InternalServerError, LlmError.PROVIDER)
    }
    @Test fun malformedSuccessfulResponseMapsToInvalidResponse() = runBlocking {
        val result = DeepSeekLlm(FakeCredentials, client = mockClient(HttpStatusCode.OK, "{not-json")).execute(request("question"))
        assertEquals(LlmResult.Failure(LlmError.INVALID_RESPONSE, 200), result)
    }
    @Test fun socketTimeoutMapsToTimeout() = runBlocking {
        val result = DeepSeekLlm(FakeCredentials, client = throwingClient(SocketTimeoutException("test timeout"))).execute(request("question"))
        assertEquals(LlmResult.Failure(LlmError.TIMEOUT), result)
    }
    @Test fun cancellationIsPropagated() = runBlocking {
        try {
            DeepSeekLlm(FakeCredentials, client = throwingClient(CancellationException("test cancellation"))).execute(request("question"))
            fail("CancellationException must not be converted to a result")
        } catch (_: CancellationException) {
        }
    }
    @Test fun oversizedContextFailsBeforeHttpCall() = runBlocking {
        var calls = 0
        val client = mockClient(HttpStatusCode.OK, "{}") { calls++ }
        val result = DeepSeekLlm(FakeCredentials, client = client).execute(request("x".repeat(1_048_577)))
        assertEquals(LlmResult.Failure(LlmError.CONTEXT_TOO_LARGE), result)
        assertEquals(0, calls)
    }
    @Test fun `structured tool result is serialized with text and original call id`() = runBlocking {
        val client = mockClient(HttpStatusCode.OK, """{"choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}""") { request ->
            val payload = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val root = Json.parseToJsonElement(payload).jsonObject
            val messages = root.getValue("messages").jsonArray
            val assistantMessage = messages[messages.lastIndex - 1].jsonObject
            assertEquals("assistant", assistantMessage.getValue("role").jsonPrimitive.content)
            val serializedCall = assistantMessage.getValue("tool_calls").jsonArray.single().jsonObject
            assertEquals("opaque-call-id", serializedCall.getValue("id").jsonPrimitive.content)
            assertEquals("function", serializedCall.getValue("type").jsonPrimitive.content)
            assertEquals(
                "safe_tool",
                serializedCall.getValue("function").jsonObject.getValue("name").jsonPrimitive.content,
            )
            val toolMessage = messages.last().jsonObject
            assertEquals("opaque-call-id", toolMessage.getValue("tool_call_id").jsonPrimitive.content)
            val content = Json.parseToJsonElement(toolMessage.getValue("content").jsonPrimitive.content).jsonObject
            assertEquals("forecast", content.getValue("text").jsonPrimitive.content)
            assertEquals(21, content.getValue("structuredContent").jsonObject.getValue("temperature").jsonPrimitive.int)
        }
        val exchange = LlmToolExchange(
            LlmToolCall("opaque-call-id", "safe_tool", buildJsonObject {}),
            "forecast",
            false,
            buildJsonObject { put("temperature", 21) },
        )

        val result = DeepSeekLlm(FakeCredentials, client = client).execute(
            LlmRequest(listOf(LlmMessage(LlmRole.USER, "weather")), toolExchanges = listOf(exchange)),
        )

        assertEquals(LlmResult.Success("answer", "stop"), result)
    }
    private fun request(text: String) = LlmRequest(listOf(LlmMessage(LlmRole.USER, text)))
    private suspend fun assertFailure(status: HttpStatusCode, expected: LlmError) {
        val result = DeepSeekLlm(FakeCredentials, client = mockClient(status, "{}")).execute(request("question"))
        assertEquals(LlmResult.Failure(expected, status.value), result)
    }
    private fun mockClient(status: HttpStatusCode, body: String, inspect: (io.ktor.client.request.HttpRequestData) -> Unit = {}): HttpClient = HttpClient(MockEngine { request -> inspect(request); respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) } }
    private fun throwingClient(failure: Throwable): HttpClient = HttpClient(MockEngine { throw failure }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) } }
    private object FakeCredentials : CredentialRepository { override suspend fun readApiKey() = "key"; override suspend fun saveApiKey(value: String) = CredentialWriteResult.Success }
}
