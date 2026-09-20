package com.mypersonalassistent.core.llm.impl

import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmError
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.llm.api.LlmResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class DeepSeekConfig(val model: String = "deepseek-flash")
class DeepSeekLlm(private val credentials: CredentialRepository, private val config: DeepSeekConfig = DeepSeekConfig(), private val client: HttpClient = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }; install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 } }) : Llm {
    override suspend fun execute(request: LlmRequest): LlmResult {
        if (request.messages.sumOf { it.text.toByteArray().size } > 1_048_576) return LlmResult.Failure(LlmError.CONTEXT_TOO_LARGE)
        val key = credentials.readApiKey() ?: return LlmResult.Failure(LlmError.AUTHORIZATION)
        return try {
            val response = client.post("https://api.deepseek.com/chat/completions") { contentType(ContentType.Application.Json); header("Authorization", "Bearer $key"); setBody(DeepSeekRequest(model = config.model, messages = request.messages.map { DeepSeekMessage(it.role.name.lowercase(), it.text) })) }
            when (response.status) { HttpStatusCode.OK -> try { response.body<DeepSeekResponse>().toResult() } catch (_: JsonConvertException) { LlmResult.Failure(LlmError.INVALID_RESPONSE) } catch (_: SerializationException) { LlmResult.Failure(LlmError.INVALID_RESPONSE) }; HttpStatusCode.Unauthorized -> LlmResult.Failure(LlmError.AUTHORIZATION); HttpStatusCode.PaymentRequired -> LlmResult.Failure(LlmError.BALANCE); HttpStatusCode.TooManyRequests -> LlmResult.Failure(LlmError.RATE_LIMIT); else -> LlmResult.Failure(LlmError.PROVIDER) }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: java.net.SocketTimeoutException) { LlmResult.Failure(LlmError.TIMEOUT) } catch (_: Throwable) { LlmResult.Failure(LlmError.NETWORK) }
    }
    private fun DeepSeekResponse.toResult(): LlmResult { val choice = choices.firstOrNull() ?: return LlmResult.Failure(LlmError.INVALID_RESPONSE); val text = choice.message.content?.takeIf { it.isNotBlank() } ?: return LlmResult.Failure(LlmError.INVALID_RESPONSE); return LlmResult.Success(text, choice.finishReason) }
}
@Serializable private data class DeepSeekRequest(val model: String = "deepseek-flash", val messages: List<DeepSeekMessage>, val stream: Boolean = false, val thinking: Thinking = Thinking(), val max_tokens: Int = 2048)
@Serializable private data class Thinking(val type: String = "disabled")
@Serializable private data class DeepSeekMessage(val role: String, val content: String)
@Serializable private data class DeepSeekResponse(val choices: List<DeepSeekChoice> = emptyList())
@Serializable private data class DeepSeekChoice(val message: DeepSeekMessage, @SerialName("finish_reason") val finishReason: String? = null)
