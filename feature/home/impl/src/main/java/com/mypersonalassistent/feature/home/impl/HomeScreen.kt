package com.mypersonalassistent.feature.home.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mypersonalassistent.feature.home.api.HomeIntent
import com.mypersonalassistent.feature.home.api.HomeState
import kotlinx.coroutines.flow.StateFlow

@Composable fun HomeScreen(stateFlow: StateFlow<HomeState>, accept: (HomeIntent) -> Unit) {
    val state by stateFlow.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenHorizontalPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Мои чаты",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { accept(HomeIntent.EditProfile) },
                modifier = Modifier.semantics { contentDescription = "Изменить профиль" },
            ) { Text("Профиль") }
            TextButton(
                onClick = { accept(HomeIntent.EditKey) },
                modifier = Modifier.semantics { contentDescription = "Сменить ключ" },
            ) { Text("Сменить ключ") }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Не удалось загрузить чаты.")
                    TextButton(onClick = { accept(HomeIntent.Retry) }) { Text("Повторить") }
                }
                state.chats.isEmpty() && state.recovery.none { !it.isCanonicalChat } -> EmptyChatsContent(Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.recovery.firstOrNull { !it.isCanonicalChat }?.let { draft ->
                        item(key = "recovery-${draft.chatId}") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Незавершённая задача", style = MaterialTheme.typography.titleMedium)
                                    Text("Восстановите локальный черновик или удалите его.")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { accept(HomeIntent.ContinueRecovery(draft.chatId)) }) { Text("Продолжить") }
                                        TextButton(onClick = { accept(HomeIntent.DiscardRecovery(draft.chatId)) }) { Text("Удалить") }
                                    }
                                }
                            }
                        }
                    }
                    items(state.chats, key = { it.id }) { chat ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { accept(HomeIntent.Open(chat.id)) }
                                .semantics { contentDescription = "Открыть чат ${chat.title}" }
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = chat.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                if (state.recovery.any { it.isCanonicalChat && it.chatId == chat.id }) Text("Незавершено", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = { accept(HomeIntent.NewChat) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .semantics { contentDescription = "Создать чат" },
            ) { androidx.compose.material3.Icon(Icons.Default.Add, contentDescription = null) }
        }
    }
}

@Composable
private fun EmptyChatsContent(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Начните диалог", style = MaterialTheme.typography.titleLarge)
            Text("Можете начать создавать своего ассистента.")
        }
    }
}

private val ScreenHorizontalPadding = 16.dp
