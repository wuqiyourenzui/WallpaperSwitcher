package com.wallpaperswitcher.engine.legado

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 阅读 `JsEncodeUtils` 的对称解密（Hutool `SymmetricCrypto`）子集：
 * 密钥/偏移量按 UTF-8 取字节（阅读传的就是 `key.encodeToByteArray()`），
 * 密文按 Base64 解，再按 UTF-8 转字符串。加密源用 `java.aesBase64DecodeToString`
 * 在运行时解密自己的规则。
 */
internal object SymmetricCrypto {

    fun decrypt(str: String, key: String, transformation: String, iv: String): String? {
        val bytes = fromBase64(str) ?: fromHex(str) ?: return null
        return try {
            val cipher = Cipher.getInstance(normalize(transformation))
            val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithmOf(transformation))
            val ivBytes = iv.toByteArray(Charsets.UTF_8)
            if (ivBytes.isNotEmpty() && !transformation.uppercase().contains("/ECB/")) {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
            } else {
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
            }
            cipher.doFinal(bytes)?.toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun encryptBase64(data: String, key: String, transformation: String, iv: String): String? =
        try {
            val cipher = Cipher.getInstance(normalize(transformation))
            val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithmOf(transformation))
            val ivBytes = iv.toByteArray(Charsets.UTF_8)
            if (ivBytes.isNotEmpty() && !transformation.uppercase().contains("/ECB/")) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            }
            java.util.Base64.getEncoder()
                .encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
        } catch (_: Throwable) {
            null
        }

    fun fromBase64(text: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(text.trim())
    } catch (_: Throwable) {
        try {
            java.util.Base64.getMimeDecoder().decode(text.trim())
        } catch (_: Throwable) {
            null
        }
    }

    fun fromHex(text: String): ByteArray? {
        val hex = text.trim()
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return try {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Android 的 `PKCS5Padding` 就是 PKCS#7，`AES/GCM/NoPadding` 需要带 tag，先按原样。 */
    private fun normalize(transformation: String): String {
        val parts = transformation.split("/")
        if (parts.size != 3) return transformation
        val mode = parts[1].uppercase()
        val padding = parts[2]
        return "${parts[0].uppercase()}/$mode/$padding"
    }

    private fun algorithmOf(transformation: String): String =
        transformation.substringBefore('/').ifBlank { "AES" }.uppercase()
}
