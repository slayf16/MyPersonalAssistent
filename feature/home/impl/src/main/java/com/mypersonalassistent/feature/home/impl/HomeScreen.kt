package com.mypersonalassistent.feature.home.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.CircularProgressIndicator
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { accept(HomeIntent.EditKey) }) { Text("Сменить ключ") }
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
                state.chats.isEmpty() -> Text("Можете начать создавать своего ассистента.", Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize().padding(bottom = 80.dp)) {
                    items(state.chats, key = { it.id }) { chat ->
                        ListItem(
                            headlineContent = { Text(chat.title) },
                            modifier = Modifier.clickable { accept(HomeIntent.Open(chat.id)) }
                                .semantics { contentDescription = "Открыть чат ${chat.title}" }
                                .padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            ExtendedFloatingActionButton(onClick = { accept(HomeIntent.NewChat) }, icon = { androidx.compose.material3.Icon(Icons.Default.Add, null) }, text = { Text("Создать чат") }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).semantics { contentDescription = "Создать чат" })
        }
    }
}
