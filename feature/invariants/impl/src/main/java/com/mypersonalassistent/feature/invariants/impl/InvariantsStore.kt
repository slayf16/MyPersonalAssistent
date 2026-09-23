package com.mypersonalassistent.feature.invariants.impl

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.mypersonalassistent.core.invariants.api.ConfirmMutationResult
import com.mypersonalassistent.core.invariants.api.InvariantCategory
import com.mypersonalassistent.core.invariants.api.CreateInvariantCommand
import com.mypersonalassistent.core.invariants.api.EditInvariantCommand
import com.mypersonalassistent.core.invariants.api.InvariantMutation
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.core.invariants.api.InvariantRule
import com.mypersonalassistent.core.invariants.api.InvariantRuleDraft
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.RuleRevision
import com.mypersonalassistent.core.invariants.api.InvariantValidationError
import com.mypersonalassistent.core.invariants.api.PrepareMutationResult
import com.mypersonalassistent.feature.invariants.api.InvariantEditorState
import com.mypersonalassistent.feature.invariants.api.InvariantEditorDraft
import com.mypersonalassistent.feature.invariants.api.InvariantFormValidation
import com.mypersonalassistent.feature.invariants.api.InvariantMutationConfirmation
import com.mypersonalassistent.feature.invariants.api.InvariantUiMessage
import com.mypersonalassistent.feature.invariants.api.InvariantsEffect
import com.mypersonalassistent.feature.invariants.api.InvariantsIntent
import com.mypersonalassistent.feature.invariants.api.InvariantsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal interface InvariantsStore : Store<InvariantsIntent, InvariantsState, InvariantsEffect>

internal class InvariantsStoreFactory(
    private val storeFactory: StoreFactory,
    private val repository: InvariantRepository,
) {
    fun create(): InvariantsStore = object : InvariantsStore,
        Store<InvariantsIntent, InvariantsState, InvariantsEffect> by storeFactory.create(
            name = "InvariantsStore",
            initialState = InvariantsState(),
            bootstrapper = null,
            executorFactory = ::Executor,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Message {
        data class State(val value: InvariantsState) : Message
    }

    private inner class Executor : CoroutineExecutor<InvariantsIntent, Nothing, InvariantsState, Message, InvariantsEffect>() {
        private var observing: Job? = null

        override fun executeIntent(intent: InvariantsIntent) {
            when (intent) {
                InvariantsIntent.Load, InvariantsIntent.Retry -> observe()
                InvariantsIntent.Add -> if (state().controlsEnabled) set { copy(editor = InvariantEditorState(), message = null, messageDetail = null) }
                is InvariantsIntent.Edit -> openEditor(intent.id)
                is InvariantsIntent.Toggle -> prepareRuleMutation(intent.id) { rule, revision ->
                    InvariantMutation.Toggle(rule.id, intent.enabled, rule.revision, revision)
                }
                is InvariantsIntent.Delete -> prepareRuleMutation(intent.id) { rule, revision ->
                    InvariantMutation.Delete(rule.id, rule.revision, revision)
                }
                is InvariantsIntent.ChangeCategory -> edit { copy(category = intent.value, validation = validate(title, statement)) }
                is InvariantsIntent.ChangeTitle -> edit { copy(title = intent.value, validation = validate(intent.value, statement)) }
                is InvariantsIntent.ChangeStatement -> edit { copy(statement = intent.value, validation = validate(title, intent.value)) }
                InvariantsIntent.Save -> submitEditor()
                InvariantsIntent.ConfirmMutation -> confirmMutation()
                InvariantsIntent.CancelMutation -> if (!state().mutationInFlight) set { copy(pendingConfirmation = null) }
                InvariantsIntent.ReloadAndReapply -> reloadEditor()
                is InvariantsIntent.RestoreEditorDraft -> restoreEditorDraft(intent)
                InvariantsIntent.RequestBack -> requestBack()
                InvariantsIntent.SaveDirtyExit -> {
                    if (!state().mutationInFlight) {
                        set { copy(dirtyExitConfirmation = false) }
                        submitEditor()
                    }
                }
                InvariantsIntent.DiscardDirtyExit -> if (!state().mutationInFlight) set { copy(editor = null, dirtyExitConfirmation = false, message = null, messageDetail = null) }
                InvariantsIntent.CancelDirtyExit -> if (!state().mutationInFlight) set { copy(dirtyExitConfirmation = false) }
            }
        }

        private fun observe() {
            observing?.cancel()
            set { copy(isLoading = true, loadError = false, message = null, messageDetail = null) }
            observing = scope.launch {
                try {
                    repository.rules.collect { rules ->
                        set { copy(isLoading = false, rules = rules, loadError = false) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    set { copy(isLoading = false, loadError = true) }
                }
            }
        }

        private fun openEditor(id: InvariantRuleId) {
            if (!state().controlsEnabled) return
            val rule = state().rules.firstOrNull { it.id == id } ?: return
            set {
                copy(
                    editor = InvariantEditorState(
                        ruleId = rule.id,
                        ruleRevision = rule.revision,
                        category = rule.category,
                        title = rule.title,
                        statement = rule.statement,
                        initialCategory = rule.category,
                        initialTitle = rule.title,
                        initialStatement = rule.statement,
                        validation = validate(rule.title, rule.statement),
                    ),
                    message = null,
                    messageDetail = null,
                )
            }
        }

        private fun edit(transform: InvariantEditorState.() -> InvariantEditorState) {
            val editor = state().editor ?: return
            if (!state().mutationInFlight && state().pendingConfirmation == null) set { copy(editor = editor.transform(), message = null, messageDetail = null) }
        }

        private fun submitEditor() {
            val editor = state().editor ?: return
            if (!state().controlsEnabled) return
            val validation = validate(editor.title, editor.statement)
            if (!validation.valid) {
                set { copy(editor = editor.copy(validation = validation)) }
                return
            }
            set { copy(mutationInFlight = true, message = null, messageDetail = null) }
            scope.launch {
                try {
                    val revision = repository.collectionRevision()
                    val draft = InvariantRuleDraft(editor.title, editor.category, editor.statement)
                    val result = if (editor.isEdit) {
                        repository.prepareEdit(
                            EditInvariantCommand(
                                ruleId = requireNotNull(editor.ruleId),
                                draft = draft,
                                expectedRuleRevision = requireNotNull(editor.ruleRevision),
                                expectedCollectionRevision = revision,
                            ),
                        )
                    } else {
                        repository.prepareCreate(CreateInvariantCommand(draft, revision))
                    }
                    handlePrepare(result, editor.ruleId?.let { state().rules.firstOrNull { rule -> rule.id == it } }?.title, isDelete = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    fail(InvariantUiMessage.STORAGE_UNAVAILABLE)
                }
            }
        }

        private fun prepareRuleMutation(id: InvariantRuleId, makeMutation: (InvariantRule, com.mypersonalassistent.core.invariants.api.CollectionRevision) -> InvariantMutation) {
            if (!state().controlsEnabled) return
            val rule = state().rules.firstOrNull { it.id == id } ?: return
            set { copy(mutationInFlight = true, message = null, messageDetail = null) }
            scope.launch {
                try {
                    val mutation = makeMutation(rule, repository.collectionRevision())
                    handlePrepare(repository.prepareMutation(mutation), rule.title, mutation is InvariantMutation.Delete)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    fail(InvariantUiMessage.STORAGE_UNAVAILABLE)
                }
            }
        }

        private suspend fun handlePrepare(result: PrepareMutationResult, title: String?, isDelete: Boolean) {
            when (result) {
                is PrepareMutationResult.Ready -> {
                    val impact = result.impact
                    set {
                        copy(
                            mutationInFlight = false,
                            pendingConfirmation = InvariantMutationConfirmation(
                                confirmationId = impact.confirmationId,
                                affectedNonterminalRuns = impact.affectedNonterminalRuns,
                                ruleTitle = title,
                                isDelete = isDelete,
                            ),
                        )
                    }
                }
                is PrepareMutationResult.Rejected -> reject(result.error)
            }
        }

        private fun confirmMutation() {
            val confirmation = state().pendingConfirmation ?: return
            if (state().mutationInFlight) return
            set { copy(mutationInFlight = true, message = null, messageDetail = null) }
            scope.launch {
                try {
                    when (val result = repository.confirmMutation(confirmation.confirmationId)) {
                        is ConfirmMutationResult.Committed -> set {
                            copy(
                                editor = null,
                                pendingConfirmation = null,
                                dirtyExitConfirmation = false,
                                mutationInFlight = false,
                                message = null,
                                messageDetail = null,
                            )
                        }
                        is ConfirmMutationResult.Rejected -> reject(result.error, clearConfirmation = true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    fail(InvariantUiMessage.STORAGE_UNAVAILABLE, clearConfirmation = true)
                }
            }
        }

        private fun reloadEditor() {
            val editor = state().editor ?: return
            val id = editor.ruleId ?: return
            if (state().mutationInFlight) return
            set { copy(mutationInFlight = true, message = null, messageDetail = null) }
            scope.launch {
                try {
                    val current = repository.read(id)
                    if (current == null) fail(InvariantUiMessage.NOT_FOUND) else set {
                        copy(
                            editor = editor.copy(ruleRevision = current.revision, reloadRequired = false),
                            mutationInFlight = false,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    fail(InvariantUiMessage.STORAGE_UNAVAILABLE)
                }
            }
        }

        private fun restoreEditorDraft(intent: InvariantsIntent.RestoreEditorDraft) {
            if (state().editor != null) return
            val restored = intent.draft?.toEditorStateOrNull()
            if (restored != null && !intent.restoreFailed) {
                set { copy(editor = restored, draftRestoreWarning = false, message = null, messageDetail = null) }
            } else if (intent.restoreFailed || intent.draft != null) {
                set { copy(editor = InvariantEditorState(), draftRestoreWarning = true, message = null, messageDetail = null) }
            }
        }

        private fun requestBack() {
            val editor = state().editor
            when {
                state().mutationInFlight -> Unit
                state().pendingConfirmation != null -> set { copy(pendingConfirmation = null) }
                editor == null -> publish(InvariantsEffect.NavigateBack)
                editor.isDirty -> set { copy(dirtyExitConfirmation = true) }
                else -> set { copy(editor = null, message = null, messageDetail = null) }
            }
        }

        private fun reject(error: InvariantValidationError, clearConfirmation: Boolean = false) {
            val message = error.toUiMessage()
            set {
                val currentEditor = editor
                copy(
                    editor = if (error is InvariantValidationError.Stale && currentEditor != null) currentEditor.copy(reloadRequired = true) else currentEditor,
                    pendingConfirmation = if (clearConfirmation) null else pendingConfirmation,
                    mutationInFlight = false,
                    message = message,
                    messageDetail = (error as? InvariantValidationError.Duplicate)?.let { "${it.existingTitle} (${it.existingRuleId.value})" },
                )
            }
        }

        private fun fail(message: InvariantUiMessage, clearConfirmation: Boolean = false) {
            set { copy(pendingConfirmation = if (clearConfirmation) null else pendingConfirmation, mutationInFlight = false, message = message, messageDetail = null) }
        }

        private fun set(transform: InvariantsState.() -> InvariantsState) = dispatch(Message.State(state().transform()))
    }

    private object ReducerImpl : Reducer<InvariantsState, Message> {
        override fun InvariantsState.reduce(msg: Message): InvariantsState = when (msg) { is Message.State -> msg.value }
    }
}

internal fun InvariantEditorState.toPersistedDraft() = InvariantEditorDraft(
    ruleId = ruleId?.value,
    ruleRevision = ruleRevision?.value,
    category = category.name,
    title = title,
    statement = statement,
    initialCategory = initialCategory.name,
    initialTitle = initialTitle,
    initialStatement = initialStatement,
)

internal fun InvariantEditorDraft.toEditorStateOrNull(): InvariantEditorState? {
    fun valid(value: String, limit: Int) = value.codePointCount(0, value.length) <= limit
    val currentCategory = runCatching { InvariantCategory.valueOf(category) }.getOrNull() ?: return null
    val savedCategory = runCatching { InvariantCategory.valueOf(initialCategory) }.getOrNull() ?: return null
    if (!valid(title, 80) || !valid(statement, 1_000) || !valid(initialTitle, 80) || !valid(initialStatement, 1_000)) return null
    val restoredRuleId = ruleId
    val restoredRuleRevision = ruleRevision
    if (restoredRuleId != null && (restoredRuleId.isBlank() || restoredRuleRevision == null || restoredRuleRevision < 1)) return null
    if (restoredRuleId == null && restoredRuleRevision != null) return null
    return InvariantEditorState(
        ruleId = restoredRuleId?.let(::InvariantRuleId),
        ruleRevision = restoredRuleRevision?.let(::RuleRevision),
        category = currentCategory,
        title = title,
        statement = statement,
        initialCategory = savedCategory,
        initialTitle = initialTitle,
        initialStatement = initialStatement,
        validation = validate(title, statement),
    )
}

private fun validate(title: String, statement: String): InvariantFormValidation {
    fun count(value: String) = value.codePointCount(0, value.length)
    fun invalid(value: String, multiline: Boolean) = value.any { char -> char == '\u0000' || (Character.isISOControl(char) && (!multiline || char != '\n' && char != '\r')) }
    val titleMessage = when {
        title.trim().isEmpty() -> InvariantUiMessage.TITLE_REQUIRED
        count(title.trim()) > 80 -> InvariantUiMessage.TITLE_TOO_LONG
        invalid(title, multiline = false) -> InvariantUiMessage.INVALID_CHARACTERS
        else -> null
    }
    val statementMessage = when {
        statement.trim().isEmpty() -> InvariantUiMessage.STATEMENT_REQUIRED
        count(statement.trim()) > 1_000 -> InvariantUiMessage.STATEMENT_TOO_LONG
        invalid(statement, multiline = true) -> InvariantUiMessage.INVALID_CHARACTERS
        else -> null
    }
    return InvariantFormValidation(titleMessage, statementMessage)
}

private fun InvariantValidationError.toUiMessage(): InvariantUiMessage = when (this) {
    is InvariantValidationError.Duplicate -> InvariantUiMessage.DUPLICATE
    is InvariantValidationError.Limit -> when (code) {
        "TOTAL_RULES" -> InvariantUiMessage.TOTAL_LIMIT
        "ENABLED_RULES" -> InvariantUiMessage.ENABLED_LIMIT
        "ACTIVE_STATEMENT_SIZE" -> InvariantUiMessage.ACTIVE_TEXT_LIMIT
        else -> InvariantUiMessage.INVALID_RULE
    }
    is InvariantValidationError.Stale -> InvariantUiMessage.STALE
    is InvariantValidationError.Field -> when (code) {
        "TITLE_REQUIRED" -> InvariantUiMessage.TITLE_REQUIRED
        "TITLE_TOO_LONG", "TITLE_LENGTH" -> InvariantUiMessage.TITLE_TOO_LONG
        "STATEMENT_REQUIRED" -> InvariantUiMessage.STATEMENT_REQUIRED
        "STATEMENT_TOO_LONG", "STATEMENT_LENGTH" -> InvariantUiMessage.STATEMENT_TOO_LONG
        "INVALID_CHARACTERS", "INVALID_CONTENT" -> InvariantUiMessage.INVALID_CHARACTERS
        else -> InvariantUiMessage.INVALID_RULE
    }
    InvariantValidationError.NotFound -> InvariantUiMessage.NOT_FOUND
    InvariantValidationError.StorageUnavailable -> InvariantUiMessage.STORAGE_UNAVAILABLE
}
