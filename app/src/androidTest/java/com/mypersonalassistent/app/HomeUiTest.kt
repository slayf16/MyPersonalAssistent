package com.mypersonalassistent.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.feature.home.api.HomeIntent
import com.mypersonalassistent.feature.home.api.HomeState
import com.mypersonalassistent.feature.home.impl.HomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the public HomeScreen contract without a database or network fixture. */
@RunWith(AndroidJUnit4::class)
class HomeUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun errorShowsRetryInsteadOfEmptyStateAndRetryRevealsContent() {
        val state = MutableStateFlow(HomeState(isLoading = false, error = true))
        var lastIntent: HomeIntent? = null
        composeRule.setContent {
            MaterialTheme { HomeScreen(state) { lastIntent = it } }
        }

        composeRule.onNodeWithText("Не удалось загрузить чаты.").assertIsDisplayed()
        composeRule.onNodeWithText("Можете начать создавать своего ассистента.").assertDoesNotExist()
        composeRule.onNodeWithText("Повторить").performClick()
        composeRule.runOnIdle { assertEquals(HomeIntent.Retry, lastIntent) }

        composeRule.runOnIdle {
            state.value = HomeState(
                isLoading = false,
                chats = listOf(ChatSummary("chat-1", "Сохранённый чат", 1)),
            )
        }
        composeRule.onNodeWithText("Сохранённый чат").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertDoesNotExist()
    }
}
