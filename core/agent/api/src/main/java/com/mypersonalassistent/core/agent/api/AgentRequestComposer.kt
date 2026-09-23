package com.mypersonalassistent.core.agent.api

import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.core.invariants.api.InvariantSnapshot

interface AgentRequestComposer {
    suspend fun compose(
        chatId: String,
        messages: List<ChatMessage>,
        taskMemory: TaskMemory? = null,
    ): LlmRequest

    /** Builds a bounded, phase-specific request while preserving the latest user input. */
    suspend fun composeForTask(
        chatId: String,
        messages: List<ChatMessage>,
        taskMemory: TaskMemory,
        checkpointContext: String,
        phaseInstruction: String,
    ): LlmRequest = compose(chatId, messages, taskMemory)

    /** User rules are an immutable system-policy block below app security and above user memory. */
    suspend fun composeForTaskWithInvariants(
        chatId: String,
        messages: List<ChatMessage>,
        taskMemory: TaskMemory,
        checkpointContext: String,
        phaseInstruction: String,
        snapshot: InvariantSnapshot,
    ): LlmRequest = composeForTask(chatId, messages, taskMemory, checkpointContext, phaseInstruction)
}
