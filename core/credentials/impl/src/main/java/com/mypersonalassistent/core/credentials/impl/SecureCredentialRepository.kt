package com.mypersonalassistent.core.credentials.impl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.core.credentials.api.CredentialWriteResult
import com.mypersonalassistent.core.credentials.api.McpSecretRepository
import com.mypersonalassistent.core.credentials.api.McpSecrets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ciphertext and a new IV are stored privately; plaintext never reaches preferences or logs. */
class SecureCredentialRepository(context: Context) : CredentialRepository, McpSecretRepository {
    private val preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    override suspend fun readApiKey(): String? = withContext(Dispatchers.IO) {
        val ciphertext = preferences.getString(CIPHER, null) ?: return@withContext null
        val iv = preferences.getString(IV, null) ?: return@withContext null
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }
    override suspend fun saveApiKey(value: String): CredentialWriteResult = withContext(Dispatchers.IO) {
        if (value.isBlank()) return@withContext CredentialWriteResult.Failure
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            preferences.edit().putString(CIPHER, Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()
        }.fold({ if (it) CredentialWriteResult.Success else CredentialWriteResult.Failure }, { CredentialWriteResult.Failure })
    }
    override suspend fun read(serverId: String): McpSecrets? = withContext(Dispatchers.IO) {
        if (!serverId.isSafeServerId()) return@withContext null
        val token = decrypt(serverId, "token")
        val apiKey = decrypt(serverId, "api")
        if (token == null && apiKey == null) null else McpSecrets(token, apiKey)
    }
    override suspend fun save(serverId: String, secrets: McpSecrets): CredentialWriteResult = withContext(Dispatchers.IO) {
        if (!serverId.isSafeServerId()) return@withContext CredentialWriteResult.Failure
        runCatching {
            val edit = preferences.edit()
            editSecret(edit, serverId, "token", secrets.token)
            editSecret(edit, serverId, "api", secrets.apiKey)
            if (edit.commit()) CredentialWriteResult.Success else CredentialWriteResult.Failure
        }.getOrElse { CredentialWriteResult.Failure }
    }
    override suspend fun delete(serverId: String): CredentialWriteResult = withContext(Dispatchers.IO) {
        if (!serverId.isSafeServerId()) return@withContext CredentialWriteResult.Failure
        runCatching {
            if (!preferences.edit().putBoolean(deleteKey(serverId), true).commit()) return@runCatching false
            deleteMarked(serverId)
        }.fold({ if (it) CredentialWriteResult.Success else CredentialWriteResult.Failure }, { CredentialWriteResult.Failure })
    }
    override suspend fun stage(serverId: String, secrets: McpSecrets): CredentialWriteResult = withContext(Dispatchers.IO) {
        if (!serverId.isSafeServerId()) return@withContext CredentialWriteResult.Failure
        runCatching {
            val edit = preferences.edit()
            editSecret(edit, serverId, "stage.token", secrets.token)
            editSecret(edit, serverId, "stage.api", secrets.apiKey)
            edit.putBoolean(stageKey(serverId), true)
            if (edit.commit()) CredentialWriteResult.Success else CredentialWriteResult.Failure
        }.getOrElse { CredentialWriteResult.Failure }
    }
    override suspend fun hasStage(serverId: String): Boolean = withContext(Dispatchers.IO) { serverId.isSafeServerId() && preferences.getBoolean(stageKey(serverId), false) }
    override suspend fun stagedServerIds(): Set<String> = withContext(Dispatchers.IO) {
        preferences.all.keys.asSequence()
            .filter { it.startsWith(MCP_PREFIX) && it.endsWith(STAGE_SUFFIX) && preferences.getBoolean(it, false) }
            .map { it.removePrefix(MCP_PREFIX).removeSuffix(STAGE_SUFFIX) }
            .filter { it.isSafeServerId() }
            .toSet()
    }
    override suspend fun activateStage(serverId: String): CredentialWriteResult = withContext(Dispatchers.IO) {
        if (!serverId.isSafeServerId()) return@withContext CredentialWriteResult.Failure
        if (!preferences.getBoolean(stageKey(serverId), false)) return@withContext CredentialWriteResult.Success
        runCatching {
            val edit = preferences.edit()
            moveSecret(edit, serverId, "stage.token", "token"); moveSecret(edit, serverId, "stage.api", "api")
            edit.remove(stageKey(serverId))
            if (edit.commit()) CredentialWriteResult.Success else CredentialWriteResult.Failure
        }.getOrElse { CredentialWriteResult.Failure }
    }
    override suspend fun discardStage(serverId: String): CredentialWriteResult = withContext(Dispatchers.IO) {
        if (!serverId.isSafeServerId()) return@withContext CredentialWriteResult.Failure
        runCatching { preferences.edit().remove(cipherKey(serverId, "stage.token")).remove(ivKey(serverId, "stage.token")).remove(cipherKey(serverId, "stage.api")).remove(ivKey(serverId, "stage.api")).remove(stageKey(serverId)).commit() }
            .fold({ if (it) CredentialWriteResult.Success else CredentialWriteResult.Failure }, { CredentialWriteResult.Failure })
    }
    override suspend fun retryPendingDeletes(): CredentialWriteResult = withContext(Dispatchers.IO) {
        val pending = preferences.all.keys.asSequence()
            .filter { it.startsWith(DELETE_PREFIX) && preferences.getBoolean(it, false) }
            .map { it.removePrefix(DELETE_PREFIX) }
            .filter { it.isSafeServerId() }
            .toList()
        if (pending.all(::deleteMarked)) CredentialWriteResult.Success else CredentialWriteResult.Failure
    }
    private fun deleteMarked(serverId: String): Boolean {
        val removed = preferences.edit().remove(cipherKey(serverId, "token")).remove(ivKey(serverId, "token"))
            .remove(cipherKey(serverId, "api")).remove(ivKey(serverId, "api"))
            .remove(cipherKey(serverId, "stage.token")).remove(ivKey(serverId, "stage.token"))
            .remove(cipherKey(serverId, "stage.api")).remove(ivKey(serverId, "stage.api"))
            .remove(stageKey(serverId)).commit()
        return removed && preferences.edit().remove(deleteKey(serverId)).commit()
    }
    private fun editSecret(edit: android.content.SharedPreferences.Editor, serverId: String, kind: String, value: String?) {
        if (value.isNullOrBlank()) { edit.remove(cipherKey(serverId, kind)).remove(ivKey(serverId, kind)); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey("mcp_$serverId"))
        edit.putString(cipherKey(serverId, kind), Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
        edit.putString(ivKey(serverId, kind), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
    }
    private fun moveSecret(edit: android.content.SharedPreferences.Editor, serverId: String, from: String, to: String) {
        val cipher = preferences.getString(cipherKey(serverId, from), null); val iv = preferences.getString(ivKey(serverId, from), null)
        if (cipher == null || iv == null) edit.remove(cipherKey(serverId, to)).remove(ivKey(serverId, to)) else edit.putString(cipherKey(serverId, to), cipher).putString(ivKey(serverId, to), iv)
        edit.remove(cipherKey(serverId, from)).remove(ivKey(serverId, from))
    }
    private fun decrypt(serverId: String, kind: String): String? {
        val ciphertext = preferences.getString(cipherKey(serverId, kind), null) ?: return null
        val iv = preferences.getString(ivKey(serverId, kind), null) ?: return null
        return runCatching { Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secretKey("mcp_$serverId"), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8) }.getOrNull()
    }
    private fun secretKey(alias: String = ALIAS): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun cipherKey(serverId: String, kind: String) = "mcp.$serverId.$kind.cipher"
    private fun ivKey(serverId: String, kind: String) = "mcp.$serverId.$kind.iv"
    private fun stageKey(serverId: String) = "$MCP_PREFIX$serverId$STAGE_SUFFIX"
    private fun deleteKey(serverId: String) = "$DELETE_PREFIX$serverId"
    private fun String.isSafeServerId() = matches(Regex("[a-zA-Z0-9-]{1,80}"))
    private companion object { const val KEYSTORE = "AndroidKeyStore"; const val ALIAS = "deepseek_api_key"; const val TRANSFORMATION = "AES/GCM/NoPadding"; const val CIPHER = "ciphertext"; const val IV = "iv"; const val MCP_PREFIX = "mcp."; const val STAGE_SUFFIX = ".stage"; const val DELETE_PREFIX = "mcp.delete." }
}
