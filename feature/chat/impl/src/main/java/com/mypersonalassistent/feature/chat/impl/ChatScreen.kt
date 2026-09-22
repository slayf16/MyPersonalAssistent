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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
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
        when {
            state.taskEditorOpen -> accept(ChatIntent.CloseTaskEditor)
            state.saveDialog -> accept(ChatIntent.CloseDialog)
            !state.saving -> accept(ChatIntent.RequestExit)
        }
    }

    ChatContent(state = state, accept = accept)

    if (state.saveDialog) {
        SaveChatDialog(state = state, accept = accept)
    }
    if (state.taskEditorOpen) {
        TaskMemoryDialog(state = state, accept = accept)
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Чат") },
                windowInsets = WindowInsets(0, 0, 0, 0),
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
                actions = {
                    IconButton(
                        onClick = { accept(ChatIntent.OpenTaskEditor) },
                        enabled = !state.loading && !state.loadFailed && !state.saving,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Контекст задачи")
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
            top = contentPadding.calculateTopPadding(),
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding(),
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.82f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!isUser) {
                AssistantMarker()
                Spacer(Modifier.size(8.dp))
            }
            Surface(
                modifier = Modifier.widthIn(max = bubbleMaxWidth),
                color = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp,
                ),
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun AssistantMarker() {
    Surface(
        modifier = Modifier.size(24.dp),
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("AI", style = MaterialTheme.typography.labelSmall)
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        AssistantMarker()
        Spacer(Modifier.size(8.dp))
        Surface(
            modifier = Modifier.semantics { contentDescription = "Ассистент отвечает" },
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
        ) {
            Text(
                text = dots,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .clearAndSetSemantics { },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ChatInput(state: ChatState, enabled: Boolean, accept: (ChatIntent) -> Unit) {
    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = state.draft,
                onValueChange = { accept(ChatIntent.ChangeDraft(it)) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Поле сообщения" },
                enabled = enabled,
                placeholder = { Text("Сообщение") },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
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
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Отправить" },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    text = "↑",
                    modifier = Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.titleMedium,
                )
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

@Composable
private fun TaskMemoryDialog(state: ChatState, accept: (ChatIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { accept(ChatIntent.CloseTaskEditor) },
        title = { Text("Контекст задачи") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Этот контекст относится только к текущему чату и помогает ассистенту держать фокус.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTaskField(
                    value = state.taskDraft.goal,
                    onValueChange = { accept(ChatIntent.ChangeTaskGoal(it)) },
                    label = "Цель",
                    placeholder = "Что нужно получить?",
                )
                OutlinedTaskField(
                    value = state.taskDraft.constraints,
                    onValueChange = { accept(ChatIntent.ChangeTaskConstraints(it)) },
                    label = "Ограничения",
                    placeholder = "Одно ограничение на строку",
                )
                OutlinedTaskField(
                    value = state.taskDraft.desiredResult,
                    onValueChange = { accept(ChatIntent.ChangeTaskResult(it)) },
                    label = "Ожидаемый результат",
                    placeholder = "Как поймём, что задача готова?",
                )
                OutlinedTaskField(
                    value = state.taskDraft.decisions,
                    onValueChange = { accept(ChatIntent.ChangeTaskDecisions(it)) },
                    label = "Принятые решения",
                    placeholder = "Одно решение на строку",
                )
            }
        },
        confirmButton = {
            Button(onClick = { accept(ChatIntent.ApplyTaskMemory) }) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = { accept(ChatIntent.CloseTaskEditor) }) { Text("Отмена") }
        },
    )
}

@Composable
private fun OutlinedTaskField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = 2,
        maxLines = 4,
    )
}

private fun LazyListState.isAtBottom(): Boolean {
    val layout = layoutInfo
    val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= layout.totalItemsCount - 2
}
