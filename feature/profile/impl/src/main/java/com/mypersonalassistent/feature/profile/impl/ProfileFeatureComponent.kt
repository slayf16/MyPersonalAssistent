package com.mypersonalassistent.feature.profile.impl

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.labelsChannel
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.feature.profile.api.ProfileEffect
import com.mypersonalassistent.feature.profile.api.ProfileIntent
import com.mypersonalassistent.feature.profile.api.ProfileState
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

class ProfileFeatureComponent(
    componentContext: ComponentContext,
    repository: MemoryRepository,
    firstRun: Boolean,
) : ComponentContext by componentContext {
    private val retained = instanceKeeper.getOrCreate {
        Retained(ProfileStoreFactory(DefaultStoreFactory(), repository, firstRun).create())
    }
    val state: StateFlow<ProfileState> = retained.store.stateFlow(lifecycle)
    val effects: ReceiveChannel<ProfileEffect> = retained.store.labelsChannel(lifecycle)
    init { retained.store.accept(ProfileIntent.Load) }
    fun accept(intent: ProfileIntent) = retained.store.accept(intent)
    private class Retained(val store: Store<ProfileIntent, ProfileState, ProfileEffect>) : InstanceKeeper.Instance {
        override fun onDestroy() = store.dispose()
    }
}
