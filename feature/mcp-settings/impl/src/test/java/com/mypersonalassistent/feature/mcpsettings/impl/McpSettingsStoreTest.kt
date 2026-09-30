package com.mypersonalassistent.feature.mcpsettings.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.mypersonalassistent.core.mcp.api.McpCatalogRepository
import com.mypersonalassistent.core.mcp.api.McpError
import com.mypersonalassistent.core.mcp.api.McpResult
import com.mypersonalassistent.core.mcp.api.McpServer
import com.mypersonalassistent.core.mcp.api.McpServerDraft
import com.mypersonalassistent.feature.mcpsettings.api.McpSettingsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpSettingsStoreTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `screen operations are intents and save failure remains visible with editor retained`() = runTest(dispatcher) {
        val catalog = FakeCatalog(saveResult = McpResult.Failure(McpError.VALIDATION))
        val store = McpSettingsStoreFactory(DefaultStoreFactory(), catalog).create()
        store.accept(McpSettingsIntent.Add)
        store.accept(McpSettingsIntent.ChangeName("Server"))
        store.accept(McpSettingsIntent.ChangeEndpoint("https:/not-a-host"))
        store.accept(McpSettingsIntent.ChangeToken("top-secret"))
        store.accept(McpSettingsIntent.Save)
        testScheduler.advanceUntilIdle()

        assertEquals(1, catalog.saved.size)
        assertEquals("top-secret", catalog.saved.single().token)
        assertNotNull(store.state.editor)
        assertFalse(store.state.busy)
        assertNotNull(store.state.errorMessage)
        store.dispose()
    }

    @Test fun `delete failure is exposed and never silently dismisses repository error`() = runTest(dispatcher) {
        val server = McpServer("server", "Server", "https://example.test/mcp", 1, 1)
        val catalog = FakeCatalog(listOf(server), deleteResult = McpResult.Failure(McpError.VALIDATION))
        val store = McpSettingsStoreFactory(DefaultStoreFactory(), catalog).create()
        testScheduler.advanceUntilIdle()
        store.accept(McpSettingsIntent.RequestDelete("server"))
        store.accept(McpSettingsIntent.ConfirmDelete)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("server"), catalog.deleted)
        assertNull(store.state.deleting)
        assertNotNull(store.state.errorMessage)
        assertFalse(store.state.busy)
        store.dispose()
    }

    @Test fun `successful edit keeps existing secrets unless clear intents are explicit`() = runTest(dispatcher) {
        val server = McpServer("server", "Server", "https://example.test/mcp", 1, 1)
        val catalog = FakeCatalog(listOf(server))
        val store = McpSettingsStoreFactory(DefaultStoreFactory(), catalog).create()
        testScheduler.advanceUntilIdle()
        store.accept(McpSettingsIntent.Edit("server"))
        store.accept(McpSettingsIntent.ChangeName("Renamed"))
        store.accept(McpSettingsIntent.Save)
        testScheduler.advanceUntilIdle()

        val saved = catalog.saved.single()
        assertEquals("server", saved.id)
        assertNull(saved.token)
        assertNull(saved.apiKey)
        assertFalse(saved.clearToken)
        assertFalse(saved.clearApiKey)
        assertNull(store.state.editor)
        store.dispose()
    }

    private class FakeCatalog(
        initial: List<McpServer> = emptyList(),
        private val saveResult: McpResult<McpServer> = McpResult.Success(McpServer("server", "Server", "https://example.test/mcp", 1, 1)),
        private val deleteResult: McpResult<Unit> = McpResult.Success(Unit),
    ) : McpCatalogRepository {
        private val servers = MutableStateFlow(initial)
        val saved = mutableListOf<McpServerDraft>()
        val deleted = mutableListOf<String>()
        override fun observe(): Flow<List<McpServer>> = servers
        override suspend fun save(draft: McpServerDraft): McpResult<McpServer> { saved += draft; return saveResult }
        override suspend fun delete(id: String): McpResult<Unit> { deleted += id; return deleteResult }
    }
}
