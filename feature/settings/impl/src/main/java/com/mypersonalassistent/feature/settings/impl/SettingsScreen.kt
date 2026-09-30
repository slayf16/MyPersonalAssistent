package com.mypersonalassistent.feature.settings.impl
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mypersonalassistent.feature.settings.api.SettingsIntent
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen(accept: (SettingsIntent) -> Unit) { Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }, navigationIcon = { IconButton({ accept(SettingsIntent.Back) }) { Text("←", modifier = Modifier.semantics { contentDescription = "Назад" }) } }) }) { p -> Column(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Setting("Инварианты") { accept(SettingsIntent.OpenInvariants) }; Setting("Профиль") { accept(SettingsIntent.OpenProfile) }; Setting("MCP") { accept(SettingsIntent.OpenMcp) } } } }
@Composable private fun Setting(title: String, click: () -> Unit) { ListItem(headlineContent = { Text(title) }, modifier = Modifier.fillMaxWidth().clickable(onClick = click).semantics { contentDescription = title }) }
