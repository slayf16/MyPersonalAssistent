package com.mypersonalassistent.core.agent.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTransitionReducerTest {
    @Test fun `plan cannot execute until explicit matching approval event`() {
        assertEquals(AgentTransition.Applied(CanonicalAgentState.P_WAIT_APPROVAL), AgentTransitionReducer.reduce(CanonicalAgentState.P_ACTIVE, AgentEvent.PLAN_READY))
        val rejected = AgentTransitionReducer.reduce(CanonicalAgentState.P_WAIT_APPROVAL, AgentEvent.LAST_STEP_ACCEPTED)
        assertTrue(rejected is AgentTransition.Rejected)
        assertEquals(AgentTransition.Applied(CanonicalAgentState.E_ACTIVE), AgentTransitionReducer.reduce(CanonicalAgentState.P_WAIT_APPROVAL, AgentEvent.APPROVE_PLAN))
    }

    @Test fun `stale run only continues through new current rules`() {
        assertTrue(AgentTransitionReducer.reduce(CanonicalAgentState.STALE_PAUSED, AgentEvent.RESUME) is AgentTransition.Rejected)
        assertEquals(AgentTransition.Applied(CanonicalAgentState.P_ACTIVE), AgentTransitionReducer.reduce(CanonicalAgentState.STALE_PAUSED, AgentEvent.CONTINUE_CURRENT_RULES))
    }
}
