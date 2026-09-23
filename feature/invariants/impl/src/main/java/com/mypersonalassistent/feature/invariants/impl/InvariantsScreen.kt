package com.mypersonalassistent.feature.invariants.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mypersonalassistent.core.invariants.api.InvariantCategory
import com.mypersonalassistent.core.invariants.api.InvariantRule
import com.mypersonalassistent.feature.invariants.api.InvariantEditorState
import com.mypersonalassistent.feature.invariants.api.InvariantUiMessage
import com.mypersonalassistent.feature.invariants.api.InvariantsIntent
import com.mypersonalassistent.feature.invariants.api.InvariantsState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun InvariantsScreen(stateFlow: StateFlow<InvariantsState>, accept: (InvariantsIntent) -> Unit) {
    val state by stateFlow.collectAsState()
    BackHandler { accept(InvariantsIntent.RequestBack) }
    if (state.editor == null) InvariantsList(state, accept) else InvariantEditor(state, requireNotNull(state.editor), accept)
    state.pendingConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { if (!state.mutationInFlight) accept(InvariantsIntent.CancelMutation) },
            title = { Text(if (confirmation.isDelete) "Удалить инвариант?" else "Подтвердите изменение") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (confirmation.isDelete) Text("Инвариант «${confirmation.ruleTitle.orEmpty()}» будет удалён.")
                    Text("Изменение правил потребует заново построить и утвердить план для ${confirmation.affectedNonterminalRuns} задач")
                }
            },
            confirmButton = { Button(onClick = { accept(InvariantsIntent.ConfirmMutation) }, enabled = !state.mutationInFlight) { Text("Подтвердить") } },
            dismissButton = { TextButton(onClick = { accept(InvariantsIntent.CancelMutation) }, enabled = !state.mutationInFlight) { Text("Отмена") } },
        )
    }
    if (state.dirtyExitConfirmation) {
        AlertDialog(
            onDismissRequest = { accept(InvariantsIntent.CancelDirtyExit) },
            title = { Text("Сохранить изменения?") },
            text = { Text("Несохранённые изменения будут потеряны только после выбора «Не сохранять».") },
            confirmButton = { Button(onClick = { accept(InvariantsIntent.SaveDirtyExit) }, enabled = !state.mutationInFlight) { Text("Сохранить") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { accept(InvariantsIntent.DiscardDirtyExit) }, enabled = !state.mutationInFlight) { Text("Не сохранять") }
                    TextButton(onClick = { accept(InvariantsIntent.CancelDirtyExit) }, enabled = !state.mutationInFlight) { Text("Отмена") }
                }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InvariantsList(state: InvariantsState, accept: (InvariantsIntent) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Инварианты") },
                navigationIcon = { IconButton(onClick = { accept(InvariantsIntent.RequestBack) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (state.controlsEnabled) accept(InvariantsIntent.Add) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Добавить инвариант") },
                modifier = Modifier.semantics { contentDescription = "Добавить инвариант" },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            state.loadError -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Не удалось загрузить инварианты")
                TextButton(onClick = { accept(InvariantsIntent.Retry) }) { Text("Повторить") }
            }
            state.rules.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp).padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Инвариантов пока нет. Ассистент следует базовым правилам безопасности приложения, но у него нет ваших обязательных правил.")
                TextButton(onClick = { accept(InvariantsIntent.Add) }) { Text("Добавить инвариант") }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.message?.let { message ->
                    item(key = "invariants-message") {
                        Text(
                            listOfNotNull(message.text(), state.messageDetail).joinToString(": "),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(state.rules, key = { it.id.value }) { rule -> RuleCard(rule, state.controlsEnabled, accept) }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: InvariantRule, enabled: Boolean, accept: (InvariantsIntent) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.title, style = MaterialTheme.typography.titleMedium)
                    Text(rule.category.label(), style = MaterialTheme.typography.labelMedium)
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { accept(InvariantsIntent.Toggle(rule.id, it)) },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "${if (rule.enabled) "Выключить" else "Включить"} инвариант ${rule.title}" },
                )
            }
            Text(rule.statement)
            Text(if (rule.enabled) "Включён" else "Выключен", modifier = Modifier.semantics { contentDescription = "Статус: ${if (rule.enabled) "Включён" else "Выключен"}" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { accept(InvariantsIntent.Edit(rule.id)) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Изменить инвариант ${rule.title}" }) { Text("Изменить") }
                TextButton(onClick = { accept(InvariantsIntent.Delete(rule.id)) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Удалить инвариант ${rule.title}" }) { Text("Удалить") }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InvariantEditor(state: InvariantsState, editor: InvariantEditorState, accept: (InvariantsIntent) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (editor.isEdit) "Изменить инвариант" else "Добавить инвариант") },
            navigationIcon = { IconButton(onClick = { accept(InvariantsIntent.RequestBack) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.draftRestoreWarning) Text("Черновик формы не удалось восстановить. Проверьте поля перед сохранением.", color = MaterialTheme.colorScheme.error)
            Text("Категория", style = MaterialTheme.typography.labelLarge)
            CategoryPicker(editor.category, state.controlsEnabled, accept)
            OutlinedTextField(
                value = editor.title,
                onValueChange = { accept(InvariantsIntent.ChangeTitle(it)) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Название инварианта" },
                enabled = state.controlsEnabled,
                label = { Text("Название") },
                supportingText = { Text("${editor.title.codePointCount(0, editor.title.length)}/80${editor.validation.titleError?.let { ". ${it.text()}" }.orEmpty()}") },
                isError = editor.validation.titleError != null,
                singleLine = true,
            )
            OutlinedTextField(
                value = editor.statement,
                onValueChange = { accept(InvariantsIntent.ChangeStatement(it)) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Текст инварианта" },
                enabled = state.controlsEnabled,
                label = { Text("Правило") },
                supportingText = { Text("${editor.statement.codePointCount(0, editor.statement.length)}/1000${editor.validation.statementError?.let { ". ${it.text()}" }.orEmpty()}") },
                isError = editor.validation.statementError != null,
                minLines = 5,
            )
            state.message?.let { message -> Text(listOfNotNull(message.text(), state.messageDetail).joinToString(": "), color = MaterialTheme.colorScheme.error) }
            if (editor.reloadRequired) TextButton(onClick = { accept(InvariantsIntent.ReloadAndReapply) }, enabled = !state.mutationInFlight) { Text("Обновить и применить снова") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { accept(InvariantsIntent.Save) }, enabled = state.controlsEnabled && editor.validation.valid) { Text("Сохранить") }
                TextButton(onClick = { accept(InvariantsIntent.RequestBack) }, enabled = !state.mutationInFlight) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun CategoryPicker(selected: InvariantCategory, enabled: Boolean, accept: (InvariantsIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InvariantCategory.entries.forEach { category ->
            AssistChip(
                onClick = { accept(InvariantsIntent.ChangeCategory(category)) },
                enabled = enabled,
                label = { Text(category.label()) },
                modifier = Modifier.semantics { contentDescription = "Категория ${category.label()}${if (selected == category) ", выбрана" else ""}" },
            )
        }
    }
}

private fun InvariantCategory.label(): String = when (this) {
    InvariantCategory.ARCHITECTURE -> "Архитектура"
    InvariantCategory.TECHNICAL_DECISION -> "Техническое решение"
    InvariantCategory.STACK_CONSTRAINT -> "Ограничение стека"
    InvariantCategory.BUSINESS_RULE -> "Бизнес-правило"
}

private fun InvariantUiMessage.text(): String = when (this) {
    InvariantUiMessage.TITLE_REQUIRED -> "Введите название"
    InvariantUiMessage.TITLE_TOO_LONG -> "Название не длиннее 80 символов"
    InvariantUiMessage.STATEMENT_REQUIRED -> "Введите правило"
    InvariantUiMessage.STATEMENT_TOO_LONG -> "Правило не длиннее 1000 символов"
    InvariantUiMessage.INVALID_CHARACTERS -> "Поле содержит недопустимые символы"
    InvariantUiMessage.DUPLICATE -> "Такой инвариант уже существует"
    InvariantUiMessage.TOTAL_LIMIT -> "Достигнут лимит в 100 инвариантов"
    InvariantUiMessage.ENABLED_LIMIT -> "Можно включить не более 20 инвариантов"
    InvariantUiMessage.ACTIVE_TEXT_LIMIT -> "Превышен лимит текста включённых инвариантов"
    InvariantUiMessage.STALE -> "Данные изменились. Обновите и примените изменения снова"
    InvariantUiMessage.NOT_FOUND -> "Инвариант больше не существует"
    InvariantUiMessage.STORAGE_UNAVAILABLE -> "Не удалось сохранить инвариант"
    InvariantUiMessage.INVALID_RULE -> "Инвариант не прошёл проверку"
}
