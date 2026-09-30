package com.mypersonalassistent.core.agent.impl

import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentFailureKind
import com.mypersonalassistent.core.agent.api.AgentPhase
import com.mypersonalassistent.core.agent.api.AgentRunInput
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.agent.api.AgentEvent
import com.mypersonalassistent.core.agent.api.MAX_EXTRA_ATTEMPTS_PER_RUN
import com.mypersonalassistent.core.agent.api.ResumeTarget
import com.mypersonalassistent.core.agent.api.AgentOperationToken
import com.mypersonalassistent.core.agent.api.PlanChangeContext
import com.mypersonalassistent.core.history.api.AgentRecovery
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmError
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.llm.api.LlmResult
import com.mypersonalassistent.core.llm.api.LlmToolCall
import com.mypersonalassistent.core.mcp.api.McpToolGateway
import com.mypersonalassistent.core.mcp.api.McpToolDefinition
import com.mypersonalassistent.core.mcp.api.McpToolId
import com.mypersonalassistent.core.mcp.api.McpToolCall
import com.mypersonalassistent.core.mcp.api.McpToolResult
import com.mypersonalassistent.core.mcp.api.McpResult
import com.mypersonalassistent.core.mcp.api.McpError
import com.mypersonalassistent.core.mcp.api.McpPreparedCall
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.core.invariants.api.CollectionRevision
import com.mypersonalassistent.core.invariants.api.InvariantChange
import com.mypersonalassistent.core.invariants.api.GateOutcome
import com.mypersonalassistent.core.invariants.api.SafeInvariantRefusal
import com.mypersonalassistent.core.invariants.api.InvariantGateStage
import com.mypersonalassistent.core.invariants.api.InvariantGuard
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.core.invariants.api.InvariantRule
import com.mypersonalassistent.core.invariants.api.InvariantRuleId
import com.mypersonalassistent.core.invariants.api.InvariantSnapshot
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotId
import com.mypersonalassistent.core.invariants.api.InvariantSnapshotRef
import com.mypersonalassistent.core.invariants.api.InvariantValidationError
import com.mypersonalassistent.core.invariants.api.PrepareMutationResult
import com.mypersonalassistent.core.invariants.api.ConfirmMutationResult
import com.mypersonalassistent.core.invariants.api.InvariantMutation
import com.mypersonalassistent.core.invariants.api.SnapshotResult
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAgentRunEngineTest {
    @Test fun `provider failure metadata survives the engine and recovery wire`() = runBlocking {
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(
            ScriptedLlm { LlmResult.Failure(LlmError.RATE_LIMIT, 429) },
            SimpleComposer,
            store,
            store,
        )

        val result = engine.start(input())

        assertEquals(AgentFailureKind.PROVIDER, result.failure)
        assertEquals(LlmError.RATE_LIMIT, result.checkpoint.providerError)
        assertEquals(429, result.checkpoint.providerHttpStatus)
        engine.persist(input(result.checkpoint))
        val recovered = requireNotNull(engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson))
        assertEquals(LlmError.RATE_LIMIT, recovered.providerError)
        assertEquals(429, recovered.providerHttpStatus)
    }

    @Test fun `interrupting an in flight MCP call records unknown outcome without a provider retry`() = runBlocking {
        val gateway = BlockingGateway()
        val llm = ScriptedLlm { request ->
            check(request.toolExchanges.isEmpty())
            LlmResult.Success("", "tool_calls", listOf(LlmToolCall("call-1", request.tools.single().name, JsonObject(emptyMap()))))
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val proposed = engine.start(input())
        val approval = async { engine.decideMcpCall(input(proposed.checkpoint), proposed.checkpoint.pendingMcpCalls.single().digest, true) }

        gateway.started.receive()
        approval.cancelAndJoin()
        val interrupted = engine.interruptMcp(input(proposed.checkpoint))

        assertTrue(interrupted.checkpoint.mcpSuppressed)
        assertFalse(interrupted.checkpoint.inFlight)
        assertTrue(interrupted.checkpoint.pendingMcpCalls.isEmpty())
        assertEquals("call-1", interrupted.checkpoint.completedMcpCalls.single().toolCallId)
        assertTrue(interrupted.checkpoint.completedMcpCalls.single().isError)
        assertTrue(interrupted.checkpoint.mcpOutcomeUnknown)
        assertEquals(1, llm.calls)
    }

    @Test fun `recovery converts an in flight MCP call to unknown and never leaves it retryable`() = runBlocking {
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, store, store)
        val checkpoint = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), phase = AgentPhase.EXECUTION, runStatus = AgentRunStatus.ACTIVE, inFlight = true,
            pendingMcpCalls = listOf(com.mypersonalassistent.core.agent.api.PendingMcpCall("12345678-1234-1234-1234-123456789012", "tool", "call", "{}", "digest", com.mypersonalassistent.core.agent.api.McpCallStatus.IN_FLIGHT)),
        )
        engine.persist(input(checkpoint))

        val recovered = requireNotNull(engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson))

        assertFalse(recovered.inFlight)
        assertTrue(recovered.mcpSuppressed)
        assertTrue(recovered.pendingMcpCalls.isEmpty())
        assertEquals("call", recovered.completedMcpCalls.single().toolCallId)
        assertTrue(recovered.completedMcpCalls.single().isError)
        assertTrue(recovered.mcpOutcomeUnknown)
    }
    @Test fun `waiting and completed MCP checkpoints survive process recreation exactly`() = runBlocking {
        val waitingStore = RecoveryStore()
        val waitingEngine = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, waitingStore, waitingStore)
        val waiting = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), revision = 2, phase = AgentPhase.EXECUTION,
            runStatus = AgentRunStatus.WAITING_MCP_APPROVAL,
            pendingMcpCalls = listOf(com.mypersonalassistent.core.agent.api.PendingMcpCall("12345678-1234-1234-1234-123456789012", "tool", "waiting-call", "{}", "waiting-digest", com.mypersonalassistent.core.agent.api.McpCallStatus.WAITING_CONFIRMATION)),
        )
        waitingEngine.persist(input(waiting))
        val waitingRecovered = requireNotNull(waitingEngine.decodeCheckpoint(requireNotNull(waitingStore.recovery).checkpointJson))

        assertEquals(waiting.pendingMcpCalls, waitingRecovered.pendingMcpCalls)
        assertEquals(AgentRunStatus.WAITING_MCP_APPROVAL, waitingRecovered.runStatus)

        val completedStore = RecoveryStore()
        val completedEngine = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, completedStore, completedStore)
        val completed = waiting.copy(
            runStatus = AgentRunStatus.ACTIVE,
            pendingMcpCalls = emptyList(),
            completedMcpCalls = listOf(com.mypersonalassistent.core.agent.api.CompletedMcpCall("12345678-1234-1234-1234-123456789012", "tool", "completed-call", "{}", "result", false)),
        )
        completedEngine.persist(input(completed))
        val completedRecovered = requireNotNull(completedEngine.decodeCheckpoint(requireNotNull(completedStore.recovery).checkpointJson))

        assertEquals(completed.completedMcpCalls, completedRecovered.completedMcpCalls)
        assertFalse(completedRecovered.mcpOutcomeUnknown)
    }

    @Test fun `unknown outcome stays visible and a later proposal requires a fresh approval`() = runBlocking {
        val gateway = RecordingGateway()
        val llm = ScriptedLlm { request ->
            assertTrue(request.toolExchanges.single().isError)
            LlmResult.Success("", "tool_calls", listOf(LlmToolCall("fresh-call", request.tools.single().name, JsonObject(emptyMap()))))
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val unknown = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), phase = AgentPhase.EXECUTION, runStatus = AgentRunStatus.ACTIVE,
            plan = listOf(com.mypersonalassistent.core.agent.api.AgentPlanStep("step-one", "Plan", "Done")),
            mcpSuppressed = true,
            completedMcpCalls = listOf(com.mypersonalassistent.core.agent.api.CompletedMcpCall("12345678-1234-1234-1234-123456789012", "lookup", "old-call", "{}", "MCP external action outcome unknown", true)),
            mcpOutcomeUnknown = true,
        )
        engine.persist(input(unknown))
        val enabled = engine.reenableMcp(input(unknown)).checkpoint

        val proposal = engine.answer(input(enabled, listOf(message("task"), message("next message"))))

        assertEquals(AgentRunStatus.WAITING_MCP_APPROVAL, proposal.checkpoint.runStatus)
        assertEquals("fresh-call", proposal.checkpoint.pendingMcpCalls.single().toolCallId)
        assertTrue(proposal.checkpoint.mcpOutcomeUnknown)
        assertEquals(0, gateway.calls)
    }

    @Test fun `sixth approved MCP call is rejected before the gateway`() = runBlocking {
        val gateway = RecordingGateway()
        val engine = DefaultAgentRunEngine(ScriptedLlm(::questionFor), SimpleComposer, RecoveryStore(), RecoveryStore(), mcp = gateway)
        val waiting = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.WAITING_MCP_APPROVAL, approvedMcpCalls = 5,
            pendingMcpCalls = listOf(com.mypersonalassistent.core.agent.api.PendingMcpCall("12345678-1234-1234-1234-123456789012", "lookup", "call-6", "{}", "digest-6", com.mypersonalassistent.core.agent.api.McpCallStatus.WAITING_CONFIRMATION)),
        )

        val result = engine.decideMcpCall(input(waiting), "digest-6", true)

        assertTrue(result.checkpoint.mcpSuppressed)
        assertTrue(result.checkpoint.pendingMcpCalls.isEmpty())
        assertEquals(0, gateway.calls)
    }

    @Test fun `tool batch that exceeds remaining run budget falls back without partial execution`() = runBlocking {
        val gateway = RecordingGateway()
        val llm = ScriptedLlm { request ->
            if (request.toolExchanges.isEmpty()) {
                LlmResult.Success("", "tool_calls", listOf(
                    LlmToolCall("call-5", request.tools.single().name, JsonObject(emptyMap())),
                    LlmToolCall("call-6", request.tools.single().name, JsonObject(emptyMap())),
                ))
            } else {
                assertEquals(2, request.toolExchanges.size)
                questionFor(request)
            }
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val checkpoint = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.ACTIVE, approvedMcpCalls = 4,
        )
        engine.persist(input(checkpoint))

        val result = engine.answer(input(checkpoint, listOf(message("task"), message("continue"))))

        assertEquals(0, gateway.calls)
        assertTrue(result.checkpoint.mcpSuppressed)
        assertEquals(2, result.checkpoint.completedMcpCalls.size)
        assertFalse(result.checkpoint.inFlight)
    }
    @Test fun `two proposed tool calls require separate approvals and a repeated approval cannot duplicate call`() = runBlocking {
        val gateway = RecordingGateway()
        val llm = ScriptedLlm { request ->
            if (request.toolExchanges.isEmpty()) LlmResult.Success("", "tool_calls", listOf(
                LlmToolCall("call-1", request.tools.single().name, JsonObject(emptyMap())),
                LlmToolCall("call-2", request.tools.single().name, JsonObject(emptyMap())),
            )) else planReady(request)
        }
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, RecoveryStore(), RecoveryStore(), mcp = gateway)
        val proposed = engine.start(input())
        val first = proposed.checkpoint.pendingMcpCalls[0]
        val initiallySecond = proposed.checkpoint.pendingMcpCalls[1]
        val afterFirst = engine.decideMcpCall(input(proposed.checkpoint), first.digest, true)
        val duplicate = engine.decideMcpCall(input(afterFirst.checkpoint), first.digest, true)
        val second = afterFirst.checkpoint.pendingMcpCalls.single { it.status == com.mypersonalassistent.core.agent.api.McpCallStatus.WAITING_CONFIRMATION }

        assertTrue(first.digest != initiallySecond.digest)
        assertEquals(AgentRunStatus.WAITING_MCP_APPROVAL, afterFirst.checkpoint.runStatus)
        assertEquals("call-2", second.toolCallId)
        assertEquals(1, gateway.calls)
        assertEquals(afterFirst.checkpoint, duplicate.checkpoint)
        assertEquals(1, gateway.calls)
    }
    @Test fun `credential-equivalent tool argument never reaches checkpoint or display`() = runBlocking {
        val secret = "configured-token-value"
        val gateway = SensitiveGateway()
        val store = RecoveryStore()
        val llm = ScriptedLlm { request ->
            if (request.toolExchanges.isEmpty()) {
                LlmResult.Success("", "tool_calls", listOf(LlmToolCall(
                    "call-sensitive",
                    request.tools.single().name,
                    buildJsonObject { put("nested", buildJsonObject { put("value", secret) }) },
                )))
            } else questionFor(request)
        }

        val result = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway).start(input())

        assertEquals(0, gateway.calls)
        assertTrue(result.checkpoint.pendingMcpCalls.isEmpty())
        assertTrue(result.checkpoint.mcpSuppressed)
        assertFalse(requireNotNull(store.recovery).checkpointJson.contains(secret))
        assertFalse(result.checkpoint.completedMcpCalls.any { it.canonicalArguments.contains(secret) })
    }

    @Test fun `mixed text and structured tool result survives checkpoint and reaches provider`() = runBlocking {
        val structured = buildJsonObject { put("temperature", 21); put("unit", "C"); put("boundedPayload", "x".repeat(20_000)) }
        val gateway = StructuredGateway(structured)
        var requestAfterTool: LlmRequest? = null
        var afterToolTurns = 0
        val llm = ScriptedLlm { request ->
            if (request.toolExchanges.isEmpty()) {
                LlmResult.Success("", "tool_calls", listOf(LlmToolCall("call-structured", request.tools.single().name, JsonObject(emptyMap()))))
            } else {
                if (afterToolTurns++ == 0) requestAfterTool = request
                when (afterToolTurns) { 1 -> planReady(request); 2 -> stepResult(request); else -> pass(request) }
            }
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val proposed = engine.start(input())

        val result = engine.decideMcpCall(input(proposed.checkpoint), proposed.checkpoint.pendingMcpCalls.single().digest, true)

        assertEquals("forecast text", requestAfterTool!!.toolExchanges.single().result)
        assertEquals(structured, requestAfterTool!!.toolExchanges.single().structuredContent)
        assertEquals(structured, result.checkpoint.completedMcpCalls.single().structuredContent)
        val decoded = engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson)
        assertEquals(structured, decoded!!.completedMcpCalls.single().structuredContent)
    }
    @Test fun `tool proposal persists waiting queue without gateway call`() = runBlocking {
        val gateway = RecordingGateway()
        val llm = ScriptedLlm { request ->
            assertEquals(1, request.tools.size)
            LlmResult.Success("", "tool_calls", listOf(LlmToolCall("call-1", request.tools.single().name, JsonObject(emptyMap()))))
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val result = engine.start(input())
        assertEquals(AgentRunStatus.WAITING_MCP_APPROVAL, result.checkpoint.runStatus)
        assertEquals(1, result.checkpoint.pendingMcpCalls.size)
        assertEquals(0, gateway.calls)
        val recovered = requireNotNull(engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson))
        assertEquals(result.checkpoint.pendingMcpCalls, recovered.pendingMcpCalls)
        assertEquals(AgentRunStatus.WAITING_MCP_APPROVAL, recovered.runStatus)
        assertEquals(result.checkpoint, engine.checkpoint.value)
    }
    @Test fun `same tool name from two servers has distinct reversible provider names`() = runBlocking {
        val gateway = CollisionGateway()
        var exposedNames = emptyList<String>()
        val llm = ScriptedLlm { request ->
            exposedNames = request.tools.map { it.name }
            LlmResult.Success("", "tool_calls", listOf(LlmToolCall("call-second", request.tools[1].name, JsonObject(emptyMap()))))
        }

        val result = DefaultAgentRunEngine(llm, SimpleComposer, RecoveryStore(), RecoveryStore(), mcp = gateway).start(input())

        assertEquals(2, exposedNames.distinct().size)
        assertTrue(exposedNames.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        assertTrue(exposedNames.none { it.contains("unsafe raw", ignoreCase = true) || it.contains("lookup") })
        assertEquals(AgentRunStatus.WAITING_MCP_APPROVAL, result.checkpoint.runStatus)
        assertEquals("87654321-4321-4321-4321-210987654321", result.checkpoint.pendingMcpCalls.single().serverId)
        assertEquals("unsafe raw lookup / one", result.checkpoint.pendingMcpCalls.single().toolName)
        assertEquals(0, gateway.calls)
    }

    @Test fun `typed MCP enable request preserves suppression until explicit UI action`() = runBlocking {
        val gateway = RecordingGateway()
        val llm = ScriptedLlm { request ->
            assertTrue(request.tools.isEmpty())
            val command = request.messages.last().text
            val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
            val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
            success("""{"schemaVersion":1,"kind":"REQUEST_MCP_ENABLE","runId":"$runId","revision":$revision}""")
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val suppressed = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.WAITING_USER, mcpSuppressed = true,
        )
        engine.persist(input(suppressed))

        val requested = engine.answer(input(suppressed, listOf(message("task"), message("use MCP again"))))

        assertEquals(AgentRunStatus.WAITING_USER, requested.checkpoint.runStatus)
        assertTrue(requested.checkpoint.mcpSuppressed)
        assertTrue(requested.checkpoint.expectedAction.contains("Включить MCP"))
        assertEquals(0, gateway.calls)

        val enabled = engine.reenableMcp(input(requested.checkpoint)).checkpoint
        assertFalse(enabled.mcpSuppressed)
        assertEquals(0, gateway.calls)
    }
    @Test fun `approved tool call is sent back with its call id before next provider turn`() = runBlocking {
        val gateway = RecordingGateway()
        var nextRequest: LlmRequest? = null
        var turnsAfterTool = 0
        val llm = ScriptedLlm { request ->
            if (request.toolExchanges.isEmpty()) {
                LlmResult.Success("", "tool_calls", listOf(LlmToolCall("call-1", request.tools.single().name, JsonObject(emptyMap()))))
            } else {
                if (turnsAfterTool++ == 0) nextRequest = request
                when (turnsAfterTool) {
                    1 -> planReady(request)
                    2 -> stepResult(request)
                    else -> pass(request)
                }
            }
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val proposed = engine.start(input())

        val result = engine.decideMcpCall(input(proposed.checkpoint), proposed.checkpoint.pendingMcpCalls.single().digest, true)

        assertEquals(1, gateway.calls)
        assertEquals("call-1", nextRequest!!.toolExchanges.single().call.id)
        assertEquals("ok", nextRequest!!.toolExchanges.single().result)
        assertEquals(AgentRunStatus.COMPLETED, result.checkpoint.runStatus)
    }
    @Test fun `denied queued calls suppress MCP and continue without an external call`() = runBlocking {
        val gateway = RecordingGateway()
        var nextRequest: LlmRequest? = null
        val llm = ScriptedLlm { request ->
            if (request.toolExchanges.isEmpty()) {
                LlmResult.Success("", "tool_calls", listOf(LlmToolCall("call-1", request.tools.single().name, JsonObject(emptyMap()))))
            } else {
                nextRequest = request
                planReady(request)
            }
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val proposed = engine.start(input())

        val result = engine.decideMcpCall(input(proposed.checkpoint), proposed.checkpoint.pendingMcpCalls.single().digest, false)

        assertEquals(0, gateway.calls)
        assertTrue(result.checkpoint.mcpSuppressed)
        assertTrue(nextRequest!!.tools.isEmpty())
        assertTrue(nextRequest!!.toolExchanges.single().isError)
    }

    @Test fun `five denied calls do not consume approved call budget or overflow transcript`() = runBlocking {
        val gateway = RecordingGateway()
        val llm = ScriptedLlm { request ->
            if (request.tools.isEmpty()) {
                val command = request.messages.last().text
                val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
                val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
                success("""{"schemaVersion":1,"kind":"REQUEST_MCP_ENABLE","runId":"$runId","revision":$revision}""")
            } else if (request.toolExchanges.size == 5) {
                LlmResult.Success("", "tool_calls", listOf(LlmToolCall("approved-1", request.tools.single().name, JsonObject(emptyMap()))))
            } else {
                planReady(request)
            }
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, mcp = gateway)
        val firstProposal = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.WAITING_MCP_APPROVAL,
            pendingMcpCalls = (1..5).map { index ->
                com.mypersonalassistent.core.agent.api.PendingMcpCall(
                    "12345678-1234-1234-1234-123456789012", "lookup", "denied-$index", "{}", "digest-$index",
                    com.mypersonalassistent.core.agent.api.McpCallStatus.WAITING_CONFIRMATION,
                )
            },
        )
        engine.persist(input(firstProposal))

        val denied = engine.decideMcpCall(input(firstProposal), firstProposal.pendingMcpCalls.first().digest, false)
        val enabled = engine.reenableMcp(input(denied.checkpoint)).checkpoint
        val secondProposal = engine.answer(input(enabled, listOf(message("task"), message("use MCP again"))))
        assertEquals(AgentRunStatus.WAITING_MCP_APPROVAL, secondProposal.checkpoint.runStatus)
        val approved = engine.decideMcpCall(input(secondProposal.checkpoint), secondProposal.checkpoint.pendingMcpCalls.single().digest, true)

        assertEquals(1, gateway.calls)
        assertEquals(1, approved.checkpoint.approvedMcpCalls)
        assertEquals(6, approved.checkpoint.completedMcpCalls.size)
        assertEquals(6, requireNotNull(engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson)).completedMcpCalls.size)
    }
    @Test fun `valid plan step and pass publish only validated final result`() = runBlocking {
        val llm = ScriptedLlm { request ->
            val command = request.messages.last().text
            val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
            val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
            when {
                command.contains("PLAN_READY") -> success("""{"schemaVersion":1,"kind":"PLAN_READY","runId":"$runId","revision":$revision,"steps":[{"id":"step-one","title":"Plan","successCriterion":"Done"}]}""")
                command.contains("STEP_RESULT") -> success("""{"schemaVersion":1,"kind":"STEP_RESULT","runId":"$runId","revision":$revision,"stepId":"step-one","artifact":"final candidate","summary":"completed"}""")
                else -> success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
            }
        }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store, clock = { 10L })

        val result = engine.start(input())

        assertEquals(3, llm.calls)
        assertEquals(AgentPhase.DONE, result.checkpoint.phase)
        assertEquals(AgentRunStatus.COMPLETED, result.checkpoint.runStatus)
        assertEquals("final candidate", result.finalResult)
        assertEquals(null, result.visibleQuestion)
        assertNotNull(store.recovery)
    }

    @Test fun `second planning question is terminal and never makes a third call`() = runBlocking {
        val llm = ScriptedLlm { request -> questionFor(request) }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)
        val first = engine.start(input())
        val second = engine.answer(input(first.checkpoint, listOf(message("task"), message("answer"))))

        assertEquals(2, llm.calls)
        assertEquals(AgentRunStatus.FAILED, second.checkpoint.runStatus)
        assertEquals(AgentFailureKind.WORKFLOW, second.failure)
        assertFalse(second.checkpoint.retryAllowed)
    }

    @Test fun `schema failure is retryable only within extra attempt budget`() = runBlocking {
        val llm = ScriptedLlm { request -> success("not json") }
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)
        var result = engine.start(input())
        repeat(MAX_EXTRA_ATTEMPTS_PER_RUN) { result = engine.retry(input(result.checkpoint)) }

        assertEquals(3, llm.calls)
        assertEquals(AgentRunStatus.FAILED, result.checkpoint.runStatus)
        assertFalse(result.checkpoint.retryAllowed)
        assertEquals(AgentFailureKind.SCHEMA, result.failure)
    }

    @Test fun `recovery decoder rejects corrupt payload without leaking payload`() {
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm { error("unused") }, SimpleComposer, store, store)
        assertEquals(null, engine.decodeCheckpoint("{untrusted raw response}"))
    }

    @Test fun `interruption normalization preserves waiting user without provider call`() = runBlocking {
        val llm = ScriptedLlm(::questionFor)
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)
        val waiting = engine.start(input())

        val normalized = engine.normalizeInterruptedForRecovery(input(waiting.checkpoint))

        assertEquals(1, llm.calls)
        assertEquals(AgentRunStatus.WAITING_USER, normalized.checkpoint.runStatus)
        assertEquals(waiting.checkpoint.runId, normalized.checkpoint.runId)
        assertEquals(waiting.checkpoint.providerCallsUsed, normalized.checkpoint.providerCallsUsed)
        assertEquals(1, llm.calls)
    }

    @Test fun `local context rejection occurs before provider call and keeps provider budget`() = runBlocking {
        val llm = ScriptedLlm { error("provider must not run") }
        val store = RecoveryStore()
        val rejectingComposer = object : com.mypersonalassistent.core.agent.api.AgentRequestComposer {
            override suspend fun compose(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory?) = LlmRequest(emptyList())
            override suspend fun composeForTask(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory, checkpointContext: String, phaseInstruction: String): LlmRequest =
                throw IllegalArgumentException("bounded context")
        }
        val result = DefaultAgentRunEngine(llm, rejectingComposer, store, store).start(input())

        assertEquals(0, llm.calls)
        assertEquals(AgentFailureKind.CONTEXT, result.failure)
        assertEquals(0, result.checkpoint.providerCallsUsed)
    }

    @Test fun `engine publishes each persisted provider checkpoint for progress`() = runBlocking {
        lateinit var engine: DefaultAgentRunEngine
        val observed = mutableListOf<AgentCheckpoint>()
        val llm = ScriptedLlm { request ->
            observed += requireNotNull(engine.checkpoint.value)
            val command = request.messages.last().text
            val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
            val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
            when {
                command.contains("PLAN_READY") -> success("""{"schemaVersion":1,"kind":"PLAN_READY","runId":"$runId","revision":$revision,"steps":[{"id":"step-one","title":"Plan","successCriterion":"Done"}]}""")
                command.contains("STEP_RESULT") -> success("""{"schemaVersion":1,"kind":"STEP_RESULT","runId":"$runId","revision":$revision,"stepId":"step-one","artifact":"final candidate","summary":"completed"}""")
                else -> success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
            }
        }
        val store = RecoveryStore()
        engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)

        engine.start(input())

        assertEquals(listOf(AgentPhase.PLANNING, AgentPhase.EXECUTION, AgentPhase.VALIDATION), observed.map { it.phase })
        assertEquals(listOf(1, 2, 3), observed.map { it.providerCallsUsed })
        assertEquals(AgentRunStatus.COMPLETED, engine.checkpoint.value?.runStatus)
    }

    @Test fun `interruption normalization after cancelled call keeps identity and resumes only on next message`() = runBlocking {
        val llm = BlockingLlm()
        val store = RecoveryStore()
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, store, store)

        val firstCall = async { engine.start(input()) }
        assertEquals(1, llm.started.receive())
        val firstPersisted = requireNotNull(engine.checkpoint.value)
        assertEquals(1, firstPersisted.providerCallsUsed)
        firstCall.cancelAndJoin()

        val firstInterrupted = engine.normalizeInterruptedForRecovery(input(firstPersisted)).checkpoint
        assertEquals(firstPersisted.runId, firstInterrupted.runId)
        assertEquals(1, firstInterrupted.providerCallsUsed)
        assertEquals(AgentRunStatus.ACTIVE, firstInterrupted.runStatus)
        assertFalse(firstInterrupted.inFlight)

        val resumedCall = async { engine.answer(input(firstInterrupted)) }
        assertEquals(2, llm.started.receive())
        val resumedPersisted = requireNotNull(engine.checkpoint.value)
        assertEquals(firstInterrupted.runId, resumedPersisted.runId)
        assertEquals(2, resumedPersisted.providerCallsUsed)
        assertEquals(1, resumedPersisted.extraAttemptsUsed)
        resumedCall.cancelAndJoin()

        val secondInterrupted = engine.normalizeInterruptedForRecovery(input(resumedPersisted)).checkpoint
        assertEquals(firstInterrupted.runId, secondInterrupted.runId)
        assertEquals(2, secondInterrupted.providerCallsUsed)
        assertEquals(1, secondInterrupted.extraAttemptsUsed)
        assertEquals(AgentRunStatus.ACTIVE, secondInterrupted.runStatus)
        assertFalse(secondInterrupted.inFlight)
        assertEquals(secondInterrupted, engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson))
    }

    @Test fun `persist registers durable nonterminal run against immutable snapshot`() = runBlocking {
        val repository = InvariantStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm(::questionFor), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = repository, guard = RecordingGuard())

        engine.start(input())

        assertEquals(1, repository.tracked.size)
        val tracked = repository.tracked.single()
        assertEquals("chat", tracked.chatId)
        assertEquals(repository.snapshot.ref(), tracked.snapshotRef)
        assertTrue(tracked.isNonterminal)
        assertTrue(tracked.isActive)
    }

    @Test fun `step and final gates refuse content before it becomes visible`() = runBlocking {
        val stepRepository = InvariantStore()
        val stepGuard = RecordingGuard(reject = InvariantGateStage.STEP)
        val stepEngine = DefaultAgentRunEngine(ScriptedLlm(::planStepPass), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = stepRepository, guard = stepGuard)
        val planned = stepEngine.start(input())
        val stepRejected = stepEngine.approvePlan(input(planned.checkpoint), planned.checkpoint.revision)

        assertEquals(AgentRunStatus.REFUSED, stepRejected.checkpoint.runStatus)
        assertTrue(InvariantGateStage.STEP in stepGuard.stages)
        assertEquals(null, stepRejected.finalResult)

        val finalRepository = InvariantStore()
        val finalGuard = RecordingGuard(reject = InvariantGateStage.FINAL)
        val finalEngine = DefaultAgentRunEngine(ScriptedLlm(::planStepPass), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = finalRepository, guard = finalGuard)
        val finalPlan = finalEngine.start(input())
        val finalRejected = finalEngine.approvePlan(input(finalPlan.checkpoint), finalPlan.checkpoint.revision)

        assertEquals(AgentRunStatus.REFUSED, finalRejected.checkpoint.runStatus)
        assertTrue(InvariantGateStage.FINAL in finalGuard.stages)
        assertEquals(null, finalRejected.finalResult)
    }

    @Test fun `checkpoint v2 round trips identity and changed policy replans from fresh snapshot`() = runBlocking {
        val store = RecoveryStore()
        val repository = InvariantStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, store, store, invariants = repository, guard = RecordingGuard())
        val checkpoint = AgentCheckpoint(
            chatId = "chat", runId = UUID.randomUUID().toString(), revision = 8,
            phase = AgentPhase.PLANNING, runStatus = AgentRunStatus.WAITING_USER,
            invariantSnapshot = repository.snapshot.ref(), approvedPlanRevision = 7, approvedAt = 6,
            staleTarget = ResumeTarget.REPEAT_EXECUTION_CALL, operationToken = AgentOperationToken(1),
        )
        engine.persist(input(checkpoint, listOf(message("task"), message("answer"))))
        val decoded = requireNotNull(engine.decodeCheckpoint(requireNotNull(store.recovery).checkpointJson))
        assertEquals(checkpoint.invariantSnapshot, decoded.invariantSnapshot)
        assertEquals(7L, decoded.approvedPlanRevision)
        assertEquals(6L, decoded.approvedAt)
        assertEquals(ResumeTarget.REPEAT_EXECUTION_CALL, decoded.staleTarget)
        assertEquals(AgentOperationToken(1), decoded.operationToken)

        repository.revision = CollectionRevision(2)
        repository.snapshot = repository.snapshot.copy(id = InvariantSnapshotId("snapshot-2"), collectionRevision = repository.revision, contentDigest = "digest-2")
        val replanned = engine.answer(input(checkpoint, listOf(message("task"), message("answer"))))
        assertEquals(repository.snapshot.ref(), replanned.checkpoint.invariantSnapshot)
        assertEquals(AgentRunStatus.WAITING_APPROVAL, replanned.checkpoint.runStatus)
    }

    @Test fun `provider event outside canonical state returns typed rejection`() = runBlocking {
        val llm = ScriptedLlm { request ->
            val command = request.messages.last().text
            val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
            val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
            success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
        }
        val result = DefaultAgentRunEngine(llm, SimpleComposer, RecoveryStore(), RecoveryStore()).start(input())

        assertEquals(null, result.failure)
        assertEquals(AgentEvent.VALIDATION_PASS, result.rejectedTransition?.event)
        assertEquals(AgentRunStatus.ACTIVE, result.checkpoint.runStatus)
        assertEquals(1, result.checkpoint.providerCallsUsed)
    }

    @Test fun `plan change revalidates and clears stale validation operation state`() = runBlocking {
        val repository = InvariantStore()
        val engine = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = repository, guard = RecordingGuard())
        val stale = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), revision = 4, phase = AgentPhase.PLANNING,
            runStatus = AgentRunStatus.WAITING_APPROVAL, invariantSnapshot = repository.snapshot.ref(),
            revisionPending = true, revisionIssues = listOf("old issue"), candidateResult = "old candidate", operationToken = AgentOperationToken(1),
        )
        repository.revision = CollectionRevision(2)
        repository.snapshot = repository.snapshot.copy(id = InvariantSnapshotId("snapshot-2"), collectionRevision = repository.revision, contentDigest = "digest-2")

        val result = engine.requestPlanChanges(input(stale), PlanChangeContext(stale.revision, "change"))

        assertEquals(AgentRunStatus.WAITING_APPROVAL, result.checkpoint.runStatus)
        assertEquals(repository.snapshot.ref(), result.checkpoint.invariantSnapshot)
        assertFalse(result.checkpoint.revisionPending)
        assertTrue(result.checkpoint.revisionIssues.isEmpty())
        assertEquals("", result.checkpoint.candidateResult)
        assertTrue(result.checkpoint.operationToken.value >= 1)
    }

    @Test fun `index write failure is fail closed before recovery and provider`() = runBlocking {
        val repository = InvariantStore(trackSucceeds = false)
        val recovery = RecoveryStore()
        val llm = ScriptedLlm { error("provider must not execute") }
        val result = DefaultAgentRunEngine(llm, SimpleComposer, recovery, recovery, invariants = repository, guard = RecordingGuard()).start(input())

        assertEquals(0, llm.calls)
        assertEquals(null, recovery.recovery)
        assertEquals(AgentFailureKind.PROVIDER, result.failure)
    }

    @Test fun `continue current rules validates snapshot and clears obsolete revision operation`() = runBlocking {
        val repository = InvariantStore()
        val stale = AgentCheckpoint(
            "chat", UUID.randomUUID().toString(), revision = 3, phase = AgentPhase.VALIDATION,
            runStatus = AgentRunStatus.STALE_PAUSED, invariantSnapshot = repository.snapshot.ref(),
            revisionPending = true, revisionIssues = listOf("obsolete"), candidateResult = "obsolete candidate", operationToken = AgentOperationToken(1),
        )
        repository.revision = CollectionRevision(2)
        repository.snapshot = repository.snapshot.copy(id = InvariantSnapshotId("snapshot-2"), collectionRevision = repository.revision, contentDigest = "digest-2")
        val result = DefaultAgentRunEngine(ScriptedLlm(::planReady), SimpleComposer, RecoveryStore(), RecoveryStore(), invariants = repository, guard = RecordingGuard())
            .continueWithCurrentRules(input(stale))

        assertEquals(AgentRunStatus.WAITING_APPROVAL, result.checkpoint.runStatus)
        assertFalse(result.checkpoint.revisionPending)
        assertTrue(result.checkpoint.revisionIssues.isEmpty())
        assertEquals("", result.checkpoint.candidateResult)
        assertTrue(result.checkpoint.operationToken.value >= 1)
        assertEquals(repository.snapshot.ref(), result.checkpoint.invariantSnapshot)
    }

    @Test fun `rejected second start leaves existing checkpoint and storage untouched`() = runBlocking {
        val repository = InvariantStore()
        val recovery = RecoveryStore()
        val llm = ScriptedLlm(::questionFor)
        val engine = DefaultAgentRunEngine(llm, SimpleComposer, recovery, recovery, invariants = repository, guard = RecordingGuard())
        val first = engine.start(input())
        val persisted = recovery.recovery
        val second = engine.start(input())

        assertEquals(1, llm.calls)
        assertEquals(first.checkpoint, second.checkpoint)
        assertEquals(AgentEvent.START, second.rejectedTransition?.event)
        assertEquals(persisted, recovery.recovery)
    }

    private fun input(checkpoint: AgentCheckpoint? = null, messages: List<ChatMessage> = listOf(message("task"))) =
        AgentRunInput("chat", messages, TaskMemory("chat", goal = "goal"), checkpoint)
    private fun message(text: String) = ChatMessage(text, MessageRole.USER, text)
    private fun success(text: String) = LlmResult.Success(text, null)
    private fun questionFor(request: LlmRequest): LlmResult.Success {
        val command = request.messages.last().text
        val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
        val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
        return success("""{"schemaVersion":1,"kind":"NEEDS_USER","runId":"$runId","revision":$revision,"question":"Need detail","expectedInput":"A short detail"}""")
    }
    private fun planReady(request: LlmRequest): LlmResult.Success {
        val command = request.messages.last().text
        val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
        val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
        return success("""{"schemaVersion":1,"kind":"PLAN_READY","runId":"$runId","revision":$revision,"steps":[{"id":"step-one","title":"Plan","successCriterion":"Done"}]}""")
    }
    private fun stepResult(request: LlmRequest): LlmResult.Success {
        val command = request.messages.last().text
        val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
        val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
        return success("""{"schemaVersion":1,"kind":"STEP_RESULT","runId":"$runId","revision":$revision,"stepId":"step-one","artifact":"final candidate","summary":"completed"}""")
    }
    private fun pass(request: LlmRequest): LlmResult.Success {
        val command = request.messages.last().text
        val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
        val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
        return success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
    }
    private fun planStepPass(request: LlmRequest): LlmResult.Success {
        val command = request.messages.last().text
        val runId = Regex("runId=([^, ]+)").find(command)!!.groupValues[1]
        val revision = Regex("revision=(\\d+)").find(command)!!.groupValues[1]
        return when {
            command.contains("PLAN_READY") -> planReady(request)
            command.contains("STEP_RESULT") -> success("""{"schemaVersion":1,"kind":"STEP_RESULT","runId":"$runId","revision":$revision,"stepId":"step-one","artifact":"final candidate","summary":"completed"}""")
            else -> success("""{"schemaVersion":1,"kind":"PASS","runId":"$runId","revision":$revision}""")
        }
    }

    private class ScriptedLlm(private val script: (LlmRequest) -> LlmResult) : Llm {
        var calls = 0
        override suspend fun execute(request: LlmRequest): LlmResult { calls++; return script(request) }
    }
    private class RecordingGateway : McpToolGateway {
        var calls = 0
        override suspend fun tools(chatId: String) = McpResult.Success(listOf(McpToolDefinition(McpToolId("12345678-1234-1234-1234-123456789012", "lookup"), "Lookup", JsonObject(mapOf("type" to JsonPrimitive("object"))))))
        override suspend fun call(call: McpToolCall): McpResult<McpToolResult> { calls++; return McpResult.Success(McpToolResult(call.callId, "ok")) }
    }
    private class SensitiveGateway : McpToolGateway {
        var calls = 0
        override suspend fun tools(chatId: String) = McpResult.Success(listOf(McpToolDefinition(McpToolId("12345678-1234-1234-1234-123456789012", "lookup"), "Lookup", JsonObject(mapOf("type" to JsonPrimitive("object"))))))
        override suspend fun prepareCall(call: McpToolCall) = McpResult.Failure(McpError.SENSITIVE_ARGUMENT)
        override suspend fun call(call: McpToolCall): McpResult<McpToolResult> { calls++; return McpResult.Success(McpToolResult(call.callId, "must not run")) }
    }
    private class StructuredGateway(private val structured: JsonObject) : McpToolGateway {
        override suspend fun tools(chatId: String) = McpResult.Success(listOf(McpToolDefinition(McpToolId("12345678-1234-1234-1234-123456789012", "weather"), "Weather", JsonObject(mapOf("type" to JsonPrimitive("object"))))))
        override suspend fun prepareCall(call: McpToolCall) = McpResult.Success(McpPreparedCall("{}"))
        override suspend fun call(call: McpToolCall) = McpResult.Success(McpToolResult(call.callId, "forecast text", structured))
    }
    private class CollisionGateway : McpToolGateway {
        var calls = 0
        override suspend fun tools(chatId: String) = McpResult.Success(listOf(
            McpToolDefinition(McpToolId("12345678-1234-1234-1234-123456789012", "unsafe raw lookup / one"), "First", JsonObject(mapOf("type" to JsonPrimitive("object")))),
            McpToolDefinition(McpToolId("87654321-4321-4321-4321-210987654321", "unsafe raw lookup / one"), "Second", JsonObject(mapOf("type" to JsonPrimitive("object")))),
        ))
        override suspend fun call(call: McpToolCall): McpResult<McpToolResult> {
            calls++
            return McpResult.Success(McpToolResult(call.callId, "ok"))
        }
    }
    private class BlockingGateway : McpToolGateway {
        val started = Channel<McpToolCall>(Channel.UNLIMITED)
        override suspend fun tools(chatId: String) = McpResult.Success(listOf(McpToolDefinition(McpToolId("12345678-1234-1234-1234-123456789012", "lookup"), "Lookup", JsonObject(mapOf("type" to JsonPrimitive("object"))))))
        override suspend fun call(call: McpToolCall): McpResult<McpToolResult> {
            started.send(call)
            awaitCancellation()
        }
    }
    private class BlockingLlm : Llm {
        val started = Channel<Int>(Channel.UNLIMITED)
        private var calls = 0
        override suspend fun execute(request: LlmRequest): LlmResult {
            started.send(++calls)
            awaitCancellation()
        }
    }
    private object SimpleComposer : com.mypersonalassistent.core.agent.api.AgentRequestComposer {
        override suspend fun compose(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory?) = LlmRequest(emptyList())
        override suspend fun composeForTask(chatId: String, messages: List<ChatMessage>, taskMemory: TaskMemory, checkpointContext: String, phaseInstruction: String) =
            LlmRequest(listOf(com.mypersonalassistent.core.llm.api.LlmMessage(com.mypersonalassistent.core.llm.api.LlmRole.SYSTEM, phaseInstruction)))
    }
    private class RecoveryStore : HistoryRepository, AgentRecoveryRepository {
        var recovery: AgentRecovery? = null
        override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow()
        override suspend fun read(id: String): ChatSnapshot? = null
        override suspend fun save(snapshot: ChatSnapshot) = true
        override suspend fun save(snapshot: ChatSnapshot, taskMemory: TaskMemory) = true
        override fun observeRecovery(): Flow<List<com.mypersonalassistent.core.history.api.AgentRecoverySummary>> = emptyFlow()
        override suspend fun readCheckpoint(chatId: String): String? = null
        override suspend fun readRecovery(chatId: String) = recovery
        override suspend fun writeRecovery(recovery: AgentRecovery): Boolean { this.recovery = recovery; return true }
        override suspend fun promoteRecovery(recovery: AgentRecovery) = true
        override suspend fun discardRecovery(chatId: String): Boolean { recovery = null; return true }
    }
    private data class TrackedRun(val chatId: String, val runId: String, val snapshotRef: InvariantSnapshotRef, val isNonterminal: Boolean, val isActive: Boolean)
    private class InvariantStore(private val trackSucceeds: Boolean = true) : InvariantRepository {
        var revision = CollectionRevision(1)
        var snapshot = InvariantSnapshot(InvariantSnapshotId("snapshot-1"), revision, emptyList(), "digest-1", 1)
        val tracked = mutableListOf<TrackedRun>()
        override val rules: Flow<List<InvariantRule>> = emptyFlow()
        override val changes: Flow<InvariantChange> = emptyFlow()
        override suspend fun collectionRevision() = revision
        override suspend fun read(id: InvariantRuleId): InvariantRule? = null
        override suspend fun prepareMutation(mutation: InvariantMutation): PrepareMutationResult = PrepareMutationResult.Rejected(InvariantValidationError.StorageUnavailable)
        override suspend fun confirmMutation(confirmationId: String): ConfirmMutationResult = ConfirmMutationResult.Rejected(InvariantValidationError.StorageUnavailable)
        override suspend fun createSnapshot(): SnapshotResult = SnapshotResult.Available(snapshot)
        override suspend fun readSnapshot(ref: InvariantSnapshotRef): SnapshotResult = if (ref.id == snapshot.id || ref.id.value == "snapshot-1") SnapshotResult.Available(if (ref.id == snapshot.id) snapshot else snapshot.copy(id = ref.id, collectionRevision = ref.collectionRevision, contentDigest = ref.contentDigest)) else SnapshotResult.Unavailable
        override suspend fun trackRun(chatId: String, runId: String, snapshotRef: InvariantSnapshotRef, isNonterminal: Boolean, isActive: Boolean, isStale: Boolean, staleTarget: String?): Boolean { if (trackSucceeds) tracked += TrackedRun(chatId, runId, snapshotRef, isNonterminal, isActive); return trackSucceeds }
    }
    private class RecordingGuard(private val reject: InvariantGateStage? = null) : InvariantGuard {
        val stages = mutableListOf<InvariantGateStage>()
        override suspend fun check(stage: InvariantGateStage, snapshot: InvariantSnapshot, artifact: String): GateOutcome {
            stages += stage
            return if (stage == reject) GateOutcome.Rejected(SafeInvariantRefusal(null, null, null, "safe")) else GateOutcome.Allowed(snapshot.ref(), sha256(artifact))
        }
        private fun InvariantSnapshot.ref() = InvariantSnapshotRef(id, collectionRevision, contentDigest)
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun InvariantSnapshot.ref() = InvariantSnapshotRef(id, collectionRevision, contentDigest)
}
