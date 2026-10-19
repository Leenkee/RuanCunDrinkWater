package com.young.waterremote.diagnostics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.security.MessageDigest
import java.util.Locale

internal object DiagnosticRedactor {
    private const val REDACTED = "[redacted]"
    private val privateKeys = listOf(
        "token", "password", "pwd", "secret", "mobile", "phone", "authorization",
        "cookie", "openid", "unionid", "email", "identity", "address", "username",
        "realname", "nickname", "devicekey", "userid", "uid", "uuid", "account", "name"
    )
    private val phoneNumber = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val credentialAssignment = Regex(
        "(?i)(token|password|pwd|secret|authorization|device_key)\\s*[:=]\\s*[^\\s,;]+"
    )
    private val verificationDigits = Regex("\\d{4,8}")

    fun sanitize(value: JsonElement): JsonElement = when {
        value.isJsonObject -> JsonObject().apply {
            value.asJsonObject.entrySet().forEach { (key, child) ->
                val normalized = key.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
                // A response's numeric business code is useful; verification codes are private.
                val verificationCode = normalized in setOf("smscode", "verifycode", "verificationcode", "captcha") ||
                    (normalized == "code" && child.isJsonPrimitive && child.asJsonPrimitive.isString &&
                        child.asString.matches(verificationDigits))
                add(key, if (verificationCode || privateKeys.any(normalized::contains)) {
                    JsonPrimitive(REDACTED)
                } else sanitize(child))
            }
        }
        value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(sanitize(it)) } }
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
            val raw = value.asString
            val nested = if (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("[")) {
                runCatching { JsonParser.parseString(raw) }.getOrNull()
            } else null
            JsonPrimitive(if (nested != null) sanitize(nested).toString() else sanitizeText(raw))
        }
        else -> value.deepCopy()
    }

    fun sanitizeText(value: String): String = credentialAssignment.replace(
        phoneNumber.replace(value, "[phone]"), "$1=$REDACTED"
    ).take(32_768)

    fun deviceFingerprint(deviceKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(deviceKey.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it) }
}
