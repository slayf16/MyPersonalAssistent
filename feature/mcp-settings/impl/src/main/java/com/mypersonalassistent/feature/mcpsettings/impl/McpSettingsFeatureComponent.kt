package com.mypersonalassistent.feature.mcpsettings.impl

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.mcp.api.McpCatalogRepository
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsIntent
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsState
import kotlinx.coroutines.flow.StateFlow

class McpSettingsFeatureComponent(componentContext: ComponentContext, catalog: McpCatalogRepository) : ComponentContext by componentContext {
    private val retained = instanceKeeper.getOrCreate { Retained(McpSettingsStoreFactory(DefaultStoreFactory(), catalog).create()) }
    val state: StateFlow<McpSettingsState> = retained.store.stateFlow(lifecycle)
    init { retained.store.accept(McpSettingsIntent.Load) }
    fun accept(intent: McpSettingsIntent) = retained.store.accept(intent)
    private class Retained(val store: Store<McpSettingsIntent, McpSettingsState, Nothing>) : InstanceKeeper.Instance { override fun onDestroy() = store.dispose() }
}
