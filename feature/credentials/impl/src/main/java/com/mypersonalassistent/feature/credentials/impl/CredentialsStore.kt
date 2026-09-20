package com.mypersonalassistent.feature.credentials.impl

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.core.credentials.api.CredentialWriteResult
import com.mypersonalassistent.feature.credentials.api.CredentialsEffect
import com.mypersonalassistent.feature.credentials.api.CredentialsIntent
import com.mypersonalassistent.feature.credentials.api.CredentialsState
import kotlinx.coroutines.launch

internal interface CredentialsStore : Store<CredentialsIntent, CredentialsState, CredentialsEffect>

internal class CredentialsStoreFactory(
    private val factory: StoreFactory,
    private val repository: CredentialRepository,
) {
    fun create(): CredentialsStore = object : CredentialsStore,
        Store<CredentialsIntent, CredentialsState, CredentialsEffect> by factory.create(
            name = "CredentialsStore",
            initialState = CredentialsState(),
            bootstrapper = null,
            executorFactory = ::Executor,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Message {
        data class State(val value: CredentialsState) : Message
    }

    private inner class Executor : CoroutineExecutor<CredentialsIntent, Nothing, CredentialsState, Message, CredentialsEffect>() {
        override fun executeIntent(intent: CredentialsIntent) {
            when (intent) {
                CredentialsIntent.Load -> scope.launch {
                    if (repository.readApiKey() != null) publish(CredentialsEffect.ExistingKey)
                }
                is CredentialsIntent.Change -> if (!state().isSaving) {
                    dispatch(Message.State(state().copy(value = intent.value)))
                }
                CredentialsIntent.Save -> if (!state().isSaving && state().value.isNotBlank()) {
                    dispatch(Message.State(state().copy(isSaving = true)))
                    scope.launch {
                        when (repository.saveApiKey(state().value)) {
                            CredentialWriteResult.Success -> publish(CredentialsEffect.Saved)
                            CredentialWriteResult.Failure -> {
                                dispatch(Message.State(state().copy(isSaving = false)))
                                publish(CredentialsEffect.TechnicalError)
                            }
                        }
                    }
                }
            }
        }
    }

    private object ReducerImpl : Reducer<CredentialsState, Message> {
        override fun CredentialsState.reduce(msg: Message): CredentialsState = when (msg) {
            is Message.State -> msg.value
        }
    }
}
