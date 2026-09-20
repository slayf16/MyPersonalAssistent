package com.mypersonalassistent.feature.credentials.impl
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.labelsChannel
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.feature.credentials.api.CredentialsEffect
import com.mypersonalassistent.feature.credentials.api.CredentialsIntent
import com.mypersonalassistent.feature.credentials.api.CredentialsState
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
class CredentialsFeatureComponent(componentContext: ComponentContext, repository: CredentialRepository, checkExisting: Boolean) : ComponentContext by componentContext { private val retained = instanceKeeper.getOrCreate { Retained(CredentialsStoreFactory(DefaultStoreFactory(), repository).create()) }; val state: StateFlow<CredentialsState> = retained.store.stateFlow(lifecycle); val effects: ReceiveChannel<CredentialsEffect> = retained.store.labelsChannel(lifecycle); init { if (checkExisting) retained.store.accept(CredentialsIntent.Load) }; fun accept(intent: CredentialsIntent) = retained.store.accept(intent); private class Retained(val store: Store<CredentialsIntent, CredentialsState, CredentialsEffect>) : InstanceKeeper.Instance { override fun onDestroy() = store.dispose() } }
