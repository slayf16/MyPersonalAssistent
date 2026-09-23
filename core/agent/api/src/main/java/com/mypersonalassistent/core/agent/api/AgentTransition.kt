package com.mypersonalassistent.core.agent.api

/** Pure, side-effect-free gate used before storage, transcript or provider work. */
enum class CanonicalAgentState { IDLE, P_ACTIVE, P_WAIT_ANSWER, P_WAIT_APPROVAL, E_ACTIVE, V_ACTIVE, STALE_PAUSED, FAILED_RETRYABLE, FAILED_TERMINAL, REFUSED, DONE, TERMINATED }
enum class AgentEvent { START, PLANNING_QUESTION, SUBMIT_ANSWER, PLAN_READY, APPROVE_PLAN, CHANGE_PLAN, STEP_ACCEPTED, LAST_STEP_ACCEPTED, VALIDATION_REVISE, VALIDATION_PASS, RETRY, CONTINUE_CURRENT_RULES, NEW_TASK, POLICY_CHANGED }
enum class RejectedTransitionReason { EVENT_NOT_ALLOWED, STALE_RUN, STALE_REVISION, STALE_PLAN, INVARIANT_VERSION_CHANGED, MISSING_APPROVAL, BUDGET_EXHAUSTED, CORRUPT_CHECKPOINT }
sealed interface AgentTransition {
    data class Applied(val next: CanonicalAgentState) : AgentTransition
    data class Rejected(val reason: RejectedTransitionReason, val currentState: CanonicalAgentState, val event: AgentEvent, val expectedActions: Set<AgentEvent>) : AgentTransition
}

object AgentTransitionReducer {
    fun reduce(state: CanonicalAgentState, event: AgentEvent): AgentTransition {
        val next = when (state) {
            CanonicalAgentState.IDLE -> if (event == AgentEvent.START) CanonicalAgentState.P_ACTIVE else null
            CanonicalAgentState.P_ACTIVE -> when (event) { AgentEvent.PLANNING_QUESTION -> CanonicalAgentState.P_WAIT_ANSWER; AgentEvent.PLAN_READY -> CanonicalAgentState.P_WAIT_APPROVAL; AgentEvent.SUBMIT_ANSWER -> CanonicalAgentState.P_ACTIVE; AgentEvent.NEW_TASK -> CanonicalAgentState.TERMINATED; AgentEvent.POLICY_CHANGED -> CanonicalAgentState.STALE_PAUSED; else -> null }
            CanonicalAgentState.P_WAIT_ANSWER -> when (event) { AgentEvent.SUBMIT_ANSWER -> CanonicalAgentState.P_ACTIVE; AgentEvent.POLICY_CHANGED -> CanonicalAgentState.STALE_PAUSED; AgentEvent.NEW_TASK -> CanonicalAgentState.TERMINATED; else -> null }
            CanonicalAgentState.P_WAIT_APPROVAL -> when (event) { AgentEvent.APPROVE_PLAN -> CanonicalAgentState.E_ACTIVE; AgentEvent.CHANGE_PLAN -> CanonicalAgentState.P_ACTIVE; AgentEvent.POLICY_CHANGED -> CanonicalAgentState.STALE_PAUSED; AgentEvent.NEW_TASK -> CanonicalAgentState.TERMINATED; else -> null }
            CanonicalAgentState.E_ACTIVE -> when (event) { AgentEvent.SUBMIT_ANSWER, AgentEvent.STEP_ACCEPTED -> CanonicalAgentState.E_ACTIVE; AgentEvent.LAST_STEP_ACCEPTED -> CanonicalAgentState.V_ACTIVE; AgentEvent.POLICY_CHANGED -> CanonicalAgentState.STALE_PAUSED; AgentEvent.NEW_TASK -> CanonicalAgentState.TERMINATED; else -> null }
            CanonicalAgentState.V_ACTIVE -> when (event) { AgentEvent.SUBMIT_ANSWER -> CanonicalAgentState.V_ACTIVE; AgentEvent.VALIDATION_REVISE -> CanonicalAgentState.E_ACTIVE; AgentEvent.VALIDATION_PASS -> CanonicalAgentState.DONE; AgentEvent.POLICY_CHANGED -> CanonicalAgentState.STALE_PAUSED; AgentEvent.NEW_TASK -> CanonicalAgentState.TERMINATED; else -> null }
            CanonicalAgentState.STALE_PAUSED -> when (event) { AgentEvent.CONTINUE_CURRENT_RULES -> CanonicalAgentState.P_ACTIVE; AgentEvent.NEW_TASK -> CanonicalAgentState.TERMINATED; else -> null }
            CanonicalAgentState.FAILED_RETRYABLE -> when (event) { AgentEvent.RETRY -> CanonicalAgentState.P_ACTIVE; AgentEvent.NEW_TASK -> CanonicalAgentState.TERMINATED; AgentEvent.POLICY_CHANGED -> CanonicalAgentState.STALE_PAUSED; else -> null }
            CanonicalAgentState.FAILED_TERMINAL, CanonicalAgentState.REFUSED, CanonicalAgentState.DONE, CanonicalAgentState.TERMINATED ->
                if (event == AgentEvent.NEW_TASK) CanonicalAgentState.TERMINATED else null
        }
        return next?.let(AgentTransition::Applied) ?: AgentTransition.Rejected(RejectedTransitionReason.EVENT_NOT_ALLOWED, state, event, allowed(state))
    }
    private fun allowed(state: CanonicalAgentState): Set<AgentEvent> = when (state) {
        CanonicalAgentState.IDLE -> setOf(AgentEvent.START)
        CanonicalAgentState.P_ACTIVE -> setOf(AgentEvent.PLANNING_QUESTION, AgentEvent.PLAN_READY, AgentEvent.SUBMIT_ANSWER, AgentEvent.NEW_TASK, AgentEvent.POLICY_CHANGED)
        CanonicalAgentState.P_WAIT_ANSWER -> setOf(AgentEvent.SUBMIT_ANSWER, AgentEvent.POLICY_CHANGED, AgentEvent.NEW_TASK)
        CanonicalAgentState.P_WAIT_APPROVAL -> setOf(AgentEvent.APPROVE_PLAN, AgentEvent.CHANGE_PLAN, AgentEvent.POLICY_CHANGED, AgentEvent.NEW_TASK)
        CanonicalAgentState.E_ACTIVE -> setOf(AgentEvent.SUBMIT_ANSWER, AgentEvent.STEP_ACCEPTED, AgentEvent.LAST_STEP_ACCEPTED, AgentEvent.POLICY_CHANGED, AgentEvent.NEW_TASK)
        CanonicalAgentState.V_ACTIVE -> setOf(AgentEvent.SUBMIT_ANSWER, AgentEvent.VALIDATION_REVISE, AgentEvent.VALIDATION_PASS, AgentEvent.POLICY_CHANGED, AgentEvent.NEW_TASK)
        CanonicalAgentState.STALE_PAUSED -> setOf(AgentEvent.CONTINUE_CURRENT_RULES, AgentEvent.NEW_TASK)
        CanonicalAgentState.FAILED_RETRYABLE -> setOf(AgentEvent.RETRY, AgentEvent.NEW_TASK, AgentEvent.POLICY_CHANGED)
        CanonicalAgentState.FAILED_TERMINAL, CanonicalAgentState.REFUSED, CanonicalAgentState.DONE, CanonicalAgentState.TERMINATED -> setOf(AgentEvent.NEW_TASK)
    }
}
