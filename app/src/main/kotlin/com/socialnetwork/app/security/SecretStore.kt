package com.socialnetwork.app.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/** Keystore-backed session secret storage, API 21+ compatible. */
class SecretStore(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val keyStoreName: String,
    private val keyAlias: String,
    private val legacyWrappedKeyName: String,
    private val onSecretFailure: () -> Unit = {}
) {
    private val legacyRsaAlias = "${keyAlias}_rsa"

    fun ensureKey() {
        try {
            val ks = KeyStore.getInstance(keyStoreName).apply { load(null) }
            if (Build.VERSION.SDK_INT >= 23) {
                if (!ks.containsAlias(keyAlias)) {
                    val kg = KeyGenerator.getInstance("AES", "AndroidKeyStore")
                    kg.init(
                        KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                    kg.generateKey()
                }
            } else {
                ensureLegacyWrappedKey(ks)
            }
        } catch (_: Exception) {
            // Keystore failures are surfaced when a secret is actually required.
        }
    }

    private fun ensureLegacyWrappedKey(ks: KeyStore) {
        if (!ks.containsAlias(legacyRsaAlias)) {
            val kpg = KeyPairGenerator.getInstance("RSA", keyStoreName)
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(legacyRsaAlias)
                .setSubject(X500Principal("CN=mr.x"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .setKeySize(2048)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        if (prefs.getString(legacyWrappedKeyName, "").isNullOrEmpty()) {
            val kg = KeyGenerator.getInstance("AES").apply { init(128) }
            val aes = kg.generateKey()
            val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.ENCRYPT_MODE, ks.getCertificate(legacyRsaAlias).publicKey)
            }
            val wrapped = Base64.encodeToString(rsa.doFinal(aes.encoded), Base64.NO_WRAP)
            prefs.edit().putString(legacyWrappedKeyName, wrapped).apply()
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(keyStoreName).apply { load(null) }
        if (Build.VERSION.SDK_INT >= 23) {
            if (!ks.containsAlias(keyAlias)) ensureKey()
            val entry = ks.getEntry(keyAlias, null)
            if (entry !is KeyStore.SecretKeyEntry) error("مفتاح الجلسة غير متاح.")
            return entry.secretKey
        }
        ensureLegacyWrappedKey(ks)
        val wrapped = prefs.getString(legacyWrappedKeyName, "").orEmpty()
        if (wrapped.isEmpty()) error("مفتاح الجلسة القديم غير متاح.")
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.DECRYPT_MODE, ks.getKey(legacyRsaAlias, null))
        }
        return SecretKeySpec(rsa.doFinal(Base64.decode(wrapped, Base64.NO_WRAP)), "AES")
    }

    fun save(name: String, value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            prefs.edit()
                .putString(
                    name,
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                        Base64.encodeToString(encrypted, Base64.NO_WRAP)
                )
                .apply()
        } catch (e: Exception) {
            prefs.edit().remove(name).apply()
            onSecretFailure()
            throw IllegalStateException("تعذر تأمين جلسة المستخدم.", e)
        }
    }

    fun read(name: String): String {
        val stored = prefs.getString(name, "").orEmpty()
        if (stored.isEmpty()) return ""
        return try {
            val parts = stored.split(":", limit = 2)
            if (parts.size != 2) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
                )
            }
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove(name).apply()
            ""
        }
    }

    fun clear(vararg names: String) {
        val editor = prefs.edit()
        names.forEach(editor::remove)
        editor.apply()
    }
}
