package com.mypersonalassistent.feature.mcpsettings.api

import com.mypersonalassistent.core.mcp.api.McpServer

data class McpEditorState(
    val id: String? = null,
    val name: String = "",
    val endpoint: String = "",
    val token: String = "",
    val apiKey: String = "",
    val clearToken: Boolean = false,
    val clearApiKey: Boolean = false,
)

data class McpSettingsState(
    val servers: List<McpServer> = emptyList(),
    val editor: McpEditorState? = null,
    val deleting: McpServer? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface McpSettingsIntent {
    data object Load : McpSettingsIntent
    data object Add : McpSettingsIntent
    data class Edit(val id: String) : McpSettingsIntent
    data object CloseEditor : McpSettingsIntent
    data class ChangeName(val value: String) : McpSettingsIntent
    data class ChangeEndpoint(val value: String) : McpSettingsIntent
    data class ChangeToken(val value: String) : McpSettingsIntent
    data class ChangeApiKey(val value: String) : McpSettingsIntent
    data object ToggleClearToken : McpSettingsIntent
    data object ToggleClearApiKey : McpSettingsIntent
    data object Save : McpSettingsIntent
    data class RequestDelete(val id: String) : McpSettingsIntent
    data object CancelDelete : McpSettingsIntent
    data object ConfirmDelete : McpSettingsIntent
    data object DismissError : McpSettingsIntent
}
