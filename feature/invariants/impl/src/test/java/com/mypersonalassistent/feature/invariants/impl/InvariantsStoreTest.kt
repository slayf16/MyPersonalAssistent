package com.mypersonalassistent.feature.invariants.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.invariants.api.CollectionRevision
import com.mypersonalassistent.core.invariants.api.CreateInvariantCommand
import com.mypersonalassistent.core.invariants.api.EditInvariantCommand
import com.mypersonalassistent.core.invariants.api.ConfirmMutationResult
import com.mypersonalassistent.core.invariants.api.InvariantChange
import com.mypersonalassistent.core.invariants.api.InvariantCategory
import com.mypersonalassistent.core.invariants.api.InvariantMutation
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.core.invariants.api.InvariantRule
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotRef
import com.mypersonalassistent.core.invariants.api.InvariantValidationError
import com.mypersonalassistent.core.invariants.api.MutationImpact
import com.mypersonalassistent.core.invariants.api.PrepareMutationResult
import com.mypersonalassistent.core.invariants.api.RuleRevision
import com.mypersonalassistent.core.invariants.api.SnapshotResult
import com.mypersonalassistent.feature.invariants.api.InvariantUiMessage
import com.mypersonalassistent.feature.invariants.api.InvariantEditorDraft
import com.mypersonalassistent.feature.invariants.api.InvariantsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvariantsStoreTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `create waits for impact confirmation and commits only once`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val store = create(repository)
        store.accept(InvariantsIntent.Load); testScheduler.advanceUntilIdle()
        store.accept(InvariantsIntent.Add)
        store.accept(InvariantsIntent.ChangeTitle("Архитектура"))
        store.accept(InvariantsIntent.ChangeStatement("Использовать Kotlin"))
        store.accept(InvariantsIntent.Save); testScheduler.advanceUntilIdle()

        assertEquals(1, repository.prepared.size)
        assertEquals(0, repository.confirmed.size)
        assertEquals(3, store.state.pendingConfirmation?.affectedNonterminalRuns)

        store.accept(InvariantsIntent.ConfirmMutation)
        store.accept(InvariantsIntent.ConfirmMutation)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("confirm-1"), repository.confirmed)
        assertNull(store.state.pendingConfirmation)
        assertFalse(store.state.mutationInFlight)
        store.dispose()
    }

    @Test fun `stale editor preserves typed input and requires explicit reload`() = runTest(dispatcher) {
        val original = rule(title = "Старое", revision = 4)
        val repository = FakeRepository(rules = listOf(original), prepareResult = PrepareMutationResult.Rejected(InvariantValidationError.Stale(CollectionRevision(7), RuleRevision(5))))
        val store = create(repository)
        store.accept(InvariantsIntent.Load); testScheduler.advanceUntilIdle()
        store.accept(InvariantsIntent.Edit(original.id))
        store.accept(InvariantsIntent.ChangeTitle("Новое"))
        store.accept(InvariantsIntent.Save); testScheduler.advanceUntilIdle()

        assertEquals("Новое", store.state.editor?.title)
        assertTrue(store.state.editor?.reloadRequired == true)
        assertEquals(InvariantUiMessage.STALE, store.state.message)
        assertEquals(0, repository.confirmed.size)
        store.dispose()
    }

    @Test fun `disabled edit is submitted through explicit edit command without changing enabled`() = runTest(dispatcher) {
        val disabled = rule(title = "Выключено", revision = 4).copy(enabled = false)
        val repository = FakeRepository(rules = listOf(disabled))
        val store = create(repository)
        store.accept(InvariantsIntent.Load); testScheduler.advanceUntilIdle()
        store.accept(InvariantsIntent.Edit(disabled.id))
        store.accept(InvariantsIntent.ChangeTitle("Новое имя"))
        store.accept(InvariantsIntent.Save); testScheduler.advanceUntilIdle()

        val command = requireNotNull(repository.editCommand)
        assertEquals(disabled.id, command.ruleId)
        assertEquals(disabled.revision, command.expectedRuleRevision)
        assertEquals("Новое имя", command.draft.title)
        assertFalse(disabled.enabled)
        store.dispose()
    }

    @Test fun `oversize restored draft becomes warned empty editor without repository mutation`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val store = create(repository)
        store.accept(InvariantsIntent.RestoreEditorDraft(InvariantEditorDraft(title = "x".repeat(81)), restoreFailed = false))
        assertTrue(store.state.draftRestoreWarning)
        assertEquals("", store.state.editor?.title)
        assertTrue(repository.prepared.isEmpty())
        store.dispose()
    }

    @Test fun `local invalid form blocks repository prepare`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val store = create(repository)
        store.accept(InvariantsIntent.Load); testScheduler.advanceUntilIdle()
        store.accept(InvariantsIntent.Add)
        store.accept(InvariantsIntent.Save); testScheduler.advanceUntilIdle()

        assertTrue(repository.prepared.isEmpty())
        assertEquals(InvariantUiMessage.TITLE_REQUIRED, store.state.editor?.validation?.titleError)
        assertEquals(InvariantUiMessage.STATEMENT_REQUIRED, store.state.editor?.validation?.statementError)
        store.dispose()
    }

    @Test fun `discarding dirty editor never prepares a mutation`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val store = create(repository)
        store.accept(InvariantsIntent.Load); testScheduler.advanceUntilIdle()
        store.accept(InvariantsIntent.Add)
        store.accept(InvariantsIntent.ChangeTitle("Не сохранять"))
        store.accept(InvariantsIntent.RequestBack)
        assertTrue(store.state.dirtyExitConfirmation)
        store.accept(InvariantsIntent.DiscardDirtyExit)

        assertNull(store.state.editor)
        assertTrue(repository.prepared.isEmpty())
        store.dispose()
    }

    private fun create(repository: FakeRepository) = InvariantsStoreFactory(DefaultStoreFactory(), repository).create()

    private class FakeRepository(
        rules: List<InvariantRule> = emptyList(),
        private val prepareResult: PrepareMutationResult? = null,
    ) : InvariantRepository {
        private val source = MutableStateFlow(rules)
        override val rules: Flow<List<InvariantRule>> = source
        override val changes = MutableSharedFlow<InvariantChange>()
        val prepared = mutableListOf<InvariantMutation>()
        val confirmed = mutableListOf<String>()
        var createCommand: CreateInvariantCommand? = null
        var editCommand: EditInvariantCommand? = null
        override suspend fun collectionRevision() = CollectionRevision(2)
        override suspend fun read(id: InvariantRuleId) = source.value.firstOrNull { it.id == id }
        override suspend fun prepareMutation(mutation: InvariantMutation): PrepareMutationResult {
            prepared += mutation
            return prepareResult ?: PrepareMutationResult.Ready(MutationImpact(mutation, 3, "confirm-1"))
        }
        override suspend fun prepareCreate(command: CreateInvariantCommand): PrepareMutationResult {
            createCommand = command
            return prepareMutation(InvariantMutation.Create(command.draft, command.expectedCollectionRevision))
        }
        override suspend fun prepareEdit(command: EditInvariantCommand): PrepareMutationResult {
            editCommand = command
            return prepareMutation(InvariantMutation.Edit(command.ruleId, command.draft, command.expectedRuleRevision, command.expectedCollectionRevision))
        }
        override suspend fun confirmMutation(confirmationId: String): ConfirmMutationResult {
            confirmed += confirmationId
            return ConfirmMutationResult.Committed(null, CollectionRevision(3), emptySet())
        }
        override suspend fun createSnapshot() = SnapshotResult.Unavailable
        override suspend fun readSnapshot(ref: InvariantSnapshotRef) = SnapshotResult.Unavailable
        override suspend fun trackRun(
            chatId: String,
            runId: String,
            snapshotRef: InvariantSnapshotRef,
            isNonterminal: Boolean,
            isActive: Boolean,
            isStale: Boolean,
            staleTarget: String?,
        ) = true
    }

    private companion object {
        fun rule(title: String, revision: Long) = InvariantRule(
            id = InvariantRuleId("rule"),
            title = title,
            category = InvariantCategory.ARCHITECTURE,
            statement = "statement",
            enabled = true,
            revision = RuleRevision(revision),
            createdAt = 1,
            updatedAt = 1,
        )
    }
}
