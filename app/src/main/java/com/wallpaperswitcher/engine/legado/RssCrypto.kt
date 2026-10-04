package com.wallpaperswitcher.engine.legado

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM with a Keystore-backed key, used for the subscription login data
 * (account/password typed into a `loginUi` form, plus `putLoginHeader`).
 * 阅读 encrypts its stored user info the same way; plain SharedPreferences
 * would leak credentials to any backup/root read.
 *
 * Values written by older versions are plain text: [decrypt] returns them
 * unchanged when they are not valid ciphertext, so the upgrade is seamless.
 */
internal object RssCrypto {

    private const val KEY_ALIAS = "rss_login_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey? = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    } catch (_: Throwable) {
        null
    }

    fun encrypt(value: String?): String? {
        if (value.isNullOrEmpty()) return value
        val secret = key() ?: return value
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secret) }
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            "v1:" + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (_: Throwable) {
            value
        }
    }

    fun decrypt(value: String?): String? {
        if (value.isNullOrEmpty() || !value.startsWith("v1:")) return value
        val secret = key() ?: return value
        return try {
            val packed = Base64.decode(value.substring(3), Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_BYTES)
            val body = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv)) }
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Throwable) {
            value
        }
    }
}
