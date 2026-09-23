package com.mypersonalassistent.feature.chat.impl

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.feature.chat.api.AgentPrimaryAction
import com.mypersonalassistent.feature.chat.api.toUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUiStateTest {
    @Test fun `waiting user exposes reply instruction and accessible pause action`() {
        val ui = checkpoint(AgentRunStatus.WAITING_USER, expected = "").toUiState()
        assertEquals(AgentPrimaryAction.PAUSE, ui.primaryAction)
        assertEquals("Ответьте на вопрос", ui.expectedAction)
        assertEquals("Поставить задачу на паузу", ui.actionContentDescription)
    }

    @Test fun `top bar actions have stable accessible labels`() {
        assertEquals("Поставить задачу на паузу", checkpoint(AgentRunStatus.ACTIVE).toUiState().actionContentDescription)
        assertEquals("Продолжить задачу", checkpoint(AgentRunStatus.PAUSED).toUiState().actionContentDescription)
        assertEquals("Повторить шаг", checkpoint(AgentRunStatus.FAILED, retry = true).toUiState().actionContentDescription)
        assertEquals("Начать новую задачу", checkpoint(AgentRunStatus.FAILED).toUiState().actionContentDescription)
    }

    @Test fun `task memory remains locked until completed checkpoint`() {
        assertFalse(com.mypersonalassistent.feature.chat.api.ChatState("id", checkpoint = checkpoint(AgentRunStatus.WAITING_USER)).taskMemoryEditable)
        assertFalse(com.mypersonalassistent.feature.chat.api.ChatState("id", checkpoint = checkpoint(AgentRunStatus.PAUSED)).taskMemoryEditable)
        assertFalse(com.mypersonalassistent.feature.chat.api.ChatState("id", checkpoint = checkpoint(AgentRunStatus.FAILED)).taskMemoryEditable)
        assertTrue(com.mypersonalassistent.feature.chat.api.ChatState("id", checkpoint = checkpoint(AgentRunStatus.COMPLETED)).taskMemoryEditable)
    }

    private fun checkpoint(status: AgentRunStatus, retry: Boolean = false, expected: String = "Действие") =
        AgentCheckpoint("id", "run", phase = AgentPhase.DONE, runStatus = status, retryAllowed = retry, expectedAction = expected)
}
