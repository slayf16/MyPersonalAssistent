package com.mypersonalassistent.feature.home.impl

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.feature.home.api.HomeEffect
import com.mypersonalassistent.feature.home.api.HomeIntent
import com.mypersonalassistent.feature.home.api.HomeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal interface HomeStore : Store<HomeIntent, HomeState, HomeEffect>

internal class HomeStoreFactory(
    private val factory: StoreFactory,
    private val history: HistoryRepository,
) {
    fun create(): HomeStore = object : HomeStore,
        Store<HomeIntent, HomeState, HomeEffect> by factory.create(
            name = "HomeStore",
            initialState = HomeState(),
            bootstrapper = null,
            executorFactory = ::Executor,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Message {
        data class State(val value: HomeState) : Message
    }

    private inner class Executor : CoroutineExecutor<HomeIntent, Nothing, HomeState, Message, HomeEffect>() {
        private var observing: Job? = null

        override fun executeIntent(intent: HomeIntent) {
            when (intent) {
                HomeIntent.Retry -> observe()
                HomeIntent.NewChat -> publish(HomeEffect.NewChat)
                HomeIntent.EditKey -> publish(HomeEffect.EditKey)
                is HomeIntent.Open -> publish(HomeEffect.Open(intent.id))
            }
        }

        private fun observe() {
            observing?.cancel()
            dispatch(Message.State(HomeState(isLoading = true)))
            observing = scope.launch {
                try {
                    history.observeSummaries().collect { summaries ->
                        dispatch(Message.State(HomeState(isLoading = false, chats = summaries)))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    dispatch(Message.State(HomeState(isLoading = false, error = true)))
                    publish(HomeEffect.TechnicalError)
                }
            }
        }
    }

    private object ReducerImpl : Reducer<HomeState, Message> {
        override fun HomeState.reduce(msg: Message): HomeState = when (msg) {
            is Message.State -> msg.value
        }
    }
}
