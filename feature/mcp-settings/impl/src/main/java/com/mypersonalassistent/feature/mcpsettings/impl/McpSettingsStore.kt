package com.mypersonalassistent.feature.mcpsettings.impl

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.mypersonalassistent.core.mcp.api.McpCatalogRepository
import com.mypersonalassistent.core.mcp.api.McpResult
import com.mypersonalassistent.core.mcp.api.McpServerDraft
import com.mypersonalassistent.feature.mcpsettings.api.McpEditorState
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsIntent
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal interface McpSettingsStore : Store<McpSettingsIntent, McpSettingsState, Nothing>

internal class McpSettingsStoreFactory(
    private val storeFactory: StoreFactory,
    private val catalog: McpCatalogRepository,
) {
    fun create(): McpSettingsStore = object : McpSettingsStore,
        Store<McpSettingsIntent, McpSettingsState, Nothing> by storeFactory.create(
            name = "McpSettingsStore",
            initialState = McpSettingsState(),
            bootstrapper = null,
            executorFactory = ::Executor,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Message { data class State(val state: McpSettingsState) : Message }

    private inner class Executor : CoroutineExecutor<McpSettingsIntent, Nothing, McpSettingsState, Message, Nothing>() {
        init {
            scope.launch {
                catalog.observe().collect { servers -> set { copy(servers = servers) } }
            }
        }

        override fun executeIntent(intent: McpSettingsIntent) {
            when (intent) {
                McpSettingsIntent.Load -> Unit
                McpSettingsIntent.Add -> if (!state().busy) set { copy(editor = McpEditorState(), errorMessage = null) }
                is McpSettingsIntent.Edit -> if (!state().busy) state().servers.firstOrNull { it.id == intent.id }?.let { server -> set { copy(editor = McpEditorState(server.id, server.name, server.endpoint), errorMessage = null) } }
                McpSettingsIntent.CloseEditor -> if (!state().busy) set { copy(editor = null) }
                is McpSettingsIntent.ChangeName -> edit { copy(name = intent.value.take(80)) }
                is McpSettingsIntent.ChangeEndpoint -> edit { copy(endpoint = intent.value.take(2048)) }
                is McpSettingsIntent.ChangeToken -> edit { copy(token = intent.value.take(4096), clearToken = false) }
                is McpSettingsIntent.ChangeApiKey -> edit { copy(apiKey = intent.value.take(4096), clearApiKey = false) }
                McpSettingsIntent.ToggleClearToken -> edit { copy(clearToken = !clearToken, token = "") }
                McpSettingsIntent.ToggleClearApiKey -> edit { copy(clearApiKey = !clearApiKey, apiKey = "") }
                McpSettingsIntent.Save -> save()
                is McpSettingsIntent.RequestDelete -> if (!state().busy) state().servers.firstOrNull { it.id == intent.id }?.let { server -> set { copy(deleting = server, errorMessage = null) } }
                McpSettingsIntent.CancelDelete -> if (!state().busy) set { copy(deleting = null) }
                McpSettingsIntent.ConfirmDelete -> delete()
                McpSettingsIntent.DismissError -> set { copy(errorMessage = null) }
            }
        }

        private fun edit(transform: McpEditorState.() -> McpEditorState) {
            if (!state().busy) state().editor?.let { current -> set { copy(editor = current.transform(), errorMessage = null) } }
        }

        private fun save() {
            val editor = state().editor ?: return
            if (state().busy || editor.name.isBlank() || editor.endpoint.isBlank()) return
            set { copy(busy = true, errorMessage = null) }
            scope.launch {
                try {
                    val result = catalog.save(McpServerDraft(
                        id = editor.id,
                        name = editor.name,
                        endpoint = editor.endpoint,
                        token = editor.token.takeIf(String::isNotBlank),
                        apiKey = editor.apiKey.takeIf(String::isNotBlank),
                        clearToken = editor.clearToken,
                        clearApiKey = editor.clearApiKey,
                    ))
                    if (result is McpResult.Success) set { copy(busy = false, editor = null) }
                    else set { copy(busy = false, errorMessage = "Не удалось сохранить MCP сервер. Проверьте адрес и повторите.") }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Throwable) { set { copy(busy = false, errorMessage = "Не удалось сохранить MCP сервер. Повторите.") } }
            }
        }

        private fun delete() {
            val server = state().deleting ?: return
            if (state().busy) return
            set { copy(busy = true, errorMessage = null) }
            scope.launch {
                try {
                    if (catalog.delete(server.id) is McpResult.Success) set { copy(busy = false, deleting = null) }
                    else set { copy(busy = false, deleting = null, errorMessage = "Не удалось полностью удалить MCP сервер. Повторите.") }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Throwable) { set { copy(busy = false, errorMessage = "Не удалось удалить MCP сервер. Повторите.") } }
            }
        }

        private fun set(transform: McpSettingsState.() -> McpSettingsState) = dispatch(Message.State(state().transform()))
    }

    private object ReducerImpl : Reducer<McpSettingsState, Message> {
        override fun McpSettingsState.reduce(msg: Message) = when (msg) { is Message.State -> msg.state }
    }
}
