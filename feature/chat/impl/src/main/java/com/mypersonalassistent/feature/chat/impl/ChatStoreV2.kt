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
import com.mypersonalassistent.core.agent.api.ResumeOperation
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.MessageRole
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
                    if (checkpoint?.chatId == id && !state().loading && !state().recoveryChoiceRequired) {
                        set { copy(checkpoint = checkpoint, recoveryAvailable = true) }
                    }
                }
            }
        }

        override fun executeIntent(intent: ChatIntent) {
            when (intent) {
                ChatIntent.Load -> load()
                is ChatIntent.ChangeDraft -> if (canEditDraft()) set { copy(draft = intent.value) }
                ChatIntent.Send -> send()
                ChatIntent.Pause -> pause()
                ChatIntent.Resume -> resume()
                ChatIntent.ContinueRecovery -> continueRecovery()
                ChatIntent.Retry -> retry()
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
                    val restored = draft?.let { item ->
                        item to engine.decodeCheckpoint(item.checkpointJson)?.normalizedAfterProcessDeath()
                    }
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
            if (checkpoint?.runStatus == AgentRunStatus.PAUSED || checkpoint?.runStatus == AgentRunStatus.FAILED) return
            val user = ChatMessage(UUID.randomUUID().toString(), MessageRole.USER, text)
            val messages = before.messages + user
            set { copy(messages = messages, draft = "") }
            val action: suspend (AgentRunInput) -> AgentRunResult = when (checkpoint?.runStatus) {
                AgentRunStatus.WAITING_USER -> engine::answer
                else -> engine::start
            }
            executeRun(messages, before.taskMemory, checkpoint, action)
        }

        private fun resume() {
            val before = state()
            val checkpoint = before.checkpoint ?: return
            if (checkpoint.runStatus != AgentRunStatus.PAUSED && checkpoint.runStatus != AgentRunStatus.WAITING_USER) return
            if (checkpoint.runStatus == AgentRunStatus.WAITING_USER) {
                set { copy(checkpoint = checkpoint.copy(expectedAction = "Ответьте на вопрос")) }
                return
            }
            executeRun(before.messages, before.taskMemory, checkpoint, engine::resume)
        }

        private fun retry() {
            val before = state()
            val checkpoint = before.checkpoint ?: return
            if (checkpoint.runStatus != AgentRunStatus.FAILED || !checkpoint.retryAllowed) return
            executeRun(before.messages, before.taskMemory, checkpoint, engine::retry)
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

        private suspend fun applyResult(result: AgentRunResult, messages: List<ChatMessage>, task: TaskMemory) {
            val visible = buildList {
                addAll(messages)
                result.visibleQuestion?.let { add(ChatMessage(UUID.randomUUID().toString(), MessageRole.ASSISTANT, it)) }
                result.finalResult?.let { add(ChatMessage(UUID.randomUUID().toString(), MessageRole.ASSISTANT, it)) }
            }
            val updatedTask = task.copy(updatedAt = clock())
            set { copy(messages = visible, taskMemory = updatedTask, checkpoint = result.checkpoint, recoveryAvailable = true) }
            engine.persist(AgentRunInput(id, visible, updatedTask, result.checkpoint))
            if (result.failure != null) publish(ChatEffect.TechnicalError)
        }

        private fun pause() {
            val before = state()
            val checkpoint = before.checkpoint ?: return
            if (checkpoint.runStatus != AgentRunStatus.ACTIVE && checkpoint.runStatus != AgentRunStatus.WAITING_USER) return
            val pauseToken = ++token
            scope.launch {
                try {
                    runJob?.cancelAndJoin()
                    val paused = engine.pause(AgentRunInput(id, state().messages, state().taskMemory))
                    if (pauseToken == token) set { copy(checkpoint = paused, recoveryAvailable = true) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (pauseToken == token) publish(ChatEffect.TechnicalError)
                }
            }
        }

        private fun startNewTask() {
            ++token
            scope.launch {
                try {
                    runJob?.cancelAndJoin()
                    engine.discardRecovery(id)
                    set { copy(checkpoint = null, recoveryAvailable = false, draft = "") }
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
                    val checkpoint = engine.decodeCheckpoint(draft.checkpointJson)?.normalizedAfterProcessDeath()
                        ?: throw IllegalStateException("Recovery checkpoint is invalid")
                    createdAt = draft.snapshot.createdAt
                    set {
                        copy(
                            messages = draft.snapshot.messages,
                            taskMemory = draft.taskMemory,
                            checkpoint = checkpoint,
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
                    val active = current.checkpoint
                    if (active?.runStatus == AgentRunStatus.ACTIVE || active?.runStatus == AgentRunStatus.WAITING_USER) {
                        val paused = engine.pause(AgentRunInput(id, current.messages, current.taskMemory))
                        current = current.copy(checkpoint = paused)
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
        private fun titleOf(messages: List<ChatMessage>): String = messages.firstOrNull { it.role == MessageRole.USER }?.content?.replace(Regex("\\s+"), " ")?.take(60) ?: "Новый чат"
    }

    private fun AgentCheckpoint.normalizedAfterProcessDeath(): AgentCheckpoint =
        if (runStatus == AgentRunStatus.ACTIVE || inFlight) copy(
            runStatus = AgentRunStatus.PAUSED,
            inFlight = false,
            pausedFromStatus = AgentRunStatus.ACTIVE,
            resumeOperation = ResumeOperation.REPEAT_CALL,
            expectedAction = "Продолжить",
        ) else this

    private object ReducerImpl : Reducer<ChatState, Message> {
        override fun ChatState.reduce(msg: Message): ChatState = when (msg) { is Message.State -> msg.value }
    }

    private companion object { const val TASK_FIELD_LIMIT = 1_000; const val TASK_LIST_LIMIT = 4_000 }
}
