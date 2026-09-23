package com.mypersonalassistent.feature.chat.impl

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.mypersonalassistent.core.agent.api.AgentCheckpoint
import com.mypersonalassistent.core.agent.api.AgentRunEngine
import com.mypersonalassistent.core.agent.api.AgentRunInput
import com.mypersonalassistent.core.agent.api.AgentRunResult
import com.mypersonalassistent.core.agent.api.AgentRunStatus
import com.mypersonalassistent.core.agent.api.PlanChangeContext
import com.mypersonalassistent.core.agent.api.StartNewTask
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.feature.chat.api.ChatEffect
import com.mypersonalassistent.feature.chat.api.ChatIntent
import com.mypersonalassistent.feature.chat.api.ChatState
import com.mypersonalassistent.feature.chat.api.TaskMemoryDraft
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal interface ChatStore : Store<ChatIntent, ChatState, ChatEffect>

/**
 * Owns the feature-scoped workflow job. The engine has no scope, so cancellation and the
 * monotonically increasing operation token make a late provider result harmless.
 */
internal class ChatStoreFactory(
    private val storeFactory: StoreFactory,
    private val id: String,
    private val history: HistoryRepository,
    private val memory: MemoryRepository,
    private val engine: AgentRunEngine,
    private val recovery: AgentRecoveryRepository,
    private val invariants: InvariantRepository? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun create(): ChatStore = object : ChatStore,
        Store<ChatIntent, ChatState, ChatEffect> by storeFactory.create(
            name = "ChatStore",
            initialState = ChatState(id),
            bootstrapper = null,
            executorFactory = ::Executor,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Message { data class State(val value: ChatState) : Message }

    private inner class Executor : CoroutineExecutor<ChatIntent, Nothing, ChatState, Message, ChatEffect>() {
        private var runJob: Job? = null
        private var token = 0L
        private var createdAt = clock()
        private var loaded = false

        init {
            scope.launch {
                engine.checkpoint.collect { checkpoint ->
                    // The engine serializes transitions and is the only source of in-flight progress.
                    val shown = state().checkpoint
                    if (
                        checkpoint?.chatId == id &&
                        !state().loading &&
                        !state().recoveryChoiceRequired &&
                        (shown == null || checkpoint.operationToken.value >= shown.operationToken.value)
                    ) {
                        set {
                            copy(
                                checkpoint = checkpoint,
                                // Refusal metadata is local to the checkpoint that emitted it;
                                // a subsequent unavailable/terminal result must not inherit it.
                                refusal = checkpoint.refusal,
                                recoveryAvailable = true,
                            )
                        }
                    }
                }
            }
            invariants?.let { repository ->
                scope.launch {
                    repository.changes.collect { change ->
                        if (id !in change.affectedRunIds) return@collect
                        val staleToken = ++token
                        try {
                            runJob?.cancelAndJoin()
                            if (staleToken == token) engine.markStaleAfterPolicyMutation(id, change.affectedRunIds)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            if (staleToken == token) publish(ChatEffect.TechnicalError)
                        }
                    }
                }
            }
        }

        override fun executeIntent(intent: ChatIntent) {
            when (intent) {
                ChatIntent.Load -> load()
                is ChatIntent.ChangeDraft -> if (canEditDraft()) set { copy(draft = intent.value) }
                ChatIntent.Send -> send()
                ChatIntent.ContinueRecovery -> continueRecovery()
                ChatIntent.Retry -> retry()
                ChatIntent.ApprovePlan -> approvePlan()
                ChatIntent.OpenPlanChanges -> openPlanChanges()
                is ChatIntent.ChangePlanComment -> changePlanComment(intent.value)
                ChatIntent.SubmitPlanChanges -> submitPlanChanges()
                ChatIntent.ClosePlanChanges -> if (!state().saving) set { copy(planChangeDialog = false) }
                ChatIntent.ContinueWithCurrentRules -> continueWithCurrentRules()
                ChatIntent.OpenInvariants -> if (state().checkpoint?.runStatus == AgentRunStatus.REFUSED) publish(ChatEffect.OpenInvariants)
                ChatIntent.StartNewTask -> startNewTask()
                ChatIntent.DiscardRecovery -> discardRecovery(reload = true)
                ChatIntent.OpenTaskEditor -> openTaskEditor()
                ChatIntent.CloseTaskEditor -> if (!state().saving) set { copy(taskEditorOpen = false) }
                is ChatIntent.ChangeTaskGoal -> editTask { copy(goal = intent.value.take(TASK_FIELD_LIMIT)) }
                is ChatIntent.ChangeTaskConstraints -> editTask { copy(constraints = intent.value.take(TASK_LIST_LIMIT)) }
                is ChatIntent.ChangeTaskResult -> editTask { copy(desiredResult = intent.value.take(TASK_FIELD_LIMIT)) }
                is ChatIntent.ChangeTaskDecisions -> editTask { copy(decisions = intent.value.take(TASK_LIST_LIMIT)) }
                ChatIntent.ApplyTaskMemory -> applyTaskMemory()
                ChatIntent.RequestExit -> if (!state().saving) set { copy(saveDialog = true) }
                ChatIntent.CloseDialog -> if (!state().saving) set { copy(saveDialog = false) }
                ChatIntent.Discard -> discardExit()
                ChatIntent.ConfirmSave -> save()
            }
        }

        private fun load(force: Boolean = false) {
            if (loaded && !force) return
            loaded = true
            set { copy(loading = true, loadFailed = false) }
            scope.launch {
                try {
                    val canonical = history.read(id)
                    val storedTask = memory.readTaskMemory(id)
                    val draft = recovery.readRecovery(id)
                    val canonicalCheckpoint = recovery.readCheckpoint(id)?.let(engine::decodeCheckpoint)
                    val restored = draft?.let { item -> item to engine.decodeCheckpoint(item.checkpointJson) }
                    // A recovery of an already saved chat requires a deliberate choice. It is never
                    // silently substituted for canonical history when the row is opened.
                    val chooseRecovery = draft != null && canonical != null
                    val snapshot = if (chooseRecovery) canonical else restored?.first?.snapshot ?: canonical
                    val task = if (chooseRecovery) storedTask else restored?.first?.taskMemory ?: storedTask
                    val checkpoint = if (chooseRecovery) canonicalCheckpoint else restored?.second ?: canonicalCheckpoint
                    if (snapshot != null) createdAt = snapshot.createdAt
                    set {
                        ChatState(
                            id = id,
                            messages = snapshot?.messages.orEmpty(),
                            taskMemory = task,
                            checkpoint = checkpoint,
                            refusal = checkpoint?.refusal,
                            recoveryAvailable = draft != null,
                            recoveryChoiceRequired = chooseRecovery,
                            loading = false,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    set { copy(loading = false, loadFailed = true) }
                    publish(ChatEffect.TechnicalError)
                }
            }
        }

        private fun send() {
            val before = state()
            val text = before.draft
            if (text.isBlank() || !canEditDraft()) return
            val checkpoint = before.checkpoint
            if (checkpoint?.runStatus == AgentRunStatus.FAILED) return
            val user = ChatMessage(UUID.randomUUID().toString(), MessageRole.USER, text)
            val messages = before.messages + user
            set { copy(messages = messages, draft = "") }
            val action: suspend (AgentRunInput) -> AgentRunResult = when (checkpoint?.runStatus) {
                AgentRunStatus.WAITING_USER,
                AgentRunStatus.ACTIVE -> engine::answer
                else -> engine::start
            }
            executeRun(messages, before.taskMemory, checkpoint, action)
        }

        private fun retry() {
            val before = state()
            val checkpoint = before.checkpoint ?: return
            if (checkpoint.runStatus != AgentRunStatus.FAILED || !checkpoint.retryAllowed) return
            executeRun(before.messages, before.taskMemory, checkpoint, engine::retry)
        }

        private fun approvePlan() {
            val before = state()
            val checkpoint = before.checkpoint ?: return
            if (checkpoint.runStatus != AgentRunStatus.WAITING_APPROVAL) return
            executeRun(before.messages, before.taskMemory, checkpoint) { input -> engine.approvePlan(input, checkpoint.revision) }
        }

        private fun openPlanChanges() {
            if (state().checkpoint?.runStatus == AgentRunStatus.WAITING_APPROVAL && !state().saving) {
                set { copy(planChangeDialog = true, planChangeComment = "") }
            }
        }

        private fun changePlanComment(value: String) {
            if (state().planChangeDialog && !state().saving) set { copy(planChangeComment = value.limitCodePoints(2_000)) }
        }

        private fun submitPlanChanges() {
            val before = state()
            val checkpoint = before.checkpoint ?: return
            val comment = before.planChangeComment.trim()
            if (checkpoint.runStatus != AgentRunStatus.WAITING_APPROVAL || comment.isEmpty()) return
            set { copy(planChangeDialog = false) }
            executeRun(before.messages, before.taskMemory, checkpoint) { input ->
                engine.requestPlanChanges(input, PlanChangeContext(checkpoint.revision, comment))
            }
        }

        private fun continueWithCurrentRules() {
            val before = state()
            val checkpoint = before.checkpoint ?: return
            if (checkpoint.runStatus != AgentRunStatus.STALE_PAUSED) return
            executeRun(before.messages, before.taskMemory, checkpoint, engine::continueWithCurrentRules)
        }

        private fun executeRun(
            messages: List<ChatMessage>,
            task: TaskMemory,
            checkpoint: AgentCheckpoint?,
            operation: suspend (AgentRunInput) -> AgentRunResult,
        ) {
            if (runJob?.isActive == true) return
            val callToken = ++token
            runJob = scope.launch {
                try {
                    val result = operation(AgentRunInput(id, messages, task, checkpoint))
                    if (callToken != token) return@launch
                    applyResult(result, messages, task)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (callToken == token) publish(ChatEffect.TechnicalError)
                }
            }
        }

        private suspend fun applyResult(
            result: AgentRunResult,
            messages: List<ChatMessage>,
            task: TaskMemory,
            persistResult: Boolean = true,
        ) {
            val visible = buildList {
                addAll(messages)
                result.visibleQuestion?.let { add(ChatMessage(UUID.randomUUID().toString(), MessageRole.ASSISTANT, it)) }
                result.finalResult?.let { add(ChatMessage(UUID.randomUUID().toString(), MessageRole.ASSISTANT, it)) }
            }
            val updatedTask = task.copy(updatedAt = clock())
            set {
                copy(
                    messages = visible,
                    taskMemory = updatedTask,
                    checkpoint = result.checkpoint,
                    refusal = result.refusal ?: result.checkpoint.refusal,
                    recoveryAvailable = persistResult,
                )
            }
            if (persistResult) engine.persist(AgentRunInput(id, visible, updatedTask, result.checkpoint))
            if (result.failure != null) publish(ChatEffect.TechnicalError)
        }

        private fun startNewTask() {
            val before = state()
            val expectedToken = before.checkpoint?.operationToken
            val callToken = ++token
            runJob = scope.launch {
                try {
                    // Termination, join and recovery/index closure belong to the engine.  The
                    // feature only projects its authoritative result and ignores an old callback.
                    val result = engine.startNewTask(
                        StartNewTask(
                            chatId = id,
                            messages = before.messages,
                            taskMemory = before.taskMemory,
                            expectedOperationToken = expectedToken,
                        ),
                    )
                    if (callToken == token) applyResult(result, before.messages, before.taskMemory, persistResult = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    publish(ChatEffect.TechnicalError)
                }
            }
        }

        private fun continueRecovery() {
            val current = state()
            if (!current.recoveryAvailable || !current.recoveryChoiceRequired) return
            scope.launch {
                try {
                    val draft = recovery.readRecovery(id) ?: throw IllegalStateException("Recovery is unavailable")
                    val checkpoint = engine.decodeCheckpoint(draft.checkpointJson)
                        ?: throw IllegalStateException("Recovery checkpoint is invalid")
                    createdAt = draft.snapshot.createdAt
                    set {
                        copy(
                            messages = draft.snapshot.messages,
                            taskMemory = draft.taskMemory,
                            checkpoint = checkpoint,
                            refusal = checkpoint.refusal,
                            recoveryAvailable = true,
                            recoveryChoiceRequired = false,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    publish(ChatEffect.TechnicalError)
                }
            }
        }

        private fun discardRecovery(reload: Boolean) {
            ++token
            scope.launch {
                try {
                    runJob?.cancelAndJoin()
                    if (!engine.discardRecovery(id)) throw IllegalStateException("Recovery discard failed")
                    if (reload) load(force = true) else publish(ChatEffect.NavigateHome)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    publish(ChatEffect.TechnicalError)
                }
            }
        }

        private fun openTaskEditor() {
            val current = state()
            if (current.loading || current.loadFailed || current.saving || !current.taskMemoryEditable) return
            val task = current.taskMemory
            set {
                copy(taskEditorOpen = true, taskDraft = TaskMemoryDraft(task.goal, task.constraints.joinToString("\n"), task.desiredResult, task.decisions.joinToString("\n")))
            }
        }

        private fun editTask(block: TaskMemoryDraft.() -> TaskMemoryDraft) {
            if (state().taskEditorOpen && !state().saving && state().taskMemoryEditable) set { copy(taskDraft = taskDraft.block()) }
        }

        private fun applyTaskMemory() {
            val current = state()
            if (!current.taskEditorOpen || current.saving || !current.taskMemoryEditable) return
            val draft = current.taskDraft
            set {
                copy(
                    taskEditorOpen = false,
                    taskMemory = TaskMemory(id, draft.goal.trim(), draft.constraints.toItems(), draft.desiredResult.trim(), draft.decisions.toItems(), current.taskMemory.updatedAt),
                )
            }
        }

        private fun save() {
            val before = state()
            if (!before.saveDialog || before.saving || before.loading || before.loadFailed) return
            set { copy(saving = true) }
            val saveToken = ++token
            scope.launch {
                try {
                    runJob?.cancelAndJoin()
                    var current = state()
                    current.checkpoint?.let { checkpoint ->
                        val normalized = engine.normalizeInterruptedForRecovery(
                            AgentRunInput(id, current.messages, current.taskMemory, checkpoint),
                        )
                        current = current.copy(checkpoint = normalized.checkpoint, refusal = normalized.refusal ?: normalized.checkpoint.refusal)
                        set { current }
                    }
                    val saved = if (current.checkpoint != null) {
                        engine.persist(AgentRunInput(id, current.messages, current.taskMemory.copy(updatedAt = clock()), current.checkpoint))
                        recovery.readRecovery(id)?.let { recovery.promoteRecovery(it) } ?: false
                    } else {
                        history.save(ChatSnapshot(id, titleOf(current.messages), createdAt, clock(), current.messages), current.taskMemory.copy(updatedAt = clock()))
                    }
                    if (saveToken == token && saved) publish(ChatEffect.NavigateHome) else if (saveToken == token) saveFailed()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (saveToken == token) saveFailed()
                }
            }
        }

        private fun discardExit() {
            if (!state().saving) discardRecovery(reload = false)
        }

        private fun saveFailed() {
            set { copy(saving = false) }
            publish(ChatEffect.TechnicalError)
        }

        private fun canEditDraft(): Boolean {
            return state().composerEditable
        }

        private fun set(transform: ChatState.() -> ChatState) = dispatch(Message.State(state().transform()))
        private fun String.toItems() = lineSequence().map { it.trim() }.filter(String::isNotEmpty).toList()
        private fun String.limitCodePoints(limit: Int): String {
            if (codePointCount(0, length) <= limit) return this
            return substring(0, offsetByCodePoints(0, limit))
        }
        private fun titleOf(messages: List<ChatMessage>): String = messages.firstOrNull { it.role == MessageRole.USER }?.content?.replace(Regex("\\s+"), " ")?.take(60) ?: "Новый чат"
    }

    private object ReducerImpl : Reducer<ChatState, Message> {
        override fun ChatState.reduce(msg: Message): ChatState = when (msg) { is Message.State -> msg.value }
    }

    private companion object { const val TASK_FIELD_LIMIT = 1_000; const val TASK_LIST_LIMIT = 4_000 }
}
