package com.mypersonalassistent.core.agent.api

import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.memory.api.TaskMemory

interface AgentRequestComposer {
    suspend fun compose(
        chatId: String,
        messages: List<ChatMessage>,
        taskMemory: TaskMemory? = null,
    ): LlmRequest
}
