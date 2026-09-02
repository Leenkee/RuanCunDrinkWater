package com.young.waterremote.data

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Compatibility codec used by the original XiaoTian Android client. */
object LegacyCrypto {
    private const val KEY = "5yoOxt9w"
    private const val IV = "20190829"

    fun encrypt(content: String): String {
        val cipher = newCipher(Cipher.ENCRYPT_MODE)
        val encrypted = cipher.doFinal(content.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun decrypt(base64Content: String): String {
        val cipher = newCipher(Cipher.DECRYPT_MODE)
        val decoded = Base64.getDecoder().decode(base64Content)
        return String(cipher.doFinal(decoded), StandardCharsets.UTF_8)
    }

    private fun newCipher(mode: Int): Cipher = Cipher.getInstance("DES/CBC/PKCS5Padding").apply {
        init(
            mode,
            SecretKeySpec(KEY.toByteArray(StandardCharsets.UTF_8), "DES"),
            IvParameterSpec(IV.toByteArray(StandardCharsets.UTF_8))
        )
    }
}

