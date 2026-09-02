package com.young.waterremote.data

import android.os.Build
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal object Protocol {
    private val messageSequence = AtomicLong(0)

    fun mobilePayload(mobile: String): String = JsonObject().apply {
        addProperty("mobile", mobile)
        addProperty("type", "login")
    }.toString()

    fun loginPayload(mobile: String, code: String): String = JsonObject().apply {
        addProperty("code", code)
        addProperty("mobile", mobile)
    }.toString()

    fun passwordLoginPayload(mobile: String, password: String): String = JsonObject().apply {
        addProperty("mobile", mobile)
        addProperty("pwd", password)
    }.toString()

    fun devicePayload(deviceKey: String): String = JsonObject().apply {
        addProperty("device_key", deviceKey)
    }.toString()

    fun envelope(act: String, encryptedData: String, token: String = ""): String {
        val now = System.currentTimeMillis()
        val header = JsonObject().apply {
            addProperty("act", act)
            addProperty("device_type", "android")
            addProperty("msg_id", now * 1_000 + messageSequence.incrementAndGet() % 1_000)
            addProperty(
                "source_model",
                "${Build.MANUFACTURER.lowercase(Locale.ROOT)}_${Build.MODEL}"
            )
            addProperty("source_sys_version", Build.DISPLAY)
            addProperty("source_version", "1.4.26")
            addProperty("token", token)
            addProperty("uuid", now / 1_000)
        }
        return JsonObject().apply {
            addProperty("data", encryptedData)
            add("header", header)
        }.toString()
    }

    fun decryptResponse(body: String): String {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw RemoteApiException("服务器返回了无法识别的数据", it) }

        val data = root.get("data")
        if (data == null || data.isJsonNull) {
            throw RemoteApiException(serverMessage(root))
        }

        val encrypted = runCatching { data.asString }
            .getOrElse { throw RemoteApiException(serverMessage(root)) }
            .trim()
            .removeSurrounding("\"")
            .replace("\\", "")
            .replace("\"", "")

        if (encrypted.isBlank() || encrypted.equals("null", ignoreCase = true)) {
            throw RemoteApiException(serverMessage(root))
        }

        return runCatching { LegacyCrypto.decrypt(encrypted) }
            .getOrElse { throw RemoteApiException(serverMessage(root, "响应解密失败"), it) }
    }

    fun parseSession(json: String, fallbackMobile: String): UserSession {
        return parseSessionOrNull(json, fallbackMobile)
            ?: throw RemoteApiException("登录响应中没有令牌")
    }

    fun parseSessionOrNull(json: String, fallbackMobile: String): UserSession? {
        val root = runCatching { parseDecryptedObject(json) }.getOrNull() ?: return null
        val token = root.stringValue("token") ?: return null
        return UserSession(
            mobile = root.stringValue("mobile") ?: fallbackMobile,
            token = token,
            balance = root.getAsJsonObject("wallet")?.stringValue("balance")
        )
    }

    fun parseBalance(json: String): String {
        val root = parseDecryptedObject(json)
        return root.getAsJsonObject("wallet")?.stringValue("balance")
            ?: throw RemoteApiException("服务器没有返回余额")
    }

    fun parseReceipt(json: String): CommandReceipt {
        val root = parseDecryptedObject(json)
        return CommandReceipt(orderNumber = root.stringValue("order_sn"))
    }

    private fun parseDecryptedObject(json: String): JsonObject = runCatching {
        JsonParser.parseString(json).asJsonObject
    }.getOrElse { throw RemoteApiException("解密后的响应格式不正确", it) }

    private fun serverMessage(root: JsonObject, fallback: String = "服务器拒绝了请求"): String {
        return sequenceOf("msg", "message", "error", "info")
            .mapNotNull { name -> root.stringValue(name) }
            .firstOrNull { it.isNotBlank() }
            ?: fallback
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asString }.getOrNull()
    }
}
