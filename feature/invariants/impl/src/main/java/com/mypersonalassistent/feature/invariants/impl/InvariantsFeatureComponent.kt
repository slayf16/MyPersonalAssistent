package com.mypersonalassistent.feature.invariants.impl

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labelsChannel
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.feature.invariants.api.InvariantsEffect
import com.mypersonalassistent.feature.invariants.api.InvariantEditorDraft
import com.mypersonalassistent.feature.invariants.api.InvariantsIntent
import com.mypersonalassistent.feature.invariants.api.InvariantsState
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/** Retained Decompose owner for the invariant list and editor; it does not own navigation. */
class InvariantsFeatureComponent(
    componentContext: ComponentContext,
    repository: InvariantRepository,
) : ComponentContext by componentContext {
    private data class Restore(val draft: InvariantEditorDraft?, val failed: Boolean)
    private val restored = runCatching {
        Restore(stateKeeper.consume("invariant-editor-draft", InvariantEditorDraft.serializer()), false)
    }.getOrElse { Restore(null, true) }
    private var created = false
    private val retained = instanceKeeper.getOrCreate {
        created = true
        Retained(InvariantsStoreFactory(DefaultStoreFactory(), repository).create())
    }
    val state: StateFlow<InvariantsState> = retained.store.stateFlow(lifecycle)
    val effects: ReceiveChannel<InvariantsEffect> = retained.store.labelsChannel(lifecycle)

    init {
        if (created && (restored.draft != null || restored.failed)) {
            retained.store.accept(InvariantsIntent.RestoreEditorDraft(restored.draft, restored.failed))
        }
        stateKeeper.register("invariant-editor-draft", InvariantEditorDraft.serializer()) {
            retained.store.state.editor?.toPersistedDraft()
        }
        retained.store.accept(InvariantsIntent.Load)
    }

    fun accept(intent: InvariantsIntent) = retained.store.accept(intent)

    private class Retained(
        val store: Store<InvariantsIntent, InvariantsState, InvariantsEffect>,
    ) : InstanceKeeper.Instance {
        override fun onDestroy() = store.dispose()
    }
}
