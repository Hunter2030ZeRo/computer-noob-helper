package com.example.commaengdoughme

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-provider credentials encrypted with an app-owned Android Keystore key. */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("model_credentials", Context.MODE_PRIVATE)
    private val alias = "comma-model-config-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun selected() = runCatching { ModelProvider.fromId(prefs.getString("selected", "gemini")!!) }.getOrDefault(ModelProvider.GEMINI)
    fun select(provider: ModelProvider) { check(prefs.edit().putString("selected", provider.id).commit()) }
    fun save(config: ProviderConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(config.provider.id.toByteArray()) }
        val data = cipher.doFinal(config.encode().toByteArray())
        check(prefs.edit().putString(config.provider.id, Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP))
            .putString("selected", config.provider.id).commit())
    }
    fun load(provider: ModelProvider): ProviderConfig? {
        val encoded = prefs.getString(provider.id, null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            updateAAD(provider.id.toByteArray())
        }
        return ProviderConfig.parse(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
    }
    fun delete(provider: ModelProvider) { check(prefs.edit().remove(provider.id).commit()) }
}
