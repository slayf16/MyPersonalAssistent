package com.mypersonalassistent.core.invariants.impl

import com.mypersonalassistent.core.invariants.api.CollectionRevision
import com.mypersonalassistent.core.invariants.api.GateOutcome
import com.mypersonalassistent.core.invariants.api.InvariantGateStage
import com.mypersonalassistent.core.invariants.api.InvariantSnapshot
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotId
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotEntry
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.InvariantCategory
import com.mypersonalassistent.core.invariants.api.RuleRevision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicInvariantGuardTest {
    private val emptySnapshot = InvariantSnapshot(InvariantSnapshotId("snapshot"), CollectionRevision(7), emptyList(), "digest", 1)

    @Test fun `empty user policy is allowed at request gate`() = runBlocking {
        val result = DeterministicInvariantGuard().check(InvariantGateStage.REQUEST, emptySnapshot, "ordinary request")
        assertTrue(result is GateOutcome.Allowed)
    }

    @Test fun `unsafe artifact is refused at final gate`() = runBlocking {
        val rule = InvariantSnapshotEntry(InvariantRuleId("user-rule"), RuleRevision(3), InvariantCategory.BUSINESS_RULE, "No kiwi", "Не публикуй kiwi")
        val snapshot = emptySnapshot.copy(entries = listOf(rule))
        val result = DeterministicInvariantGuard().check(InvariantGateStage.FINAL, snapshot, "publish kiwi")
        assertTrue(result is GateOutcome.Rejected)
        result as GateOutcome.Rejected
        assertTrue(result.refusal.ruleId == rule.ruleId && result.refusal.category == rule.category)
    }
}
