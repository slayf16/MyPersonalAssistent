package com.mypersonalassistent.feature.chat.impl

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.labelsChannel
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.feature.chat.api.ChatEffect
import com.mypersonalassistent.feature.chat.api.ChatIntent
import com.mypersonalassistent.feature.chat.api.ChatState
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle owner for the feature Store. InstanceKeeper preserves it across configuration changes. */
class ChatFeatureComponent(componentContext: ComponentContext, id: String, history: HistoryRepository, llm: Llm) : ComponentContext by componentContext {
    private val retained = instanceKeeper.getOrCreate { RetainedStore(ChatStoreFactory(DefaultStoreFactory(), id, history, llm).create()) }
    val state: StateFlow<ChatState> = retained.store.stateFlow(lifecycle)
    val effects: ReceiveChannel<ChatEffect> = retained.store.labelsChannel(lifecycle)
    init { retained.store.accept(ChatIntent.Load) }
    fun accept(intent: ChatIntent) = retained.store.accept(intent)
    private class RetainedStore(val store: Store<ChatIntent, ChatState, ChatEffect>) : InstanceKeeper.Instance { override fun onDestroy() = store.dispose() }
}
