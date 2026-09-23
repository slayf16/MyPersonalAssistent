package com.mypersonalassistent.core.invariants.impl

import com.mypersonalassistent.core.database.api.InvariantStorage
import com.mypersonalassistent.core.database.api.StoredInvariantCommitResult
import com.mypersonalassistent.core.database.api.StoredInvariantMutation
import com.mypersonalassistent.core.database.api.StoredInvariantRule
import com.mypersonalassistent.core.database.api.StoredInvariantSnapshot
import com.mypersonalassistent.core.database.api.StoredAgentRunIndex
import com.mypersonalassistent.core.invariants.api.CollectionRevision
import com.mypersonalassistent.core.invariants.api.ConfirmMutationResult
import com.mypersonalassistent.core.invariants.api.InvariantChange
import com.mypersonalassistent.core.invariants.api.GateOutcome
import com.mypersonalassistent.core.invariants.api.InvariantGateStage
import com.mypersonalassistent.core.invariants.api.InvariantGuard
import com.mypersonalassistent.core.invariants.api.InvariantSemanticPort
import com.mypersonalassistent.core.invariants.api.SemanticGuardResult
import com.mypersonalassistent.core.invariants.api.InvariantMutation
import com.mypersonalassistent.core.invariants.api.InvariantOperation
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.core.invariants.api.InvariantRule
import com.mypersonalassistent.core.invariants.api.InvariantRuleDraft
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.InvariantSnapshot
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotEntry
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotId
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotRef
import com.mypersonalassistent.core.invariants.api.InvariantValidationError
import com.mypersonalassistent.core.invariants.api.MutationImpact
import com.mypersonalassistent.core.invariants.api.PrepareMutationResult
import com.mypersonalassistent.core.invariants.api.RuleRevision
import com.mypersonalassistent.core.invariants.api.SafeInvariantRefusal
import com.mypersonalassistent.core.invariants.api.SnapshotResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Domain validation and snapshots stay outside Room, while the actual commit is transactional in storage. */
class DefaultInvariantRepository(
    private val storage: InvariantStorage,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : InvariantRepository {
    private val confirmations = mutableMapOf<String, InvariantMutation>()
    private val confirmationMutex = Mutex()
    private val mutableChanges = MutableSharedFlow<InvariantChange>(extraBufferCapacity = 16)

    override val rules: Flow<List<InvariantRule>> = storage.observeInvariantRules().map { rows -> rows.map { it.toDomain() } }
    override val changes: Flow<InvariantChange> = mutableChanges.asSharedFlow()

    override suspend fun collectionRevision(): CollectionRevision = storage.invariantCollectionRevision()
    override suspend fun read(id: InvariantRuleId): InvariantRule? = storage.readInvariantRule(id)?.toDomain()

    override suspend fun prepareMutation(mutation: InvariantMutation): PrepareMutationResult {
        val all = storage.readInvariantRulesIncludingDeleted()
        val currentRevision = storage.invariantCollectionRevision()
        if (mutation.expectedCollectionRevision != currentRevision) return rejectedStale(currentRevision, mutation, all)
        val error = validate(mutation, all)
        if (error != null) return PrepareMutationResult.Rejected(error)
        val confirmationId = UUID.randomUUID().toString()
        confirmationMutex.withLock { confirmations[confirmationId] = mutation }
        val affected = storage.countAffectedNonterminalRuns(currentRevision)
        return PrepareMutationResult.Ready(MutationImpact(mutation, affected, confirmationId))
    }

    override suspend fun confirmMutation(confirmationId: String): ConfirmMutationResult {
        val mutation = confirmationMutex.withLock { confirmations.remove(confirmationId) }
            ?: return ConfirmMutationResult.Rejected(InvariantValidationError.Stale(storage.invariantCollectionRevision()))
        val result = storage.commitInvariantMutation(mutation.toStored(clock()))
        return when (result) {
            is StoredInvariantCommitResult.Committed -> {
                val change = InvariantChange(result.collectionRevision, result.affectedRunIds)
                mutableChanges.tryEmit(change)
                ConfirmMutationResult.Committed(result.rule?.toDomain(), result.collectionRevision, result.affectedRunIds)
            }
            is StoredInvariantCommitResult.Rejected -> ConfirmMutationResult.Rejected(result.toError())
        }
    }

    override suspend fun createSnapshot(): SnapshotResult {
        val revision = storage.invariantCollectionRevision()
        val entries = storage.readInvariantRulesIncludingDeleted().asSequence()
            .filter { it.deletedAt == null && it.enabled }
            .sortedWith(compareBy<StoredInvariantRule> { it.id.value })
            .map { InvariantSnapshotEntry(it.id, it.revision, it.category, it.title, it.statement) }
            .toList()
        val digest = digest(entries.serialize())
        val snapshot = InvariantSnapshot(InvariantSnapshotId(UUID.randomUUID().toString()), revision, entries, digest, clock())
        val stored = StoredInvariantSnapshot(snapshot.id.value, revision, snapshot.serialize(), digest, SNAPSHOT_SCHEMA, snapshot.createdAt)
        return if (storage.saveInvariantSnapshot(stored) is com.mypersonalassistent.core.database.api.StorageResult.Success) SnapshotResult.Available(snapshot) else SnapshotResult.Unavailable
    }

    override suspend fun readSnapshot(ref: InvariantSnapshotRef): SnapshotResult {
        val stored = storage.readInvariantSnapshot(ref.id.value) ?: return SnapshotResult.Unavailable
        if (stored.schemaVersion != SNAPSHOT_SCHEMA || stored.collectionRevision != ref.collectionRevision || stored.contentDigest != ref.contentDigest) return SnapshotResult.Unavailable
        return try {
            val entries = deserialize(stored.payload)
            if (digest(entries.serialize()) != stored.contentDigest) SnapshotResult.Unavailable
            else SnapshotResult.Available(InvariantSnapshot(InvariantSnapshotId(stored.id), stored.collectionRevision, entries, stored.contentDigest, stored.createdAt))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) { SnapshotResult.Unavailable }
    }

    override suspend fun trackRun(
        chatId: String,
        runId: String,
        snapshotRef: InvariantSnapshotRef,
        isNonterminal: Boolean,
        isActive: Boolean,
        isStale: Boolean,
        staleTarget: String?,
    ): Boolean = storage.upsertAgentRunIndex(
        StoredAgentRunIndex(chatId, runId, snapshotRef.collectionRevision, isNonterminal, isActive, isStale, staleTarget, clock()),
    ) is com.mypersonalassistent.core.database.api.StorageResult.Success

    private suspend fun rejectedStale(current: CollectionRevision, mutation: InvariantMutation, all: List<StoredInvariantRule>): PrepareMutationResult.Rejected {
        val id = when (mutation) { is InvariantMutation.Create -> null; is InvariantMutation.Edit -> mutation.ruleId; is InvariantMutation.Toggle -> mutation.ruleId; is InvariantMutation.Delete -> mutation.ruleId }
        return PrepareMutationResult.Rejected(InvariantValidationError.Stale(current, id?.let { ruleId -> all.firstOrNull { it.id == ruleId }?.revision }))
    }

    private fun validate(mutation: InvariantMutation, all: List<StoredInvariantRule>): InvariantValidationError? {
        val current = all.filter { it.deletedAt == null }
        val target = when (mutation) {
            is InvariantMutation.Create -> null
            is InvariantMutation.Edit -> current.firstOrNull { it.id == mutation.ruleId } ?: return InvariantValidationError.NotFound
            is InvariantMutation.Toggle -> current.firstOrNull { it.id == mutation.ruleId } ?: return InvariantValidationError.NotFound
            is InvariantMutation.Delete -> current.firstOrNull { it.id == mutation.ruleId } ?: return InvariantValidationError.NotFound
        }
        if (target != null && target.revision != when (mutation) { is InvariantMutation.Edit -> mutation.expectedRuleRevision; is InvariantMutation.Toggle -> mutation.expectedRuleRevision; is InvariantMutation.Delete -> mutation.expectedRuleRevision; is InvariantMutation.Create -> null }) {
            return InvariantValidationError.Stale(mutation.expectedCollectionRevision, target.revision)
        }
        val draft = when (mutation) { is InvariantMutation.Create -> mutation.draft; is InvariantMutation.Edit -> mutation.draft; is InvariantMutation.Toggle -> target!!.toDraft(enabled = mutation.enabled); is InvariantMutation.Delete -> return null }
        val normalized = draft.normalized() ?: return InvariantValidationError.Field("statement", "INVALID_CONTENT")
        if (normalized.title.countCodePoints() !in 1..80) return InvariantValidationError.Field("title", "TITLE_LENGTH")
        if (normalized.statement.countCodePoints() !in 1..1000) return InvariantValidationError.Field("statement", "STATEMENT_LENGTH")
        if (isUnsafe(normalized.statement)) return InvariantValidationError.Field("statement", "SECURITY_BOUNDARY")
        val duplicate = current.firstOrNull { it.id != target?.id && it.category == normalized.category && normalizeStatement(it.statement) == normalized.statement }
        if (duplicate != null) return InvariantValidationError.Duplicate(duplicate.id, duplicate.title)
        val projected = current.filter { it.id != target?.id } + StoredInvariantRule(target?.id ?: InvariantRuleId("new"), normalized.title, normalized.category, normalized.statement, normalized.enabled, target?.revision ?: RuleRevision(1), 0, 0)
        if (mutation is InvariantMutation.Create && current.size >= 100) return InvariantValidationError.Limit("TOTAL_RULES")
        if (projected.count { it.enabled } > 20) return InvariantValidationError.Limit("ENABLED_RULES")
        if (projected.filter { it.enabled }.sumOf { it.statement.countCodePoints() } > 6000) return InvariantValidationError.Limit("ACTIVE_STATEMENT_SIZE")
        return null
    }

    private fun InvariantMutation.toStored(now: Long): StoredInvariantMutation = when (this) {
        is InvariantMutation.Create -> StoredInvariantMutation(InvariantOperation.CREATE, InvariantRuleId(UUID.randomUUID().toString()), draft.normalized()!!.title, draft.category, draft.normalized()!!.statement, draft.enabled, expectedCollectionRevision, null, now)
        is InvariantMutation.Edit -> StoredInvariantMutation(InvariantOperation.EDIT, ruleId, draft.normalized()!!.title, draft.category, draft.normalized()!!.statement, draft.enabled, expectedCollectionRevision, expectedRuleRevision, now)
        is InvariantMutation.Toggle -> StoredInvariantMutation(if (enabled) InvariantOperation.ENABLE else InvariantOperation.DISABLE, ruleId, null, null, null, enabled, expectedCollectionRevision, expectedRuleRevision, now)
        is InvariantMutation.Delete -> StoredInvariantMutation(InvariantOperation.DELETE, ruleId, null, null, null, null, expectedCollectionRevision, expectedRuleRevision, now)
    }

    private fun StoredInvariantCommitResult.Rejected.toError(): InvariantValidationError = when (code) {
        "NOT_FOUND" -> InvariantValidationError.NotFound
        "STORAGE" -> InvariantValidationError.StorageUnavailable
        else -> InvariantValidationError.Stale(currentCollectionRevision, currentRuleRevision)
    }
    private fun StoredInvariantRule.toDomain() = InvariantRule(id, title, category, statement, enabled, revision, createdAt, updatedAt, deletedAt)
    private fun StoredInvariantRule.toDraft(enabled: Boolean) = InvariantRuleDraft(title, category, statement, enabled)
    private fun InvariantRuleDraft.normalized(): InvariantRuleDraft? {
        val normalizedTitle = title.trim()
        val normalizedStatement = normalizeStatement(statement).trim()
        return if (normalizedTitle.any(::forbidden) || normalizedStatement.any(::forbidden)) null else copy(title = normalizedTitle, statement = normalizedStatement)
    }
    private fun normalizeStatement(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\u00a0\\u2000-\\u200b\\u202f\\u205f\\u3000]+"), " ")
    private fun forbidden(char: Char): Boolean = char == '\u0000' || (char.isISOControl() && char != '\n' && char != '\t')
    private fun String.countCodePoints(): Int = codePointCount(0, length)
    /** Finite direct-intent RU/EN boundary after NFC/whitespace normalization. */
    private fun isUnsafe(statement: String): Boolean {
        val normalized = java.text.Normalizer.normalize(statement, java.text.Normalizer.Form.NFC)
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        return listOf(
            "api key", "api-key", "apikey", "secret", "password", "token",
            "body logging", "log request body", "system role", "system prompt",
            "ignore platform security", "ignore security", "override security",
            "игнорируй безопасность", "обойди безопасность", "отключи безопасность",
            "переопредели безопасность", "системная роль", "системный промпт",
            "ключ api", "апи ключ", "секрет", "пароль", "токен",
            "логируй body", "логируй тело запроса",
        ).any(normalized::contains)
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun List<InvariantSnapshotEntry>.serialize(): String = joinToString("\n") { entry -> listOf(entry.ruleId.value, entry.ruleRevision.value, entry.category.name, entry.title, entry.statement).joinToString("|") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toString().toByteArray(StandardCharsets.UTF_8)) } }
    private fun InvariantSnapshot.serialize(): String = entries.serialize()
    private fun deserialize(payload: String): List<InvariantSnapshotEntry> = if (payload.isEmpty()) emptyList() else payload.lines().map { row ->
        val columns = row.split("|"); require(columns.size == 5)
        val values = columns.map { String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8) }
        InvariantSnapshotEntry(InvariantRuleId(values[0]), RuleRevision(values[1].toLong()), com.mypersonalassistent.core.invariants.api.InvariantCategory.valueOf(values[2]), values[3], values[4])
    }
    private companion object { const val SNAPSHOT_SCHEMA = 1 }
}

/** Deterministic guard evaluates user-owned snapshot rules only; it has no hidden policy list. */
class DeterministicInvariantGuard(private val semantic: InvariantSemanticPort = object : InvariantSemanticPort {
    override suspend fun evaluate(stage: InvariantGateStage, snapshot: InvariantSnapshot, artifact: String) = SemanticGuardResult.Unavailable
}) : InvariantGuard {
    override suspend fun check(stage: InvariantGateStage, snapshot: InvariantSnapshot, artifact: String): GateOutcome {
        snapshot.entries.forEach { entry ->
            val forbidden = entry.forbiddenTerms()
            if (forbidden.any { term -> artifact.contains(term, ignoreCase = true) }) {
                return GateOutcome.Rejected(SafeInvariantRefusal(entry.title, entry.category, entry.ruleId, "Результат несовместим с выбранным пользовательским правилом."))
            }
        }
        if (snapshot.entries.isNotEmpty()) when (val result = try {
            semantic.evaluate(stage, snapshot, artifact)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) { SemanticGuardResult.Unavailable }) {
            SemanticGuardResult.Allowed -> Unit
            is SemanticGuardResult.Conflict -> return GateOutcome.Rejected(result.refusal)
            SemanticGuardResult.Unavailable -> return GateOutcome.Unavailable
        }
        return GateOutcome.Allowed(InvariantSnapshotRef(snapshot.id, snapshot.collectionRevision, snapshot.contentDigest), digest(artifact))
    }

    /** Supports explicit user constraints such as «Не упоминай X» / «Do not mention X». */
    private fun InvariantSnapshotEntry.forbiddenTerms(): List<String> {
        val normalized = statement.trim()
        val directive = listOf("никогда не ", "не ", "do not ", "don't ", "forbid ")
            .firstOrNull { normalized.startsWith(it, ignoreCase = true) } ?: return emptyList()
        return normalized.substring(directive.length).trim()
            .replaceFirst(Regex("^(упоминай|публикуй|предлагай|используй|mention|publish|suggest|use)\\s+", RegexOption.IGNORE_CASE), "")
            .split(Regex("[,;.!?]"))
            .map(String::trim).filter { it.codePointCount(0, it.length) >= 3 }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
