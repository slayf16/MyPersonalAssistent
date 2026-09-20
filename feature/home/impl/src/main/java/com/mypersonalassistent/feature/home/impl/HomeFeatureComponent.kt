package com.mypersonalassistent.feature.home.impl
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.labelsChannel
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.feature.home.api.HomeEffect
import com.mypersonalassistent.feature.home.api.HomeIntent
import com.mypersonalassistent.feature.home.api.HomeState
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
class HomeFeatureComponent(componentContext: ComponentContext, history: HistoryRepository) : ComponentContext by componentContext { private val retained = instanceKeeper.getOrCreate { Retained(HomeStoreFactory(DefaultStoreFactory(), history).create()) }; val state: StateFlow<HomeState> = retained.store.stateFlow(lifecycle); val effects: ReceiveChannel<HomeEffect> = retained.store.labelsChannel(lifecycle); init { retained.store.accept(HomeIntent.Retry) }; fun accept(intent: HomeIntent) = retained.store.accept(intent); private class Retained(val store: Store<HomeIntent, HomeState, HomeEffect>) : InstanceKeeper.Instance { override fun onDestroy() = store.dispose() } }
