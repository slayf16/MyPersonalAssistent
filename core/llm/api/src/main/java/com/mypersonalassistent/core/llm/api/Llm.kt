package com.mypersonalassistent.core.llm.api
import kotlinx.serialization.json.JsonElement

enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }
data class LlmMessage(val role: LlmRole, val text: String, val toolCallId: String? = null)
data class LlmToolDefinition(val name: String, val description: String? = null, val inputSchema: JsonElement)
data class LlmToolCall(val id: String, val name: String, val arguments: JsonElement)
/** One completed external action, kept with its provider call id for the next turn. */
data class LlmToolExchange(val call: LlmToolCall, val result: String, val isError: Boolean, val structuredContent: JsonElement? = null)
data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDefinition> = emptyList(),
    val toolExchanges: List<LlmToolExchange> = emptyList(),
)
interface Llm { suspend fun execute(request: LlmRequest): LlmResult }
sealed interface LlmResult {
    data class Success(
        val text: String,
        val finishReason: String?,
        val toolCalls: List<LlmToolCall> = emptyList(),
    ) : LlmResult

    /** Safe provider metadata only: response bodies, headers and credentials never cross this API. */
    data class Failure(
        val error: LlmError,
        val httpStatus: Int? = null,
    ) : LlmResult
}
enum class LlmError { AUTHORIZATION, BALANCE, RATE_LIMIT, NETWORK, TIMEOUT, CONTEXT_TOO_LARGE, PROVIDER, INVALID_RESPONSE }
