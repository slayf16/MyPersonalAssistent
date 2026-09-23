package com.mypersonalassistent.core.agent.impl

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentPlanStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal sealed interface EnvelopeResult {
    data object Failure : EnvelopeResult
    data class Plan(val steps: List<AgentPlanStep>) : EnvelopeResult
    data class Question(val question: String) : EnvelopeResult
    data class Step(val stepId: String?, val artifact: String, val summary: String, val revised: Boolean) : EnvelopeResult
    data object Pass : EnvelopeResult
    data class Revise(val issues: List<String>) : EnvelopeResult
}

internal object AgentEnvelopeParser {
    private const val MAX_ENVELOPE = 16_384
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(raw: String, checkpoint: AgentCheckpoint): EnvelopeResult {
        return try {
            if (raw.codePointCount(0, raw.length) > MAX_ENVELOPE) return EnvelopeResult.Failure
        val envelope = json.decodeFromString<EnvelopeWire>(raw)
        if (envelope.schemaVersion != 1 || envelope.runId != checkpoint.runId || envelope.revision != checkpoint.revision) return EnvelopeResult.Failure
        when (envelope.kind) {
            "PLAN_READY" -> if (envelope.steps.size in 1..3 && envelope.steps.all { it.id.valid(36) && it.title.valid(120) && it.successCriterion.valid(300) }) EnvelopeResult.Plan(envelope.steps.map { AgentPlanStep(it.id!!, it.title!!, it.successCriterion!!) }) else EnvelopeResult.Failure
            "NEEDS_USER" -> if (envelope.question.valid(500) && envelope.expectedInput.valid(240)) EnvelopeResult.Question(envelope.question!!) else EnvelopeResult.Failure
            "STEP_RESULT" -> if (envelope.stepId.valid(36) && envelope.artifact.valid(8_000) && envelope.summary.valid(1_000)) EnvelopeResult.Step(envelope.stepId, envelope.artifact!!, envelope.summary!!, revised = false) else EnvelopeResult.Failure
            "REVISED_RESULT" -> if (envelope.artifact.valid(8_000) && envelope.summary.valid(1_000)) EnvelopeResult.Step(null, envelope.artifact!!, envelope.summary!!, revised = true) else EnvelopeResult.Failure
            "PASS" -> EnvelopeResult.Pass
            "REVISE" -> if (envelope.issues.size in 1..5 && envelope.issues.all { it.valid(400) }) EnvelopeResult.Revise(envelope.issues) else EnvelopeResult.Failure
            else -> EnvelopeResult.Failure
        }
        } catch (_: Throwable) {
            EnvelopeResult.Failure
        }
    }

    private fun String?.valid(limit: Int): Boolean = !isNullOrBlank() && codePointCount(0, length) <= limit
}

@Serializable
private data class EnvelopeWire(
    val schemaVersion: Int,
    val kind: String,
    val runId: String,
    val revision: Long,
    val stepId: String? = null,
    val steps: List<PlanStepWire> = emptyList(),
    val question: String? = null,
    val expectedInput: String? = null,
    val artifact: String? = null,
    val summary: String? = null,
    val issues: List<String> = emptyList(),
)
@Serializable private data class PlanStepWire(val id: String, val title: String, val successCriterion: String)
