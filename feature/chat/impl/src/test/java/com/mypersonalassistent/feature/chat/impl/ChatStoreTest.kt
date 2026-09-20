package com.mypersonalassistent.feature.chat.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.history.api.ChatSnapshot
import com.mypersonalassistent.core.history.api.ChatSummary
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.core.llm.api.LlmError
import com.mypersonalassistent.core.llm.api.LlmRequest
import com.mypersonalassistent.core.llm.api.LlmResult
import com.mypersonalassistent.feature.chat.api.ChatIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()
    @Test fun `failed send restores exact raw draft and removes only current turn`() = runTest(dispatcher) {
        val store = ChatStoreFactory(DefaultStoreFactory(), "id", NoHistory, FailingLlm).create()
        store.accept(ChatIntent.ChangeDraft("  exact text  ")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals("  exact text  ", store.state.draft)
        assertEquals(0, store.state.messages.size)
        store.dispose()
    }
    @Test fun `concurrent send creates one provider call`() = runTest(dispatcher) {
        val llm = CountingLlm(); val store = ChatStoreFactory(DefaultStoreFactory(), "id", NoHistory, llm).create()
        store.accept(ChatIntent.ChangeDraft("one")); store.accept(ChatIntent.Send); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(1, llm.calls); store.dispose()
    }
    @Test fun `save cancels active request and persists interrupted user turn`() = runTest(dispatcher) {
        val history = RecordingHistory(); val store = ChatStoreFactory(DefaultStoreFactory(), "id", history, HangingLlm).create()
        store.accept(ChatIntent.ChangeDraft("draft")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle()
        assertEquals("draft", history.saved?.messages?.single()?.content); assertEquals(com.mypersonalassistent.core.history.api.DeliveryState.INTERRUPTED, history.saved?.messages?.single()?.deliveryState); store.dispose()
    }
    @Test fun `discard leaves loaded history untouched`() = runTest(dispatcher) {
        val persisted = ChatSnapshot("id", "title", 1, 1, listOf(com.mypersonalassistent.core.history.api.ChatMessage("m", com.mypersonalassistent.core.history.api.MessageRole.USER, "saved")))
        val history = RecordingHistory(persisted); val store = ChatStoreFactory(DefaultStoreFactory(), "id", history, CountingLlm()).create()
        store.accept(ChatIntent.Load); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.Discard); testScheduler.advanceUntilIdle()
        assertEquals(0, history.saveCalls); assertEquals(persisted.messages, store.state.messages); store.dispose()
    }
    @Test fun `failed save unlocks UI and keeps transcript`() = runTest(dispatcher) {
        val history = FailingSaveHistory(); val store = ChatStoreFactory(DefaultStoreFactory(), "id", history, CountingLlm()).create()
        store.accept(ChatIntent.ChangeDraft("retry")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle()
        assertEquals(1, history.saveCalls); assertFalse(store.state.saving); assertEquals(listOf("retry", "ok"), store.state.messages.map { it.content }); store.dispose()
    }
    @Test fun `retry after a failed send uses one current user turn`() = runTest(dispatcher) {
        val llm = FailThenSuccessLlm(); val store = ChatStoreFactory(DefaultStoreFactory(), "id", NoHistory, llm).create()
        store.accept(ChatIntent.ChangeDraft("first")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        store.accept(ChatIntent.ChangeDraft("edited")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle()
        assertEquals(listOf("edited"), llm.requests[1].messages.map { it.text })
        assertEquals(listOf("edited", "ok"), store.state.messages.map { it.content }); store.dispose()
    }
    @Test fun `discard after new changes preserves the prior saved snapshot`() = runTest(dispatcher) {
        val persisted = ChatSnapshot("id", "title", 1, 1, listOf(com.mypersonalassistent.core.history.api.ChatMessage("m", com.mypersonalassistent.core.history.api.MessageRole.USER, "saved")))
        val history = RecordingHistory(persisted); val store = ChatStoreFactory(DefaultStoreFactory(), "id", history, CountingLlm()).create()
        store.accept(ChatIntent.Load); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.ChangeDraft("new")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.Discard); testScheduler.advanceUntilIdle()
        assertEquals(0, history.saveCalls); assertEquals(persisted, history.read("id")); store.dispose()
    }
    @Test fun `discard cancels an active request without restoring its draft`() = runTest(dispatcher) {
        val llm = CancellableLlm(); val store = ChatStoreFactory(DefaultStoreFactory(), "id", NoHistory, llm).create()
        store.accept(ChatIntent.ChangeDraft("draft")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.Discard); testScheduler.advanceUntilIdle()
        assertEquals(true, llm.cancelled); assertEquals("", store.state.draft); assertEquals(listOf("draft"), store.state.messages.map { it.content }); store.dispose()
    }
    @Test fun `save retry persists one unchanged snapshot after the first failure`() = runTest(dispatcher) {
        val history = FailsOnceHistory(); val store = ChatStoreFactory(DefaultStoreFactory(), "id", history, CountingLlm()).create()
        store.accept(ChatIntent.ChangeDraft("retry")); store.accept(ChatIntent.Send); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.RequestExit); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle(); store.accept(ChatIntent.ConfirmSave); testScheduler.advanceUntilIdle()
        assertEquals(2, history.saveCalls); assertEquals(listOf("retry", "ok"), history.saved?.messages?.map { it.content }); store.dispose()
    }
    private object FailingLlm : Llm { override suspend fun execute(request: LlmRequest) = LlmResult.Failure(LlmError.NETWORK) }
    private class CountingLlm : Llm { var calls = 0; override suspend fun execute(request: LlmRequest): LlmResult { calls++; return LlmResult.Success("ok", null) } }
    private object HangingLlm : Llm { override suspend fun execute(request: LlmRequest): LlmResult { awaitCancellation() } }
    private class FailThenSuccessLlm : Llm { val requests = mutableListOf<LlmRequest>(); override suspend fun execute(request: LlmRequest): LlmResult { requests += request; return if (requests.size == 1) LlmResult.Failure(LlmError.NETWORK) else LlmResult.Success("ok", null) } }
    private class CancellableLlm : Llm { var cancelled = false; override suspend fun execute(request: LlmRequest): LlmResult = try { awaitCancellation() } finally { cancelled = true } }
    private object NoHistory : HistoryRepository { override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow(); override suspend fun read(id: String): ChatSnapshot? = null; override suspend fun save(snapshot: ChatSnapshot) = true }
    private class RecordingHistory(private val loaded: ChatSnapshot? = null) : HistoryRepository { var saved: ChatSnapshot? = null; var saveCalls = 0; override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow(); override suspend fun read(id: String): ChatSnapshot? = loaded; override suspend fun save(snapshot: ChatSnapshot): Boolean { saveCalls++; saved = snapshot; return true } }
    private class FailingSaveHistory : HistoryRepository { var saveCalls = 0; override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow(); override suspend fun read(id: String): ChatSnapshot? = null; override suspend fun save(snapshot: ChatSnapshot): Boolean { saveCalls++; throw IllegalStateException("storage failure") } }
    private class FailsOnceHistory : HistoryRepository { var saveCalls = 0; var saved: ChatSnapshot? = null; override fun observeSummaries(): Flow<List<ChatSummary>> = emptyFlow(); override suspend fun read(id: String): ChatSnapshot? = null; override suspend fun save(snapshot: ChatSnapshot): Boolean { saveCalls++; if (saveCalls == 1) throw IllegalStateException("storage failure"); saved = snapshot; return true } }
}
