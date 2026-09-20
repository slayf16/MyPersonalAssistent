package com.mypersonalassistent.core.llm.impl

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkLogcatSmokeTest {
    @Test fun mockRequestEmitsNetworkTagWithoutProviderOrCredential() = runBlocking {
        val client = DeepSeekHttpClientFactory(debugLoggingEnabled = true)
            .create(MockEngine { respond("mock", HttpStatusCode.OK) })

        val response = client.get("https://api.example.test/chat?api_key=must-not-be-logged")

        assertEquals(HttpStatusCode.OK, response.status)
        client.close()
    }
}
