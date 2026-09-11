package dev.dominikstahl.audioscribe.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dev.dominikstahl.audioscribe.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SecureKeyManager"
        private const val PREFS_NAME = "audioscribe_secure_prefs"
        private const val KEY_ENCRYPTED_API_KEY = "encrypted_gemini_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AudioScribeMasterKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        val AVAILABLE_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.5-transcribe",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-3.8-flash"
        )
    }

    init {
        ensureKeyStoreKey()
    }

    private fun ensureKeyStoreKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEY_STORE
                )
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AndroidKeyStore key", e)
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            val keyEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            keyEntry?.secretKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve SecretKey", e)
            null
        }
    }

    fun saveApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            deleteApiKey()
            return
        }

        try {
            val secretKey = getSecretKey() ?: return
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            // Combine IV (12 bytes) + cipherText into single blob
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            val base64 = Base64.encodeToString(combined, Base64.NO_WRAP)
            prefs.edit().putString(KEY_ENCRYPTED_API_KEY, base64).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt and save API key", e)
        }
    }

    fun getApiKey(): String? {
        val base64 = prefs.getString(KEY_ENCRYPTED_API_KEY, null)
        if (!base64.isNullOrEmpty()) {
            try {
                val combined = Base64.decode(base64, Base64.NO_WRAP)
                if (combined.size > GCM_IV_LENGTH) {
                    val iv = ByteArray(GCM_IV_LENGTH)
                    val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
                    System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
                    System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

                    val secretKey = getSecretKey() ?: return null
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                    val decrypted = cipher.doFinal(cipherText)
                    return String(decrypted, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt API key", e)
            }
        }

        // Fallback: check if workspace injected key exists in BuildConfig
        val buildKey = BuildConfig.GEMINI_API_KEY
        if (!buildKey.isNullOrBlank() && buildKey != "MY_GEMINI_API_KEY" && buildKey != "PLACEHOLDER") {
            return buildKey
        }

        return null
    }

    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }

    fun isUsingWorkspaceKey(): Boolean {
        val stored = prefs.getString(KEY_ENCRYPTED_API_KEY, null)
        if (stored.isNullOrEmpty()) {
            val buildKey = BuildConfig.GEMINI_API_KEY
            return !buildKey.isNullOrBlank() && buildKey != "MY_GEMINI_API_KEY"
        }
        return false
    }

    fun getWorkspaceKey(): String? {
        val buildKey = BuildConfig.GEMINI_API_KEY
        if (!buildKey.isNullOrBlank() && buildKey != "MY_GEMINI_API_KEY") {
            return buildKey
        }
        return null
    }

    fun deleteApiKey() {
        prefs.edit().remove(KEY_ENCRYPTED_API_KEY).apply()
    }

    fun getSelectedModel(): String {
        val saved = prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        return if (AVAILABLE_MODELS.contains(saved)) saved else DEFAULT_MODEL
    }

    fun setSelectedModel(model: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, model.trim()).apply()
    }
}
