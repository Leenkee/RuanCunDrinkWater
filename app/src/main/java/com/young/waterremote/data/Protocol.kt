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

    fun drinkingStatusPayload(deviceKey: String, orderNumber: String): String =
        JsonObject().apply {
            addProperty("device_key", deviceKey)
            addProperty("order_sn", orderNumber)
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
            ?: throw RemoteApiException("账号或密码错误")
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
            ?: throw SessionInvalidException()
    }

    fun parseBalanceResponse(body: String): String {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw RemoteApiException("服务器返回了无法识别的数据", it) }
        val data = root.get("data")
        val encrypted = data?.takeUnless { it.isJsonNull }?.let { value ->
            runCatching { value.asString }.getOrNull()
        }
        if (encrypted.isNullOrBlank() || encrypted.equals("null", ignoreCase = true)) {
            throw SessionInvalidException()
        }
        return parseBalance(decryptResponse(body))
    }

    fun parseReceipt(json: String): CommandReceipt {
        val root = parseDecryptedObject(json)
        return CommandReceipt(orderNumber = root.stringValue("order_sn"))
    }

    fun parseCommandResponse(body: String): CommandReceipt {
        val response = parseResponseObject(body)
        throwIfSessionInvalid(response)
        return parseReceipt(decryptResponse(body))
    }

    /** Keep the envelope and decoded water response, never the reversible ciphertext. */
    internal fun diagnosticResponse(body: String, includeDecryptedData: Boolean): JsonObject {
        val response = runCatching { parseResponseObject(body) }.getOrNull()
            ?: return JsonObject().apply {
                addProperty("format", "non-object or invalid JSON; body omitted")
            }
        return JsonObject().apply {
            response.entrySet().filter { it.key != "data" }.forEach { (key, value) ->
                add(key, value.deepCopy())
            }
            val data = response.get("data")
            addProperty("hasData", data != null && !data.isJsonNull)
            if (data == null || data.isJsonNull) return@apply
            addProperty("dataFormat", when {
                data.isJsonObject -> "object"
                data.isJsonArray -> "array"
                else -> "primitive"
            })
            if (!includeDecryptedData) return@apply
            if (data.isJsonObject || data.isJsonArray) {
                add("unencryptedData", data.deepCopy())
            } else {
                runCatching { decryptResponse(body) }
                    .onSuccess { decoded ->
                        val json = runCatching { JsonParser.parseString(decoded) }.getOrNull()
                        if (json != null) add("decryptedData", json)
                        else addProperty("decryptedFormat", "invalid JSON; body omitted")
                    }
                    .onFailure { addProperty("decryptionError", it.javaClass.simpleName) }
            }
        }
    }

    fun parseDrinkingStatusResponse(
        body: String,
        expectedOrderNumber: String
    ): DrinkingStatus {
        val response = parseResponseObject(body)
        throwIfSessionInvalid(response)
        val code = response.get("code")?.let { value ->
            runCatching { value.asInt }.getOrNull()
        }
        val message = serverMessage(response, "")

        if (code == -1 && message.contains("订单还未结束")) {
            return DrinkingStatus.IN_PROGRESS
        }
        if (code != 200) {
            throw RemoteApiException(message.ifBlank { "无法查询出水状态" })
        }

        val decrypted = decryptResponse(body)
        val order = parseDecryptedObject(decrypted).getAsJsonObject("order")
            ?: throw RemoteApiException("出水状态响应中没有订单信息")
        val orderNumber = order.stringValue("order_sn")
            ?: throw RemoteApiException("出水状态响应中没有订单号")
        if (orderNumber != expectedOrderNumber) {
            throw RemoteApiException("出水状态返回了不匹配的订单")
        }

        return if (order.stringValue("order_state") == "4") {
            DrinkingStatus.FINISHED
        } else {
            DrinkingStatus.IN_PROGRESS
        }
    }

    private fun parseResponseObject(body: String): JsonObject = runCatching {
        JsonParser.parseString(body).asJsonObject
    }.getOrElse { throw RemoteApiException("服务器返回了无法识别的数据", it) }

    private fun throwIfSessionInvalid(response: JsonObject) {
        val auth = response.getAsJsonObject("header")?.stringValue("auth")
        val message = serverMessage(response, "")
        val normalizedMessage = message.lowercase(Locale.ROOT)
        val tokenFailure = normalizedMessage.contains("token") && sequenceOf(
            "错误",
            "失效",
            "无效",
            "过期",
            "invalid",
            "error",
            "expired"
        ).any(normalizedMessage::contains)

        if (auth?.toIntOrNull() == 1 || tokenFailure) {
            throw SessionInvalidException()
        }
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
