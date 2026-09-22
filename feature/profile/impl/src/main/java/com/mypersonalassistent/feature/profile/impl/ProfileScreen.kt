package com.mypersonalassistent.feature.profile.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.mypersonalassistent.core.memory.api.ResponseDetail
import com.mypersonalassistent.core.memory.api.ResponseLanguage
import com.mypersonalassistent.core.memory.api.ResponseTone
import com.mypersonalassistent.feature.profile.api.ProfileIntent
import com.mypersonalassistent.feature.profile.api.ProfileState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ProfileScreen(stateFlow: StateFlow<ProfileState>, accept: (ProfileIntent) -> Unit) {
    val state by stateFlow.collectAsState()
    BackHandler(enabled = !state.firstRun) { accept(ProfileIntent.Back) }
    if (state.loading) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (state.firstRun) "Настроим ассистента" else "Профиль ассистента", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (state.firstRun) "Пара коротких настроек — и ответы будут ближе к вашему стилю. Этот шаг можно пропустить."
            else "Изменения применятся к следующему сообщению во всех чатах.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.preferredName,
            onValueChange = { accept(ProfileIntent.ChangeName(it)) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Как к вам обращаться" },
            enabled = !state.saving,
            label = { Text("Как к вам обращаться") },
            supportingText = { Text("Необязательно") },
            singleLine = true,
        )
        ChoiceGroup("Язык ответа", languageChoices, state.language, !state.saving) { accept(ProfileIntent.SelectLanguage(it)) }
        OtherValueInput(
            visible = state.language == ResponseLanguage.OTHER,
            value = state.customLanguage,
            error = state.languageError,
            label = "Свой язык ответа",
            enabled = !state.saving,
            onValueChange = { accept(ProfileIntent.ChangeCustomLanguage(it)) },
        )
        ChoiceGroup("Тон", toneChoices, state.tone, !state.saving) { accept(ProfileIntent.SelectTone(it)) }
        OtherValueInput(
            visible = state.tone == ResponseTone.OTHER,
            value = state.customTone,
            error = state.toneError,
            label = "Свой тон ответа",
            enabled = !state.saving,
            onValueChange = { accept(ProfileIntent.ChangeCustomTone(it)) },
        )
        ChoiceGroup("Подробность", detailChoices, state.detailLevel, !state.saving) { accept(ProfileIntent.SelectDetail(it)) }
        OtherValueInput(
            visible = state.detailLevel == ResponseDetail.OTHER,
            value = state.customDetailLevel,
            error = state.detailError,
            label = "Свой уровень подробности",
            enabled = !state.saving,
            onValueChange = { accept(ProfileIntent.ChangeCustomDetail(it)) },
        )
        OutlinedTextField(
            value = state.customInstructions,
            onValueChange = { accept(ProfileIntent.ChangeInstructions(it)) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Дополнительные предпочтения" },
            enabled = !state.saving,
            label = { Text("Дополнительные предпочтения") },
            placeholder = { Text("Например: сначала давай краткий вывод") },
            minLines = 3,
            maxLines = 5,
            supportingText = { Text("${state.customInstructions.length}/1000") },
        )
        Button(
            onClick = { accept(ProfileIntent.Save) },
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.saving) "Сохраняем…" else "Сохранить профиль") }
        if (state.firstRun) {
            TextButton(
                onClick = { accept(ProfileIntent.Skip) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Пропустить") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { accept(ProfileIntent.Back) }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("Назад") }
                TextButton(onClick = { accept(ProfileIntent.Clear) }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text("Очистить") }
            }
        }
    }
}

@Composable
private fun OtherValueInput(
    visible: Boolean,
    value: String,
    error: Boolean,
    label: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    if (!visible) return
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        enabled = enabled,
        isError = error,
        label = { Text(label) },
        supportingText = {
            Text(if (error) "Укажите значение" else "${value.length}/120")
        },
        singleLine = true,
    )
}

@Composable
private fun <T> ChoiceGroup(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            choices.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
    }
}

private val languageChoices = listOf(
    ResponseLanguage.AUTO to "Авто",
    ResponseLanguage.RUSSIAN to "Русский",
    ResponseLanguage.ENGLISH to "English",
    ResponseLanguage.OTHER to "Другое",
)
private val toneChoices = listOf(
    ResponseTone.FRIENDLY to "Дружелюбно",
    ResponseTone.NEUTRAL to "Нейтрально",
    ResponseTone.FORMAL to "Формально",
    ResponseTone.OTHER to "Другое",
)
private val detailChoices = listOf(
    ResponseDetail.CONCISE to "Кратко",
    ResponseDetail.BALANCED to "Баланс",
    ResponseDetail.DETAILED to "Подробно",
    ResponseDetail.OTHER to "Другое",
)
