package com.example.iot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BrokerConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val tls: Boolean
) {
    fun validate(): String? = when {
        host.isBlank() -> "Ingresa la IP o el nombre local del broker."
        host.contains("://") || host.contains('/') -> "Escribe solo el host del broker, sin protocolo ni ruta."
        !host.endsWith(".local", ignoreCase = true) && !isPrivateIpv4(host) ->
            "El broker debe estar en la red local (IP privada IPv4 o nombre .local)."
        port !in 1..65535 -> "El puerto debe estar entre 1 y 65535."
        else -> null
    }

    fun uri(): String = "${if (tls) "ssl" else "tcp"}://$host:$port"

    private fun isPrivateIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return first == 10 || first == 192 && second == 168 || first == 172 && second in 16..31 ||
            first == 169 && second == 254
    }
}

class SecureBrokerConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("broker_config", Context.MODE_PRIVATE)

    fun load(): BrokerConfig {
        return BrokerConfig(
            host = preferences.getString(KEY_HOST, "").orEmpty(),
            port = preferences.getInt(KEY_PORT, if (preferences.getBoolean(KEY_TLS, false)) 8883 else 1883),
            username = decrypt(KEY_USERNAME),
            password = decrypt(KEY_PASSWORD),
            tls = preferences.getBoolean(KEY_TLS, false)
        )
    }

    fun save(config: BrokerConfig) {
        val issue = config.validate()
        require(issue == null) { issue.orEmpty() }
        preferences.edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putBoolean(KEY_TLS, config.tls)
            .putString(KEY_USERNAME, encrypt(config.username))
            .putString(KEY_PASSWORD, encrypt(config.password))
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val bytes = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$bytes"
    }

    private fun decrypt(key: String): String {
        val encoded = preferences.getString(key, null) ?: return ""
        return try {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(Base64.decode(parts[1], Base64.NO_WRAP).let(cipher::doFinal), Charsets.UTF_8)
        } catch (_: Exception) {
            preferences.edit().remove(key).apply()
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "clasificador_broker_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TLS = "tls"
        const val KEY_USERNAME = "username_encrypted"
        const val KEY_PASSWORD = "password_encrypted"
    }
}
