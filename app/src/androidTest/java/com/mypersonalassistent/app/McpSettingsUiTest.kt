package com.mypersonalassistent.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mypersonalassistent.core.mcp.api.McpServer
import com.mypersonalassistent.feature.mcpsettings.api.McpEditorState
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsIntent
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsState
import com.mypersonalassistent.feature.mcpsettings.impl.McpSettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpSettingsUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun addEditAndDeleteAreExpressedOnlyAsMviIntents() {
        val fixture = SettingsFixture()
        composeRule.setContent { MaterialTheme { McpSettingsScreen(fixture.state, fixture::accept) {} } }
        composeRule.onNodeWithContentDescription("Добавить MCP").performClick()
        composeRule.onNodeWithText("Название").performTextInput("Test MCP")
        composeRule.onNodeWithText("HTTP endpoint").performTextInput("https://example.test/mcp")
        composeRule.onNodeWithText("Сохранить").performClick()
        composeRule.onNodeWithText("Test MCP").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Изменить Test MCP").performClick()
        composeRule.onNodeWithText("Сохранить").performClick()
        composeRule.onNodeWithContentDescription("Удалить Test MCP").performClick()
        composeRule.onNodeWithContentDescription("Подтвердить удаление Test MCP").performClick()
        composeRule.onNodeWithText("Test MCP").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(2, fixture.intents.count { it == McpSettingsIntent.Save })
            assertEquals(1, fixture.intents.count { it == McpSettingsIntent.ConfirmDelete })
        }
    }

    @Test fun tokenAndApiKeyFieldsUseMaskedPresentation() {
        val fixture = SettingsFixture()
        composeRule.setContent { MaterialTheme { McpSettingsScreen(fixture.state, fixture::accept) {} } }
        composeRule.onNodeWithContentDescription("Добавить MCP").performClick()
        composeRule.onNodeWithContentDescription("Секретный token MCP").performTextInput("token-visible-only-in-state")
        composeRule.onNodeWithContentDescription("Секретный API key MCP").performTextInput("key-visible-only-in-state")

        composeRule.onNodeWithText("token-visible-only-in-state").assertDoesNotExist()
        composeRule.onNodeWithText("key-visible-only-in-state").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Секретный token MCP").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Секретный API key MCP").assertIsDisplayed()
    }

    private class SettingsFixture {
        val state = MutableStateFlow(McpSettingsState())
        val intents = mutableListOf<McpSettingsIntent>()
        fun accept(intent: McpSettingsIntent) {
            intents += intent
            val current = state.value
            when (intent) {
                McpSettingsIntent.Add -> state.value = current.copy(editor = McpEditorState())
                is McpSettingsIntent.ChangeName -> state.value = current.copy(editor = current.editor?.copy(name = intent.value))
                is McpSettingsIntent.ChangeEndpoint -> state.value = current.copy(editor = current.editor?.copy(endpoint = intent.value))
                is McpSettingsIntent.ChangeToken -> state.value = current.copy(editor = current.editor?.copy(token = intent.value))
                is McpSettingsIntent.ChangeApiKey -> state.value = current.copy(editor = current.editor?.copy(apiKey = intent.value))
                McpSettingsIntent.Save -> {
                    val editor = current.editor ?: return
                    val server = McpServer(editor.id ?: "server", editor.name, editor.endpoint, 1, 1)
                    state.value = current.copy(servers = listOf(server), editor = null)
                }
                is McpSettingsIntent.Edit -> current.servers.firstOrNull { it.id == intent.id }?.let { server ->
                    state.value = current.copy(editor = McpEditorState(server.id, server.name, server.endpoint))
                }
                is McpSettingsIntent.RequestDelete -> current.servers.firstOrNull { it.id == intent.id }?.let { state.value = current.copy(deleting = it) }
                McpSettingsIntent.ConfirmDelete -> state.value = current.copy(servers = current.servers.filterNot { it.id == current.deleting?.id }, deleting = null)
                McpSettingsIntent.CloseEditor -> state.value = current.copy(editor = null)
                McpSettingsIntent.CancelDelete -> state.value = current.copy(deleting = null)
                else -> Unit
            }
        }
    }
}
