package com.mypersonalassistent.core.invariants.api

import kotlinx.coroutines.flow.Flow

/** The four user-facing categories are labels only and never define priority. */
enum class InvariantCategory { ARCHITECTURE, TECHNICAL_DECISION, STACK_CONSTRAINT, BUSINESS_RULE }
enum class InvariantOperation { CREATE, EDIT, ENABLE, DISABLE, DELETE }

@JvmInline value class InvariantRuleId(val value: String)
@JvmInline value class CollectionRevision(val value: Long)
@JvmInline value class RuleRevision(val value: Long)
@JvmInline value class InvariantSnapshotId(val value: String)

data class InvariantRule(
    val id: InvariantRuleId,
    val title: String,
    val category: InvariantCategory,
    val statement: String,
    val enabled: Boolean,
    val revision: RuleRevision,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

data class InvariantRuleDraft(val title: String, val category: InvariantCategory, val statement: String, val enabled: Boolean = true)

data class InvariantSnapshotEntry(
    val ruleId: InvariantRuleId,
    val ruleRevision: RuleRevision,
    val category: InvariantCategory,
    val title: String,
    val statement: String,
)

data class InvariantSnapshot(
    val id: InvariantSnapshotId,
    val collectionRevision: CollectionRevision,
    val entries: List<InvariantSnapshotEntry>,
    val contentDigest: String,
    val createdAt: Long,
)

data class InvariantSnapshotRef(val id: InvariantSnapshotId, val collectionRevision: CollectionRevision, val contentDigest: String)

data class InvariantAuditEvent(
    val eventId: String,
    val ruleId: InvariantRuleId,
    val operation: InvariantOperation,
    val oldRuleRevision: RuleRevision?,
    val newRuleRevision: RuleRevision?,
    val oldContentDigest: String?,
    val newContentDigest: String?,
    val collectionRevision: CollectionRevision,
    val createdAt: Long,
)

sealed interface InvariantMutation {
    val expectedCollectionRevision: CollectionRevision
    data class Create(val draft: InvariantRuleDraft, override val expectedCollectionRevision: CollectionRevision) : InvariantMutation
    data class Edit(val ruleId: InvariantRuleId, val draft: InvariantRuleDraft, val expectedRuleRevision: RuleRevision, override val expectedCollectionRevision: CollectionRevision) : InvariantMutation
    data class Toggle(val ruleId: InvariantRuleId, val enabled: Boolean, val expectedRuleRevision: RuleRevision, override val expectedCollectionRevision: CollectionRevision) : InvariantMutation
    data class Delete(val ruleId: InvariantRuleId, val expectedRuleRevision: RuleRevision, override val expectedCollectionRevision: CollectionRevision) : InvariantMutation
}

/** Explicit UI-to-domain command for creating a rule.  It intentionally carries the
 * displayed collection revision so the repository can reject a stale form without
 * writing anything. */
data class CreateInvariantCommand(
    val draft: InvariantRuleDraft,
    val expectedCollectionRevision: CollectionRevision,
)

/** Explicit UI-to-domain command for editing one already displayed rule. */
data class EditInvariantCommand(
    val ruleId: InvariantRuleId,
    val draft: InvariantRuleDraft,
    val expectedRuleRevision: RuleRevision,
    val expectedCollectionRevision: CollectionRevision,
)

data class MutationImpact(
    val mutation: InvariantMutation,
    val affectedNonterminalRuns: Int,
    /** Opaque single-use token; callers must not construct it. */
    val confirmationId: String,
)

sealed interface InvariantValidationError {
    data class Field(val field: String, val code: String) : InvariantValidationError
    data class Duplicate(val existingRuleId: InvariantRuleId, val existingTitle: String) : InvariantValidationError
    data class Limit(val code: String) : InvariantValidationError
    data class Stale(val currentCollectionRevision: CollectionRevision, val currentRuleRevision: RuleRevision? = null) : InvariantValidationError
    data object NotFound : InvariantValidationError
    data object StorageUnavailable : InvariantValidationError
}

sealed interface PrepareMutationResult {
    data class Ready(val impact: MutationImpact) : PrepareMutationResult
    data class Rejected(val error: InvariantValidationError) : PrepareMutationResult
}

sealed interface ConfirmMutationResult {
    data class Committed(val rule: InvariantRule?, val collectionRevision: CollectionRevision, val affectedRunIds: Set<String>) : ConfirmMutationResult
    data class Rejected(val error: InvariantValidationError) : ConfirmMutationResult
}

data class InvariantChange(val collectionRevision: CollectionRevision, val affectedRunIds: Set<String>)

/** User-owned source of truth. No transcript, feature state or prompt may mutate it. */
interface InvariantRepository {
    val rules: Flow<List<InvariantRule>>
    val changes: Flow<InvariantChange>
    suspend fun collectionRevision(): CollectionRevision
    suspend fun read(id: InvariantRuleId): InvariantRule?
    suspend fun prepareMutation(mutation: InvariantMutation): PrepareMutationResult
    suspend fun prepareCreate(command: CreateInvariantCommand): PrepareMutationResult =
        prepareMutation(InvariantMutation.Create(command.draft, command.expectedCollectionRevision))
    suspend fun prepareEdit(command: EditInvariantCommand): PrepareMutationResult =
        prepareMutation(
            InvariantMutation.Edit(
                ruleId = command.ruleId,
                draft = command.draft,
                expectedRuleRevision = command.expectedRuleRevision,
                expectedCollectionRevision = command.expectedCollectionRevision,
            ),
        )
    suspend fun confirmMutation(confirmationId: String): ConfirmMutationResult
    suspend fun createSnapshot(): SnapshotResult
    suspend fun readSnapshot(ref: InvariantSnapshotRef): SnapshotResult
    /** Durable run index used solely for stale-impact accounting; it contains no transcript. */
    suspend fun trackRun(
        chatId: String,
        runId: String,
        snapshotRef: InvariantSnapshotRef,
        isNonterminal: Boolean,
        isActive: Boolean,
        isStale: Boolean = false,
        staleTarget: String? = null,
    ): Boolean
}

sealed interface SnapshotResult {
    data class Available(val snapshot: InvariantSnapshot) : SnapshotResult
    data object Unavailable : SnapshotResult
}

enum class InvariantGateStage { REQUEST, PLAN, STEP, FINAL }

/**
 * The complete outcome of exactly one guard invocation.  It is deliberately local to
 * a call: no refusal metadata may be read from, or retained for, another gate/run.
 */
sealed interface GateOutcome {
    data class Allowed(val snapshotRef: InvariantSnapshotRef, val artifactDigest: String) : GateOutcome
    data class Rejected(val refusal: SafeInvariantRefusal) : GateOutcome
    data object Unavailable : GateOutcome
}

@Deprecated("Use GateOutcome")
typealias InvariantGateResult = GateOutcome
data class SafeInvariantRefusal(val title: String?, val category: InvariantCategory?, val ruleId: InvariantRuleId?, val explanation: String)
sealed interface SemanticGuardResult { data object Allowed : SemanticGuardResult; data class Conflict(val refusal: SafeInvariantRefusal) : SemanticGuardResult; data object Unavailable : SemanticGuardResult }
interface InvariantSemanticPort { suspend fun evaluate(stage: InvariantGateStage, snapshot: InvariantSnapshot, artifact: String): SemanticGuardResult }

/** A guard is fail-closed: an unavailable result is never equivalent to Allowed. */
interface InvariantGuard {
    suspend fun check(stage: InvariantGateStage, snapshot: InvariantSnapshot, artifact: String): GateOutcome
}
