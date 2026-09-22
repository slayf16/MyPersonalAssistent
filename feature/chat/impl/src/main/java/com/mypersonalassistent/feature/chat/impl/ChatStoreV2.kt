package com.mypersonalassistent.feature.chat.impl

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.mypersonalassistent.core.agent.api.AgentRequestComposer
import com.mypersonalassistent.core.history.api.ChatMessage
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.DeliveryState
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.MessageRole
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmResult
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
import kotlinx.coroutines.launch

internal interface ChatStore : Store<ChatIntent, ChatState, ChatEffect>

internal class ChatStoreFactory(
    private val storeFactory: StoreFactory,
    private val id: String,
    private val history: HistoryRepository,
    private val memory: MemoryRepository,
    private val composer: AgentRequestComposer,
    private val llm: Llm,
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
        private var request: Job? = null
        private var requestId = 0L
        private var createdAt = clock()
        private var loaded = false

        override fun executeIntent(intent: ChatIntent) {
            when (intent) {
                ChatIntent.Load -> load()
                is ChatIntent.ChangeDraft -> if (canEdit()) dispatch(Message.State(state().copy(draft = intent.value)))
                ChatIntent.Send -> send()
                ChatIntent.OpenTaskEditor -> openTaskEditor()
                ChatIntent.CloseTaskEditor -> if (!state().saving) dispatch(Message.State(state().copy(taskEditorOpen = false)))
                is ChatIntent.ChangeTaskGoal -> editTask { copy(goal = intent.value.take(TASK_FIELD_LIMIT)) }
                is ChatIntent.ChangeTaskConstraints -> editTask { copy(constraints = intent.value.take(TASK_LIST_LIMIT)) }
                is ChatIntent.ChangeTaskResult -> editTask { copy(desiredResult = intent.value.take(TASK_FIELD_LIMIT)) }
                is ChatIntent.ChangeTaskDecisions -> editTask { copy(decisions = intent.value.take(TASK_LIST_LIMIT)) }
                ChatIntent.ApplyTaskMemory -> applyTaskMemory()
                ChatIntent.RequestExit -> dispatch(Message.State(state().copy(saveDialog = true)))
                ChatIntent.CloseDialog -> if (!state().saving) dispatch(Message.State(state().copy(saveDialog = false)))
                ChatIntent.Discard -> discard()
                ChatIntent.ConfirmSave -> save()
            }
        }

        private fun load() {
            if (loaded) return
            loaded = true
            dispatch(Message.State(state().copy(loading = true)))
            scope.launch {
                try {
                    val snapshot = history.read(id)
                    val task = memory.readTaskMemory(id)
                    if (snapshot != null) {
                        createdAt = snapshot.createdAt
                        val interrupted = snapshot.messages.lastOrNull()?.takeIf { it.deliveryState == DeliveryState.INTERRUPTED }
                        val messages = if (interrupted == null) snapshot.messages else snapshot.messages.filterNot { it.id == interrupted.id }
                        dispatch(Message.State(ChatState(id, messages, interrupted?.content.orEmpty(), taskMemory = task, loading = false)))
                    } else {
                        dispatch(Message.State(state().copy(taskMemory = task, loading = false)))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    dispatch(Message.State(state().copy(loading = false, loadFailed = true)))
                    publish(ChatEffect.TechnicalError)
                }
            }
        }

        private fun send() {
            val before = state()
            val text = before.draft
            if (text.isBlank() || before.sending || before.saving || before.loading || before.loadFailed) return
            val user = ChatMessage(UUID.randomUUID().toString(), MessageRole.USER, text)
            val messages = before.messages + user
            val callId = ++requestId
            dispatch(Message.State(before.copy(messages = messages, draft = "", sending = true)))
            request = scope.launch {
                try {
                    when (val result = llm.execute(composer.compose(id, messages, before.taskMemory))) {
                        is LlmResult.Success -> if (callId == requestId) {
                            dispatch(Message.State(state().copy(
                                messages = state().messages + ChatMessage(
                                    UUID.randomUUID().toString(),
                                    MessageRole.ASSISTANT,
                                    result.text,
                                    finishReason = result.finishReason,
                                ),
                                sending = false,
                            )))
                        }
                        is LlmResult.Failure -> if (callId == requestId) fail(user)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (callId == requestId) fail(user)
                }
            }
        }

        private fun openTaskEditor() {
            if (state().loading || state().loadFailed || state().saving) return
            val task = state().taskMemory
            dispatch(Message.State(state().copy(
                taskEditorOpen = true,
                taskDraft = TaskMemoryDraft(
                    task.goal,
                    task.constraints.joinToString("\n"),
                    task.desiredResult,
                    task.decisions.joinToString("\n"),
                ),
            )))
        }

        private fun editTask(block: TaskMemoryDraft.() -> TaskMemoryDraft) {
            if (state().taskEditorOpen && !state().saving) {
                dispatch(Message.State(state().copy(taskDraft = state().taskDraft.block())))
            }
        }

        private fun applyTaskMemory() {
            val current = state()
            if (!current.taskEditorOpen || current.saving) return
            val draft = current.taskDraft
            dispatch(Message.State(current.copy(
                taskEditorOpen = false,
                taskMemory = TaskMemory(
                    chatId = id,
                    goal = draft.goal.trim(),
                    constraints = draft.constraints.toItems(),
                    desiredResult = draft.desiredResult.trim(),
                    decisions = draft.decisions.toItems(),
                    updatedAt = current.taskMemory.updatedAt,
                ),
            )))
        }

        private fun fail(user: ChatMessage) {
            dispatch(Message.State(state().copy(
                messages = state().messages.filterNot { it.id == user.id },
                draft = user.content,
                sending = false,
            )))
            publish(ChatEffect.TechnicalError)
        }

        private fun discard() {
            if (!state().saving) {
                ++requestId
                request?.cancel()
                publish(ChatEffect.NavigateHome)
            }
        }

        private fun save() {
            val before = state()
            if (!before.saveDialog || before.saving || before.loadFailed || before.loading) return
            dispatch(Message.State(before.copy(saving = true)))
            scope.launch {
                ++requestId
                request?.cancelAndJoin()
                val current = state()
                val last = current.messages.lastOrNull()
                val interrupted = last?.takeIf { it.role == MessageRole.USER }?.copy(deliveryState = DeliveryState.INTERRUPTED)
                val messages = if (current.sending && interrupted != null) current.messages.dropLast(1) + interrupted else current.messages
                dispatch(Message.State(current.copy(messages = messages, sending = false)))
                val now = clock()
                val snapshot = ChatSnapshot(id, titleOf(messages), createdAt, now, messages)
                try {
                    if (history.save(snapshot, current.taskMemory.copy(updatedAt = now))) {
                        publish(ChatEffect.NavigateHome)
                    } else {
                        saveFailed()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    saveFailed()
                }
            }
        }

        private fun saveFailed() {
            dispatch(Message.State(state().copy(saving = false)))
            publish(ChatEffect.TechnicalError)
        }

        private fun canEdit() = !state().sending && !state().saving && !state().loading && !state().loadFailed
        private fun String.toItems() = lineSequence().map { it.trim() }.filter(String::isNotEmpty).toList()
        private fun titleOf(messages: List<ChatMessage>): String = messages
            .firstOrNull { it.role == MessageRole.USER }
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.codePoints()
            ?.limit(60)
            ?.toArray()
            ?.let { String(it, 0, it.size) }
            ?: "Новый чат"
    }

    private object ReducerImpl : Reducer<ChatState, Message> {
        override fun ChatState.reduce(msg: Message): ChatState = when (msg) {
            is Message.State -> msg.value
        }
    }

    private companion object {
        const val TASK_FIELD_LIMIT = 1_000
        const val TASK_LIST_LIMIT = 4_000
    }
}
