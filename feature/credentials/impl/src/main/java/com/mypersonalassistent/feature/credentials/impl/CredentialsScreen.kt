package com.mypersonalassistent.feature.credentials.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.mypersonalassistent.feature.credentials.api.CredentialsIntent
import com.mypersonalassistent.feature.credentials.api.CredentialsState
import kotlinx.coroutines.flow.StateFlow

@Composable fun CredentialsScreen(stateFlow: StateFlow<CredentialsState>, accept: (CredentialsIntent) -> Unit) {
    val state by stateFlow.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ключ DeepSeek API", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ключ хранится в защищённом хранилище устройства.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.value,
            onValueChange = { accept(CredentialsIntent.Change(it)) },
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API-ключ") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Button(
            enabled = state.value.isNotBlank() && !state.isSaving,
            onClick = { accept(CredentialsIntent.Save) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Сохранить") }
    }
}
