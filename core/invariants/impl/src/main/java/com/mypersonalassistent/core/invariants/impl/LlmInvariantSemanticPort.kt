package com.mypersonalassistent.core.invariants.impl

import com.mypersonalassistent.core.invariants.api.InvariantArtifactPurpose
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
    override suspend fun evaluate(purpose: InvariantArtifactPurpose, snapshot: InvariantSnapshot, artifact: String): SemanticGuardResult {
        if (snapshot.entries.isEmpty()) return SemanticGuardResult.Allowed
        val policy = snapshot.entries.joinToString("\n") { e ->
            "ruleId=${esc(e.ruleId.value)}; category=${e.category.name}; title=${esc(e.title)}; statement=${esc(e.statement)}"
        }
        val request = LlmRequest(listOf(
            LlmMessage(
                LlmRole.SYSTEM,
                """
                    [[INVARIANT_POLICY]]
                    $policy
                    [[END_INVARIANT_POLICY]]
                    Evaluate only the supplied artifact, not the task as a whole. artifactPurpose identifies it: REQUEST is raw user input; PLAN is planned work; STEP is intermediate assistant output; FINAL is final assistant output.
                    Before reporting a conflict, determine whether a rule governs this artifact purpose. A rule governing an assistant work product does not govern a raw REQUEST merely because that request has different content; a rule that explicitly governs user requests still applies to REQUEST. No artifact purpose has a blanket bypass.
                    Return CONFLICT:<ruleId> only when the supplied artifact actually violates an applicable rule. Return exactly ALLOWED or CONFLICT:<ruleId>.
                """.trimIndent(),
            ),
            LlmMessage(LlmRole.USER, "artifactPurpose=${purpose.name}\nartifact=${esc(artifact)}"),
        ))
        val text = (llm.execute(request) as? LlmResult.Success)?.text?.trim() ?: return SemanticGuardResult.Unavailable
        if (text == "ALLOWED") return SemanticGuardResult.Allowed
        val id = text.removePrefix("CONFLICT:").takeIf { text.startsWith("CONFLICT:") && it.isNotBlank() } ?: return SemanticGuardResult.Unavailable
        val entry = snapshot.entries.firstOrNull { it.ruleId.value == id } ?: return SemanticGuardResult.Unavailable
        return SemanticGuardResult.Conflict(SafeInvariantRefusal(entry.title, entry.category, entry.ruleId, "Результат несовместим с выбранным пользовательским правилом."))
    }
    private fun esc(value: String) = value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("[[", "\\[\\[")
}
