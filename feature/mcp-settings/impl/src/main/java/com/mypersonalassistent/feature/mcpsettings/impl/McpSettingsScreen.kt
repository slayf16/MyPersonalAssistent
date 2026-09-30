package com.mypersonalassistent.feature.mcpsettings.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mypersonalassistent.feature.mcpsettings.api.McpEditorState
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsIntent
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsState
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun McpSettingsScreen(stateFlow: StateFlow<McpSettingsState>, accept: (McpSettingsIntent) -> Unit, onBack: () -> Unit) {
    val state by stateFlow.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Подключение MCP") },
            navigationIcon = { IconButton(onBack) { Text("←", modifier = Modifier.semantics { contentDescription = "Назад" }) } },
            actions = { TextButton({ accept(McpSettingsIntent.Add) }, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Добавить MCP" }) { Text("Добавить") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.errorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).semantics { contentDescription = "Ошибка MCP: $message" })
            }
            if (state.busy) CircularProgressIndicator(Modifier.padding(16.dp).semantics { contentDescription = "Операция MCP выполняется" })
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.servers, key = { it.id }) { server ->
                    ListItem(
                        headlineContent = { Text(server.name) },
                        supportingContent = { Text(server.endpoint) },
                        modifier = Modifier.semantics { contentDescription = "${server.name}, ${server.endpoint}" },
                        trailingContent = { Row {
                            TextButton({ accept(McpSettingsIntent.Edit(server.id)) }, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Изменить ${server.name}" }) { Text("Изменить") }
                            TextButton({ accept(McpSettingsIntent.RequestDelete(server.id)) }, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Удалить ${server.name}" }) { Text("Удалить") }
                        } },
                    )
                }
            }
        }
    }
    state.editor?.let { editor -> McpForm(editor, state.busy, accept) }
    state.deleting?.let { server ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) accept(McpSettingsIntent.CancelDelete) },
            title = { Text("Удалить MCP сервер?") },
            text = { Text("${server.name} и его разрешения для чатов будут удалены.") },
            confirmButton = { Button({ accept(McpSettingsIntent.ConfirmDelete) }, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Подтвердить удаление ${server.name}" }) { Text("Удалить") } },
            dismissButton = { TextButton({ accept(McpSettingsIntent.CancelDelete) }, enabled = !state.busy) { Text("Отмена") } },
        )
    }
}

@Composable private fun McpForm(editor: McpEditorState, busy: Boolean, accept: (McpSettingsIntent) -> Unit) {
    val passwordOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    AlertDialog(
        onDismissRequest = { if (!busy) accept(McpSettingsIntent.CloseEditor) },
        title = { Text(if (editor.id == null) "Новый MCP сервер" else "Изменить MCP сервер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(editor.name, { accept(McpSettingsIntent.ChangeName(it)) }, label = { Text("Название") }, enabled = !busy)
                OutlinedTextField(editor.endpoint, { accept(McpSettingsIntent.ChangeEndpoint(it)) }, label = { Text("HTTP endpoint") }, enabled = !busy)
                OutlinedTextField(editor.token, { accept(McpSettingsIntent.ChangeToken(it)) }, label = { Text(if (editor.id == null) "Token (необязательно)" else "Новый token (пусто — сохранить прежний)") }, enabled = !busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = passwordOptions, modifier = Modifier.semantics { contentDescription = "Секретный token MCP" })
                TextButton({ accept(McpSettingsIntent.ToggleClearToken) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Очистить token" }) { Text(if (editor.clearToken) "Token будет очищен" else "Очистить token") }
                OutlinedTextField(editor.apiKey, { accept(McpSettingsIntent.ChangeApiKey(it)) }, label = { Text(if (editor.id == null) "API key (необязательно)" else "Новый API key (пусто — сохранить прежний)") }, enabled = !busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = passwordOptions, modifier = Modifier.semantics { contentDescription = "Секретный API key MCP" })
                TextButton({ accept(McpSettingsIntent.ToggleClearApiKey) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Очистить API key" }) { Text(if (editor.clearApiKey) "API key будет очищен" else "Очистить API key") }
            }
        },
        confirmButton = { Button({ accept(McpSettingsIntent.Save) }, enabled = !busy && editor.name.isNotBlank() && editor.endpoint.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton({ accept(McpSettingsIntent.CloseEditor) }, enabled = !busy) { Text("Отмена") } },
    )
}
