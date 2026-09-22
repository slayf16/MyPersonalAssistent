package com.mypersonalassistent.core.llm.api

enum class LlmRole { SYSTEM, USER, ASSISTANT }
data class LlmMessage(val role: LlmRole, val text: String)
data class LlmRequest(val messages: List<LlmMessage>)
interface Llm { suspend fun execute(request: LlmRequest): LlmResult }
sealed interface LlmResult { data class Success(val text: String, val finishReason: String?) : LlmResult; data class Failure(val error: LlmError) : LlmResult }
enum class LlmError { AUTHORIZATION, BALANCE, RATE_LIMIT, NETWORK, TIMEOUT, CONTEXT_TOO_LARGE, PROVIDER, INVALID_RESPONSE }
