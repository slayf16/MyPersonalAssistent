package com.mypersonalassistent.feature.invariants.api

import com.mypersonalassistent.core.invariants.api.InvariantCategory
import com.mypersonalassistent.core.invariants.api.InvariantRule
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.RuleRevision
import kotlinx.serialization.Serializable

/** Immutable UI projection; the repository remains the only source of confirmed rules. */
data class InvariantsState(
    val isLoading: Boolean = true,
    val rules: List<InvariantRule> = emptyList(),
    val loadError: Boolean = false,
    val editor: InvariantEditorState? = null,
    val pendingConfirmation: InvariantMutationConfirmation? = null,
    val dirtyExitConfirmation: Boolean = false,
    val mutationInFlight: Boolean = false,
    val message: InvariantUiMessage? = null,
    val messageDetail: String? = null,
    val draftRestoreWarning: Boolean = false,
) {
    val controlsEnabled: Boolean get() = !isLoading && !mutationInFlight && pendingConfirmation == null
}

/** Bounded StateKeeper payload; restoring it never writes to the repository. */
@Serializable
data class InvariantEditorDraft(
    val ruleId: String? = null,
    val ruleRevision: Long? = null,
    val category: String = InvariantCategory.ARCHITECTURE.name,
    val title: String = "",
    val statement: String = "",
    val initialCategory: String = category,
    val initialTitle: String = title,
    val initialStatement: String = statement,
)

data class InvariantEditorState(
    val ruleId: InvariantRuleId? = null,
    val ruleRevision: RuleRevision? = null,
    val category: InvariantCategory = InvariantCategory.ARCHITECTURE,
    val title: String = "",
    val statement: String = "",
    val initialCategory: InvariantCategory = category,
    val initialTitle: String = title,
    val initialStatement: String = statement,
    val validation: InvariantFormValidation = InvariantFormValidation(
        titleError = InvariantUiMessage.TITLE_REQUIRED,
        statementError = InvariantUiMessage.STATEMENT_REQUIRED,
    ),
    val reloadRequired: Boolean = false,
) {
    val isEdit: Boolean get() = ruleId != null
    val isDirty: Boolean get() = category != initialCategory || title != initialTitle || statement != initialStatement
}

data class InvariantFormValidation(
    val titleError: InvariantUiMessage? = null,
    val statementError: InvariantUiMessage? = null,
) {
    val valid: Boolean get() = titleError == null && statementError == null
}

/** The confirmation token is opaque and only produced by the repository. */
data class InvariantMutationConfirmation(
    val confirmationId: String,
    val affectedNonterminalRuns: Int,
    val ruleTitle: String?,
    val isDelete: Boolean,
)

enum class InvariantUiMessage {
    TITLE_REQUIRED,
    TITLE_TOO_LONG,
    STATEMENT_REQUIRED,
    STATEMENT_TOO_LONG,
    INVALID_CHARACTERS,
    DUPLICATE,
    TOTAL_LIMIT,
    ENABLED_LIMIT,
    ACTIVE_TEXT_LIMIT,
    STALE,
    NOT_FOUND,
    STORAGE_UNAVAILABLE,
    INVALID_RULE,
}

sealed interface InvariantsIntent {
    data object Load : InvariantsIntent
    data object Retry : InvariantsIntent
    data object Add : InvariantsIntent
    data class Edit(val id: InvariantRuleId) : InvariantsIntent
    data class Toggle(val id: InvariantRuleId, val enabled: Boolean) : InvariantsIntent
    data class Delete(val id: InvariantRuleId) : InvariantsIntent
    data class ChangeCategory(val value: InvariantCategory) : InvariantsIntent
    data class ChangeTitle(val value: String) : InvariantsIntent
    data class ChangeStatement(val value: String) : InvariantsIntent
    data object Save : InvariantsIntent
    data object ConfirmMutation : InvariantsIntent
    data object CancelMutation : InvariantsIntent
    data object ReloadAndReapply : InvariantsIntent
    data class RestoreEditorDraft(val draft: InvariantEditorDraft?, val restoreFailed: Boolean) : InvariantsIntent
    data object RequestBack : InvariantsIntent
    data object SaveDirtyExit : InvariantsIntent
    data object DiscardDirtyExit : InvariantsIntent
    data object CancelDirtyExit : InvariantsIntent
}

sealed interface InvariantsEffect {
    data object NavigateBack : InvariantsEffect
}
