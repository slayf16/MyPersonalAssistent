package com.mypersonalassistent.core.credentials.impl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.core.credentials.api.CredentialWriteResult
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ciphertext and a new IV are stored privately; plaintext never reaches preferences or logs. */
class SecureCredentialRepository(context: Context) : CredentialRepository {
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
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private companion object { const val KEYSTORE = "AndroidKeyStore"; const val ALIAS = "deepseek_api_key"; const val TRANSFORMATION = "AES/GCM/NoPadding"; const val CIPHER = "ciphertext"; const val IV = "iv" }
}
