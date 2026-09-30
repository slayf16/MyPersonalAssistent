package com.mypersonalassistent.core.credentials.impl

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mypersonalassistent.core.credentials.api.CredentialWriteResult
import com.mypersonalassistent.core.credentials.api.McpSecrets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureCredentialRepositoryTest {
    @Test fun keyRoundTripUsesCiphertextInsteadOfPlaintext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = SecureCredentialRepository(context)
        val key = "test-key-${System.nanoTime()}"
        assertEquals(CredentialWriteResult.Success, repository.saveApiKey(key))
        assertEquals(key, repository.readApiKey())
        val prefs = context.getSharedPreferences("credentials", android.content.Context.MODE_PRIVATE)
        assertNotEquals(key, prefs.getString("ciphertext", null))
        val firstIv = prefs.getString("iv", null)
        assertNotEquals(null, firstIv)
        repository.saveApiKey(key)
        assertNotEquals(firstIv, prefs.getString("iv", null))
    }

    @Test fun stagedMcpSecretsSurviveRepositoryReopenAndStayInactiveUntilActivation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val serverId = "saga-${System.nanoTime()}"
        val first = SecureCredentialRepository(context)
        assertEquals(CredentialWriteResult.Success, first.save(serverId, McpSecrets(token = "old-token")))
        assertEquals(CredentialWriteResult.Success, first.stage(serverId, McpSecrets(token = "new-token", apiKey = "new-api")))

        val reopened = SecureCredentialRepository(context)
        assertEquals(true, reopened.hasStage(serverId))
        assertEquals(true, serverId in reopened.stagedServerIds())
        assertEquals(McpSecrets(token = "old-token"), reopened.read(serverId))
        assertEquals(CredentialWriteResult.Success, reopened.activateStage(serverId))

        val afterActivationReopen = SecureCredentialRepository(context)
        assertEquals(false, afterActivationReopen.hasStage(serverId))
        assertEquals(false, serverId in afterActivationReopen.stagedServerIds())
        assertEquals(McpSecrets(token = "new-token", apiKey = "new-api"), afterActivationReopen.read(serverId))
        afterActivationReopen.delete(serverId)
    }

    @Test fun discardAndDeleteRemoveStagedMcpCiphertextAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val serverId = "discard-${System.nanoTime()}"
        val repository = SecureCredentialRepository(context)
        repository.save(serverId, McpSecrets(token = "active"))
        repository.stage(serverId, McpSecrets(token = "staged"))

        val reopened = SecureCredentialRepository(context)
        assertEquals(CredentialWriteResult.Success, reopened.discardStage(serverId))
        assertEquals(false, serverId in reopened.stagedServerIds())
        assertEquals(McpSecrets(token = "active"), SecureCredentialRepository(context).read(serverId))
        repository.stage(serverId, McpSecrets(token = "another-stage"))
        assertEquals(CredentialWriteResult.Success, repository.delete(serverId))

        val deleted = SecureCredentialRepository(context)
        assertEquals(null, deleted.read(serverId))
        assertEquals(false, deleted.hasStage(serverId))
        assertEquals(false, serverId in deleted.stagedServerIds())
    }

    @Test fun durableDeleteMarkerRetriesOrphanCleanupAfterRepositoryReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val serverId = "delete-retry-${System.nanoTime()}"
        val repository = SecureCredentialRepository(context)
        assertEquals(CredentialWriteResult.Success, repository.save(serverId, McpSecrets(token = "orphan-token", apiKey = "orphan-key")))
        val prefs = context.getSharedPreferences("credentials", android.content.Context.MODE_PRIVATE)
        // Fixture models process death after the marker commit and before encrypted bytes removal.
        assertEquals(true, prefs.edit().putBoolean("mcp.delete.$serverId", true).commit())

        val reopened = SecureCredentialRepository(context)
        assertEquals(CredentialWriteResult.Success, reopened.retryPendingDeletes())

        val afterCleanup = SecureCredentialRepository(context)
        assertEquals(null, afterCleanup.read(serverId))
        assertEquals(false, prefs.contains("mcp.delete.$serverId"))
        assertEquals(false, prefs.all.keys.any { it.startsWith("mcp.$serverId.") })
    }
}
