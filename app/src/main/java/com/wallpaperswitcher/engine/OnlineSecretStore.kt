package com.wallpaperswitcher.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.wallpaperswitcher.util.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV password at rest: AES-256/GCM with a key that lives in the Android
 * Keystore and never leaves it. The database only ever sees
 * `base64(iv):base64(ciphertext)`.
 *
 * Privacy notes:
 *  - the plaintext password is never written to the database, a preference,
 *    the config export or the runtime log;
 *  - if the Keystore entry is lost (app data kept but keystore reset, or a
 *    restore onto another device) [decrypt] returns "" instead of crashing -
 *    the source then reports "需要重新填写密码" and the user re-enters it.
 *
 * No new dependency: this is the platform Keystore, not security-crypto.
 */
object OnlineSecretStore {

    private const val TAG = "OnlineSecret"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "wallpaper_switcher_online_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** Empty input stays empty (a source without credentials). */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (t: Throwable) {
            // Never let a keystore problem crash a settings save; the caller
            // stores "" and the UI tells the user to re-enter the password.
            AppLog.e(TAG, "Password encryption failed", t)
            ""
        }
    }

    /** Returns "" when the blob is empty, malformed or undecryptable. */
    fun decrypt(blob: String): String {
        if (blob.isEmpty()) return ""
        val parts = blob.split(':')
        if (parts.size != 2) return ""
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLog.w(TAG, "WebDAV password could not be decrypted (keystore reset?)")
            ""
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
