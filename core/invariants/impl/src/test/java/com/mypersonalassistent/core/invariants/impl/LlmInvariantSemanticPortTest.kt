package com.mypersonalassistent.core.invariants.impl

import com.mypersonalassistent.core.invariants.api.*
import com.mypersonalassistent.core.llm.api.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmInvariantSemanticPortTest {
    private val snapshot = InvariantSnapshot(InvariantSnapshotId("s"), CollectionRevision(1), listOf(InvariantSnapshotEntry(InvariantRuleId("r"), RuleRevision(1), InvariantCategory.BUSINESS_RULE, "Rule", "Do not ship")), "d", 1)
    @Test fun `allowed conflict and unavailable are strict`() = runBlocking {
        fun port(text: String?) = LlmInvariantSemanticPort(object : Llm { override suspend fun execute(request: LlmRequest) = text?.let { LlmResult.Success(it, null) } ?: LlmResult.Failure(LlmError.NETWORK) })
        assertTrue(port("ALLOWED").evaluate(InvariantGateStage.FINAL, snapshot, "x") is SemanticGuardResult.Allowed)
        val conflict = port("CONFLICT:r").evaluate(InvariantGateStage.FINAL, snapshot, "x") as SemanticGuardResult.Conflict
        assertTrue(conflict.refusal.ruleId == InvariantRuleId("r") && conflict.refusal.title == "Rule")
        assertTrue(port("maybe").evaluate(InvariantGateStage.FINAL, snapshot, "x") is SemanticGuardResult.Unavailable)
    }
}
