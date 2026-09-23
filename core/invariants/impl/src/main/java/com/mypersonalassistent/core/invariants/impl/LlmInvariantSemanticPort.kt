package com.mypersonalassistent.core.invariants.impl

import com.mypersonalassistent.core.invariants.api.InvariantGateStage
import com.mypersonalassistent.core.invariants.api.InvariantSemanticPort
import com.mypersonalassistent.core.invariants.api.InvariantSnapshot
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotRef
import com.mypersonalassistent.core.invariants.api.SafeInvariantRefusal
import com.mypersonalassistent.core.invariants.api.SemanticGuardResult
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmMessage
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.llm.api.LlmResult
import com.mypersonalassistent.core.llm.api.LlmRole

/** Bounded one-shot semantic check; it never invokes the agent engine or exposes raw policy externally. */
class LlmInvariantSemanticPort(private val llm: Llm) : InvariantSemanticPort {
    override suspend fun evaluate(stage: InvariantGateStage, snapshot: InvariantSnapshot, artifact: String): SemanticGuardResult {
        if (snapshot.entries.isEmpty()) return SemanticGuardResult.Allowed
        val policy = snapshot.entries.joinToString("\n") { e ->
            "ruleId=${esc(e.ruleId.value)}; category=${e.category.name}; title=${esc(e.title)}; statement=${esc(e.statement)}"
        }
        val request = LlmRequest(listOf(
            LlmMessage(LlmRole.SYSTEM, "[[INVARIANT_POLICY]]\n$policy\n[[END_INVARIANT_POLICY]]\nReturn exactly ALLOWED or CONFLICT:<ruleId>."),
            LlmMessage(LlmRole.USER, "stage=${stage.name}\nartifact=${esc(artifact)}"),
        ))
        val text = (llm.execute(request) as? LlmResult.Success)?.text?.trim() ?: return SemanticGuardResult.Unavailable
        if (text == "ALLOWED") return SemanticGuardResult.Allowed
        val id = text.removePrefix("CONFLICT:").takeIf { text.startsWith("CONFLICT:") && it.isNotBlank() } ?: return SemanticGuardResult.Unavailable
        val entry = snapshot.entries.firstOrNull { it.ruleId.value == id } ?: return SemanticGuardResult.Unavailable
        return SemanticGuardResult.Conflict(SafeInvariantRefusal(entry.title, entry.category, entry.ruleId, "Результат несовместим с выбранным пользовательским правилом."))
    }
    private fun esc(value: String) = value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("[[", "\\[\\[")
}
