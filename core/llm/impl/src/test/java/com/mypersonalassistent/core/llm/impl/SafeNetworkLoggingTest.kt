package com.mypersonalassistent.core.llm.impl

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SafeNetworkLoggingTest {
    @Test fun successLogsOnlySafeRequestMetadataAndStatus() = runBlocking {
        val logs = mutableListOf<String>()
        val client = factory(logs, 10L, 52L).create(MockEngine { respond("ok", HttpStatusCode.OK) })

        client.get("https://api.example.test/chat/completions?api_key=secret-token")

        assertEquals(
            listOf(
                "[1] --> GET api.example.test/chat/completions",
                "[1] <-- 200 42ms"
            ),
            logs
        )
        assertSafe(logs)
        client.close()
    }

    @Test fun httpErrorLogsItsStatus() = runBlocking {
        val logs = mutableListOf<String>()
        val client = factory(logs, 2L, 5L).create(MockEngine { respond("provider error", HttpStatusCode.InternalServerError) })

        client.get("https://api.example.test/chat?token=secret-token")

        assertEquals("[1] <-- 500 3ms", logs.last())
        assertSafe(logs)
        client.close()
    }

    @Test fun transportFailureUsesCategoryWithoutExceptionText() = runBlocking {
        val logs = mutableListOf<String>()
        val client = factory(logs, 4L, 7L).create(MockEngine { throw IOException("secret-token request body") })

        try {
            client.get("https://api.example.test/chat?token=secret-token")
            fail("Expected transport failure")
        } catch (_: IOException) {
        }

        assertEquals("[1] !! transport 3ms", logs.last())
        assertSafe(logs)
        client.close()
    }

    @Test fun cancellationIsPropagatedAndLoggedAsCancelled() = runBlocking {
        val logs = mutableListOf<String>()
        val client = factory(logs, 8L, 11L).create(MockEngine { throw CancellationException("secret-token") })

        try {
            client.get("https://api.example.test/chat?token=secret-token")
            fail("CancellationException must be propagated")
        } catch (_: CancellationException) {
        }

        assertEquals("[1] !! cancelled 3ms", logs.last())
        assertSafe(logs)
        client.close()
    }

    @Test fun disabledLoggingDoesNotWriteToSink() = runBlocking {
        val logs = mutableListOf<String>()
        val client = DeepSeekHttpClientFactory(
            debugLoggingEnabled = false,
            sink = NetworkLogSink { logs += it }
        ).create(MockEngine { respond("ok", HttpStatusCode.OK) })

        client.get("https://api.example.test/chat?token=secret-token")

        assertTrue(logs.isEmpty())
        client.close()
    }

    @Test fun loggerFailureDoesNotBreakRequest() = runBlocking {
        val client = DeepSeekHttpClientFactory(
            debugLoggingEnabled = true,
            sink = NetworkLogSink { throw IllegalStateException("sink failed") },
            elapsedRealtimeMillis = { 0L }
        ).create(MockEngine { respond("ok", HttpStatusCode.OK) })

        val response = client.get("https://api.example.test/chat")

        assertEquals(HttpStatusCode.OK, response.status)
        client.close()
    }

    private fun factory(logs: MutableList<String>, vararg times: Long): DeepSeekHttpClientFactory {
        val iterator = times.iterator()
        return DeepSeekHttpClientFactory(
            debugLoggingEnabled = true,
            sink = NetworkLogSink { logs += it },
            elapsedRealtimeMillis = { iterator.next() }
        )
    }

    private fun assertSafe(logs: List<String>) {
        assertFalse(logs.any { it.contains("secret-token") })
        assertFalse(logs.any { it.contains("request body") })
        assertFalse(logs.any { it.contains("?") })
    }
}
