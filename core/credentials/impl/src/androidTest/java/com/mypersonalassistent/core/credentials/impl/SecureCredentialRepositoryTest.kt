package com.mypersonalassistent.core.credentials.impl

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mypersonalassistent.core.credentials.api.CredentialWriteResult
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
}
