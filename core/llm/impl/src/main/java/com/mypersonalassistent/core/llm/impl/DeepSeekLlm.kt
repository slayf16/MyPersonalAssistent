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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DeepSeekConfig(val model: String = "deepseek-flash")
class DeepSeekLlm(
    private val credentials: CredentialRepository,
    private val config: DeepSeekConfig = DeepSeekConfig(),
    private val client: HttpClient
) : Llm {
    override suspend fun execute(request: LlmRequest): LlmResult {
        if (request.messages.sumOf { it.text.toByteArray().size } > 1_048_576) return LlmResult.Failure(
            LlmError.CONTEXT_TOO_LARGE
        )
        val key = credentials.readApiKey() ?: return LlmResult.Failure(LlmError.AUTHORIZATION)
        return try {
            val messages = buildList {
                addAll(request.messages.map {
                    DeepSeekMessage(
                        it.role.name.lowercase(),
                        it.text,
                        toolCallId = it.toolCallId
                    )
                })
                request.toolExchanges.forEach { exchange ->
                    add(
                        DeepSeekMessage(
                            "assistant",
                            toolCalls = listOf(
                                DeepSeekToolCall(
                                    id = exchange.call.id,
                                    type = "function",
                                    function = DeepSeekToolCallFunction(
                                        exchange.call.name,
                                        exchange.call.arguments.toString()
                                    )
                                )
                            )
                        )
                    )
                    val content = exchange.structuredContent?.let { structured ->
                        buildJsonObject {
                            put("text", exchange.result); put(
                            "structuredContent",
                            structured
                        )
                        }.toString()
                    } ?: exchange.result
                    add(DeepSeekMessage("tool", content, toolCallId = exchange.call.id))
                }
            }
            val response = client.post("https://api.deepseek.com/chat/completions") {
                contentType(ContentType.Application.Json); header(
                "Authorization",
                "Bearer $key"
            ); setBody(
                DeepSeekRequest(
                    model = config.model,
                    messages = messages,
                    tools = request.tools.map {
                        DeepSeekTool(
                            "function",
                            DeepSeekFunction(it.name, it.description, it.inputSchema)
                        )
                    })
            )
            }
            when (response.status) {
                HttpStatusCode.OK -> try {
                    when (val result = response.body<DeepSeekResponse>().toResult()) {
                        is LlmResult.Failure -> result.copy(httpStatus = response.status.value)
                        is LlmResult.Success -> result
                    }
                } catch (_: JsonConvertException) {
                    LlmResult.Failure(LlmError.INVALID_RESPONSE, response.status.value)
                } catch (_: SerializationException) {
                    LlmResult.Failure(LlmError.INVALID_RESPONSE, response.status.value)
                }; HttpStatusCode.Unauthorized -> LlmResult.Failure(
                    LlmError.AUTHORIZATION,
                    response.status.value,
                ); HttpStatusCode.PaymentRequired -> LlmResult.Failure(
                    LlmError.BALANCE,
                    response.status.value,
                ); HttpStatusCode.TooManyRequests -> LlmResult.Failure(
                    LlmError.RATE_LIMIT,
                    response.status.value,
                ); else -> LlmResult.Failure(
                    LlmError.PROVIDER,
                    response.status.value,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.net.SocketTimeoutException) {
            LlmResult.Failure(LlmError.TIMEOUT)
        } catch (_: Throwable) {
            LlmResult.Failure(LlmError.NETWORK)
        }
    }

    private fun DeepSeekResponse.toResult(): LlmResult {
        val choice = choices.firstOrNull() ?: return LlmResult.Failure(LlmError.INVALID_RESPONSE);
        val calls = choice.message.toolCalls.mapNotNull { call ->
            runCatching {
                com.mypersonalassistent.core.llm.api.LlmToolCall(
                    call.id,
                    call.function.name,
                    Json.parseToJsonElement(call.function.arguments)
                )
            }.getOrNull()
        };
        val text =
            choice.message.content.orEmpty(); return if (text.isBlank() && calls.isEmpty()) LlmResult.Failure(
            LlmError.INVALID_RESPONSE
        ) else LlmResult.Success(text, choice.finishReason, calls)
    }
}

@Serializable
private data class DeepSeekRequest(
    val model: String = "deepseek-flash",
    val messages: List<DeepSeekMessage>,
    val tools: List<DeepSeekTool> = emptyList(),
    val stream: Boolean = false,
    val thinking: Thinking = Thinking(),
    val max_tokens: Int = 2048
)

@Serializable
private data class Thinking(val type: String = "disabled")

@Serializable
private data class DeepSeekMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekToolCall> = emptyList(),
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
private data class DeepSeekResponse(val choices: List<DeepSeekChoice> = emptyList())

@Serializable
private data class DeepSeekChoice(
    val message: DeepSeekMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
private data class DeepSeekTool(val type: String, val function: DeepSeekFunction)

@Serializable
private data class DeepSeekFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement
)

@Serializable
private data class DeepSeekToolCall(
    val id: String,
    /** Required by DeepSeek when the assistant tool-call message is sent back after execution. */
    val type: String,
    val function: DeepSeekToolCallFunction,
)

@Serializable
private data class DeepSeekToolCallFunction(val name: String, val arguments: String)
