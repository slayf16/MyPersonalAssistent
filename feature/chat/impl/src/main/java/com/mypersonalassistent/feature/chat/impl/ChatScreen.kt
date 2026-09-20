package com.mypersonalassistent.feature.chat.impl

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.feature.chat.api.ChatIntent
import com.mypersonalassistent.feature.chat.api.ChatState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ChatScreen(stateFlow: StateFlow<ChatState>, accept: (ChatIntent) -> Unit) {
    val state by stateFlow.collectAsState()

    BackHandler {
        if (state.saveDialog) {
            accept(ChatIntent.CloseDialog)
        } else if (!state.saving) {
            accept(ChatIntent.RequestExit)
        }
    }

    ChatContent(state = state, accept = accept)

    if (state.saveDialog) {
        SaveChatDialog(state = state, accept = accept)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(state: ChatState, accept: (ChatIntent) -> Unit) {
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    val canEdit = !state.loading && !state.loadFailed && !state.sending && !state.saving

    LaunchedEffect(listState) {
        snapshotFlow { listState.isAtBottom() }
            .collect { followLatest = it }
    }
    LaunchedEffect(state.messages.size, state.sending) {
        if (followLatest) {
            val lastIndex = state.messages.lastIndex + if (state.sending) 1 else 0
            if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чат") },
                navigationIcon = {
                    IconButton(
                        onClick = { accept(ChatIntent.RequestExit) },
                        enabled = !state.saving,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                        )
                    }
                },
            )
        },
        bottomBar = {
            ChatInput(
                state = state,
                enabled = canEdit,
                accept = accept,
            )
        },
    ) { contentPadding ->
        when {
            state.loading -> LoadingContent(contentPadding)
            state.loadFailed -> LoadFailedContent(contentPadding)
            else -> MessageList(
                messages = state.messages,
                sending = state.sending,
                listState = listState,
                contentPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    sending: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
        if (sending) {
            item(key = "assistant-typing") { AssistantTypingBubble() }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AssistantTypingBubble() {
    val transition = rememberInfiniteTransition(label = "assistantTyping")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typingDots",
    )
    val dots = when {
        phase < 0.34f -> "."
        phase < 0.67f -> ".."
        else -> "..."
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.semantics { contentDescription = "Ассистент отвечает" },
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
        ) {
            Text(
                text = dots,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clearAndSetSemantics { },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ChatInput(state: ChatState, enabled: Boolean, accept: (ChatIntent) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = { accept(ChatIntent.ChangeDraft(it)) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                label = { Text("Сообщение") },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (enabled && state.draft.isNotBlank()) accept(ChatIntent.Send) },
                ),
            )
            Button(
                onClick = { accept(ChatIntent.Send) },
                enabled = enabled && state.draft.isNotBlank(),
            ) {
                Text("Отправить")
            }
        }
    }
}

@Composable
private fun LoadingContent(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LoadFailedContent(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text("Не удалось загрузить чат")
    }
}

@Composable
private fun SaveChatDialog(state: ChatState, accept: (ChatIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { accept(ChatIntent.CloseDialog) },
        title = { Text("Сохранить чат?") },
        confirmButton = {
            Button(
                onClick = { accept(ChatIntent.ConfirmSave) },
                enabled = !state.saving && !state.loading && !state.loadFailed,
            ) { Text("Да") }
        },
        dismissButton = {
            Button(
                onClick = { accept(ChatIntent.Discard) },
                enabled = !state.saving,
            ) { Text("Нет") }
        },
    )
}

private fun LazyListState.isAtBottom(): Boolean {
    val layout = layoutInfo
    val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= layout.totalItemsCount - 2
}
