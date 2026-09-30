package com.mypersonalassistent.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.feature.chat.api.ChatIntent
import com.mypersonalassistent.feature.chat.api.ChatState
import com.mypersonalassistent.feature.chat.impl.ChatScreen
import com.mypersonalassistent.core.mcp.api.McpPermission
import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.agent.api.McpCallStatus
import com.mypersonalassistent.core.agent.api.PendingMcpCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI coverage for the public ChatScreen contract. The fixture drives only its
 * StateFlow and accepts user intents; provider/store behavior is covered by
 * feature:chat unit tests. No credentials or network calls are used here.
 */
@RunWith(AndroidJUnit4::class)
class ChatUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sendingMessageShowsSingleAccessibleTypingBubbleAndLocksInput() {
        val fixture = ChatFixture()
        composeRule.setContent { ChatHost(fixture) }

        composeRule.messageField().performTextInput("Проверка ожидания")
        composeRule.onNodeWithContentDescription("Отправить").performClick()

        composeRule.onNodeWithText("Проверка ожидания").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Ассистент отвечает").assertCountEquals(1)
        composeRule.messageField().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(1, fixture.sendCount)
            assertEquals(1, fixture.state.value.messages.count { it.role == MessageRole.USER })
        }
    }

    @Test
    fun providerFailureRestoresExactDraftAndShowsOneTechnicalSnackbar() {
        val fixture = ChatFixture()
        val rawDraft = "  Точный текст запроса  "
        composeRule.setContent { ChatHost(fixture) }

        composeRule.messageField().performTextInput(rawDraft)
        composeRule.onNodeWithContentDescription("Отправить").performClick()
        composeRule.runOnIdle { fixture.failActiveRequest() }

        composeRule.onNode(hasSetTextAction() and hasText(rawDraft)).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithContentDescription("Отправить").assertIsEnabled()
        composeRule.onAllNodesWithContentDescription("Ассистент отвечает").assertCountEquals(0)
        composeRule.onAllNodesWithText("Техническая ошибка").assertCountEquals(1)
        composeRule.runOnIdle {
            assertEquals(rawDraft, fixture.state.value.draft)
            assertEquals(0, fixture.state.value.messages.size)
            assertEquals(1, fixture.technicalErrorCount.value)
        }
    }

    @Test
    fun backDispatcherOpensAndThenClosesSaveDialog() {
        val fixture = ChatFixture()
        composeRule.setContent { ChatHost(fixture) }

        dispatchBackAndAwait(fixture, ChatIntent.RequestExit)
        composeRule.onNodeWithText("Сохранить чат?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(ChatIntent.RequestExit, fixture.intents.last()) }

        dispatchBackAndAwait(fixture, ChatIntent.CloseDialog)
        composeRule.onNodeWithText("Сохранить чат?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(ChatIntent.CloseDialog, fixture.intents.last()) }
    }

    @Test fun mcpSelectorTogglesOnlyTheChosenServer() {
        val state = MutableStateFlow(ChatState("ui-chat", mcpSelectorOpen = true, mcpPermissions = listOf(McpPermission("server", false, "Research MCP"))))
        val intents = mutableListOf<ChatIntent>()
        composeRule.setContent { MaterialTheme { ChatScreen(state) { intents += it } } }

        composeRule.onNodeWithContentDescription("Research MCP: выключен").performClick()
        composeRule.runOnIdle { assertEquals(ChatIntent.SetMcpPermission("server", true), intents.single()) }
    }

    @Test fun mcpSelectorScrollsThroughMaximumTenServers() {
        val permissions = (1..10).map { McpPermission("server-$it", false, "Server $it") }
        val state = MutableStateFlow(ChatState("ui-chat", mcpSelectorOpen = true, mcpPermissions = permissions))
        composeRule.setContent { MaterialTheme { ChatScreen(state) {} } }

        composeRule.onNodeWithContentDescription("Server 10: выключен").performScrollTo().assertIsDisplayed()
    }

    @Test fun mcpApprovalSheetRequiresExplicitAllowOrDeny() {
        val pending = PendingMcpCall("server", "lookup", "call-1", "{\"q\":\"safe\"}", "digest-1", McpCallStatus.WAITING_CONFIRMATION)
        val state = MutableStateFlow(ChatState("ui-chat", checkpoint = AgentCheckpoint("ui-chat", "run", phase = AgentPhase.EXECUTION, runStatus = AgentRunStatus.WAITING_MCP_APPROVAL, pendingMcpCalls = listOf(pending))))
        val intents = mutableListOf<ChatIntent>()
        composeRule.setContent { MaterialTheme { ChatScreen(state) { intents += it } } }

        composeRule.onNodeWithText("Подтвердить вызов MCP").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Разрешить вызов MCP").performClick()
        composeRule.runOnIdle { assertEquals(ChatIntent.DecideMcpCall("digest-1", true), intents.single()) }
    }

    @Test fun mcpApprovalBottomSheetKeepsLongMaskedArgumentsScrollableAndActionsVisible() {
        val secret = "raw-password-must-not-render"
        val display = "{\"password\":\"••••\",\"payload\":\"${"x".repeat(3000)}\"}"
        val pending = PendingMcpCall(
            "server", "long-tool", "call-long", "{\"password\":\"$secret\"}", "digest-long",
            McpCallStatus.WAITING_CONFIRMATION,
            displayArguments = display,
        )
        val state = MutableStateFlow(ChatState("ui-chat", checkpoint = AgentCheckpoint("ui-chat", "run", phase = AgentPhase.EXECUTION, runStatus = AgentRunStatus.WAITING_MCP_APPROVAL, pendingMcpCalls = listOf(pending))))
        composeRule.setContent { MaterialTheme { ChatScreen(state) {} } }

        composeRule.onNodeWithContentDescription("Маскированные аргументы MCP").assertIsDisplayed()
        composeRule.onNodeWithText(secret).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Отклонить вызов MCP").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Разрешить вызов MCP").assertIsDisplayed()
    }

    @Test fun dismissingMcpApprovalDeniesAndSuppressedRunNeedsExplicitReenable() {
        val pending = PendingMcpCall("server", "lookup", "call-1", "{}", "digest-2", McpCallStatus.WAITING_CONFIRMATION)
        val state = MutableStateFlow(ChatState("ui-chat", checkpoint = AgentCheckpoint("ui-chat", "run", phase = AgentPhase.EXECUTION, runStatus = AgentRunStatus.WAITING_MCP_APPROVAL, pendingMcpCalls = listOf(pending))))
        val intents = mutableListOf<ChatIntent>()
        composeRule.setContent { MaterialTheme { ChatScreen(state) { intents += it } } }
        composeRule.onNodeWithText("Отклонить").performClick()
        composeRule.runOnIdle { assertEquals(ChatIntent.DecideMcpCall("digest-2", false), intents.single()) }

        intents.clear()
        composeRule.runOnIdle { state.value = ChatState("ui-chat", checkpoint = AgentCheckpoint("ui-chat", "run", phase = AgentPhase.EXECUTION, runStatus = AgentRunStatus.ACTIVE, mcpSuppressed = true)) }
        composeRule.onNodeWithContentDescription("Снова разрешить MCP").performClick()
        composeRule.runOnIdle { assertEquals(ChatIntent.ReenableMcp, intents.single()) }
    }

    @Test fun unknownExternalOutcomeIsVisibleBeforeASeparateNewApproval() {
        val pending = PendingMcpCall("server", "lookup", "fresh-call", "{}", "fresh-digest", McpCallStatus.WAITING_CONFIRMATION)
        val state = MutableStateFlow(ChatState("ui-chat", checkpoint = AgentCheckpoint(
            "ui-chat", "run", phase = AgentPhase.EXECUTION,
            runStatus = AgentRunStatus.WAITING_MCP_APPROVAL,
            pendingMcpCalls = listOf(pending),
            mcpOutcomeUnknown = true,
        )))
        composeRule.setContent { MaterialTheme { ChatScreen(state) {} } }

        composeRule.onNodeWithText("Предыдущее действие MCP могло выполниться").assertIsDisplayed()
        composeRule.onNodeWithText("Предыдущее внешнее действие могло выполниться. Новый вызов требует отдельного подтверждения.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Разрешить вызов MCP").assertIsDisplayed()
    }

    private fun dispatchBackAndAwait(fixture: ChatFixture, expected: ChatIntent) {
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.intents.lastOrNull() == expected }
        composeRule.waitForIdle()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.messageField() =
        onNodeWithContentDescription("Поле сообщения")

    @Composable
    private fun ChatHost(fixture: ChatFixture) {
        val errors by fixture.technicalErrorCount.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(errors) {
            if (errors > 0) snackbar.showSnackbar("Техническая ошибка")
        }
        MaterialTheme {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) {
                ChatScreen(fixture.state, fixture::accept)
            }
        }
    }

    private class ChatFixture {
        val state = MutableStateFlow(ChatState(id = "ui-chat"))
        val technicalErrorCount = MutableStateFlow(0)
        val intents = mutableListOf<ChatIntent>()
        var sendCount = 0
            private set

        fun accept(intent: ChatIntent) {
            intents += intent
            when (intent) {
                is ChatIntent.ChangeDraft -> state.update { it.copy(draft = intent.value) }
                ChatIntent.Send -> beginFakeRequest()
                ChatIntent.RequestExit -> state.update { it.copy(saveDialog = true) }
                ChatIntent.CloseDialog -> state.update { it.copy(saveDialog = false) }
                ChatIntent.OpenTaskEditor -> state.update { it.copy(taskEditorOpen = true) }
                ChatIntent.CloseTaskEditor -> state.update { it.copy(taskEditorOpen = false) }
                is ChatIntent.ChangeTaskGoal -> state.update { it.copy(taskDraft = it.taskDraft.copy(goal = intent.value)) }
                is ChatIntent.ChangeTaskConstraints -> state.update { it.copy(taskDraft = it.taskDraft.copy(constraints = intent.value)) }
                is ChatIntent.ChangeTaskResult -> state.update { it.copy(taskDraft = it.taskDraft.copy(desiredResult = intent.value)) }
                is ChatIntent.ChangeTaskDecisions -> state.update { it.copy(taskDraft = it.taskDraft.copy(decisions = intent.value)) }
                ChatIntent.ApplyTaskMemory -> state.update { it.copy(taskEditorOpen = false) }
                ChatIntent.ConfirmSave, ChatIntent.Discard, ChatIntent.Load -> Unit
                else -> Unit
            }
        }

        fun failActiveRequest() {
            val user = state.value.messages.lastOrNull { it.role == MessageRole.USER } ?: return
            state.update {
                it.copy(
                    messages = it.messages.filterNot { message -> message.id == user.id },
                    draft = user.content,
                    checkpoint = null,
                )
            }
            technicalErrorCount.update { it + 1 }
        }

        private fun beginFakeRequest() {
            val before = state.value
            if (!before.composerEditable || before.draft.isBlank()) return
            sendCount += 1
            state.value = before.copy(
                messages = before.messages + ChatMessage(
                    id = "pending-$sendCount",
                    role = MessageRole.USER,
                    content = before.draft,
                ),
                draft = "",
                checkpoint = AgentCheckpoint("ui-chat", "pending-$sendCount", phase = AgentPhase.EXECUTION, runStatus = AgentRunStatus.ACTIVE, inFlight = true),
            )
        }
    }
}
