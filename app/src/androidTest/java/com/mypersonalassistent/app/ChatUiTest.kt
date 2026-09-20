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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.feature.chat.api.ChatIntent
import com.mypersonalassistent.feature.chat.api.ChatState
import com.mypersonalassistent.feature.chat.impl.ChatScreen
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
                ChatIntent.ConfirmSave, ChatIntent.Discard, ChatIntent.Load -> Unit
            }
        }

        fun failActiveRequest() {
            val user = state.value.messages.lastOrNull { it.role == MessageRole.USER } ?: return
            state.update {
                it.copy(
                    messages = it.messages.filterNot { message -> message.id == user.id },
                    draft = user.content,
                    sending = false,
                )
            }
            technicalErrorCount.update { it + 1 }
        }

        private fun beginFakeRequest() {
            val before = state.value
            if (before.sending || before.draft.isBlank()) return
            sendCount += 1
            state.value = before.copy(
                messages = before.messages + ChatMessage(
                    id = "pending-$sendCount",
                    role = MessageRole.USER,
                    content = before.draft,
                ),
                draft = "",
                sending = true,
            )
        }
    }
}
