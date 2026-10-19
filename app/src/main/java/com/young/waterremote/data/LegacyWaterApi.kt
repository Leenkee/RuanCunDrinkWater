package com.young.waterremote.data

import com.young.waterremote.diagnostics.DiagnosticLogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LegacyWaterApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun requestCode(mobile: String) {
        val encrypted = LegacyCrypto.encrypt(Protocol.mobilePayload(mobile))
        request(
            url = "$USER_API/sendCode",
            envelope = Protocol.envelope("sendCode", encrypted)
        ) { Protocol.decryptResponse(it) }
    }

    suspend fun login(mobile: String, code: String): UserSession {
        val encrypted = LegacyCrypto.encrypt(Protocol.loginPayload(mobile, code))
        return request(
            url = "$USER_API/loginByCode",
            envelope = Protocol.envelope("loginByCode", encrypted)
        ) { Protocol.parseSession(Protocol.decryptResponse(it), mobile) }
    }

    suspend fun loginWithPassword(mobile: String, password: String): UserSession {
        val encrypted = LegacyCrypto.encrypt(Protocol.passwordLoginPayload(mobile, password))
        return request(
            url = "$USER_API/loginByPwd",
            envelope = Protocol.envelope("loginByPwd", encrypted)
        ) { Protocol.parseSession(Protocol.decryptResponse(it), mobile) }
    }

    suspend fun fetchBalance(session: UserSession): String {
        val encrypted = LegacyCrypto.encrypt(Protocol.mobilePayload(session.mobile))
        return request(
            url = "$USER_INFO_API/getUserInfo",
            envelope = Protocol.envelope("getUserInfo", encrypted, session.token)
        ) { Protocol.parseBalanceResponse(it) }
    }

    suspend fun sendCommand(token: String, deviceKey: String): CommandReceipt {
        val encrypted = LegacyCrypto.encrypt(Protocol.devicePayload(deviceKey))
        return request(
            url = DRINK_COMMAND_API,
            envelope = Protocol.envelope("send", encrypted, token),
            includeDecryptedData = true,
            details = mapOf("deviceFingerprint" to DiagnosticLogs.deviceFingerprint(deviceKey))
        ) { Protocol.parseCommandResponse(it) }
    }

    suspend fun fetchDrinkingStatus(
        token: String,
        deviceKey: String,
        orderNumber: String
    ): DrinkingStatus {
        val encrypted = LegacyCrypto.encrypt(
            Protocol.drinkingStatusPayload(deviceKey, orderNumber)
        )
        return request(
            url = DRINKING_STATUS_API,
            envelope = Protocol.envelope("getQrDrinkingStatu", encrypted, token),
            includeDecryptedData = true,
            details = mapOf(
                "deviceFingerprint" to DiagnosticLogs.deviceFingerprint(deviceKey),
                "orderNumber" to orderNumber
            )
        ) { Protocol.parseDrinkingStatusResponse(it, orderNumber) }
    }

    private suspend fun <T> request(
        url: String,
        envelope: String,
        includeDecryptedData: Boolean = false,
        details: Map<String, Any?> = emptyMap(),
        parse: (String) -> T
    ): T =
        withContext(Dispatchers.IO) {
            val requestId = requestIds.incrementAndGet()
            val context = details + mapOf("requestId" to requestId, "endpoint" to url)
            val started = System.nanoTime()
            DiagnosticLogs.record("api.request", context)
            val body = FormBody.Builder()
                .add("gptechMsg", envelope)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept-Language", "zh-CN,zh;q=0.8")
                .header("User-Agent", USER_AGENT)
                .header("Cache-Control", "no-cache")
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    DiagnosticLogs.record("api.response", context + mapOf(
                        "httpStatus" to response.code,
                        "durationMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        "bodyBytes" to responseBody.toByteArray(Charsets.UTF_8).size,
                        "response" to Protocol.diagnosticResponse(responseBody, includeDecryptedData)
                    ))
                    if (!response.isSuccessful) {
                        throw RemoteApiException("网络请求失败（${response.code}）")
                    }
                    parse(responseBody).also { result ->
                        val parsed = when (result) {
                            is CommandReceipt -> mapOf(
                                "orderNumber" to result.orderNumber,
                                "hasOrderNumber" to !result.orderNumber.isNullOrBlank()
                            )
                            is DrinkingStatus -> mapOf("drinkingStatus" to result.name)
                            else -> emptyMap()
                        }
                        DiagnosticLogs.record("api.parsed", context + parsed)
                    }
                }
            } catch (error: Exception) {
                DiagnosticLogs.failure("api.failed", error, context + mapOf(
                    "durationMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                ))
                when (error) {
                    is CancellationException -> throw error
                    is IOException -> throw RemoteApiException("无法连接饮水机服务，请检查网络", error)
                    else -> throw error
                }
            }
        }

    private companion object {
        val requestIds = AtomicLong(0)
        const val USER_API = "http://newxiaotian.tianji-inc.com/api/v1/UserApi"
        const val USER_INFO_API = "http://newxiaotian.tianji-inc.com/api/v1/UserInfoApi"
        const val DRINK_COMMAND_API =
            "http://iot.tianji-inc.com/index.php/drinking/send_command/send"
        const val DRINKING_STATUS_API =
            "http://iot.tianji-inc.com/index.php/drinking/qrcode_drinking/getQrDrinkingStatu"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Mobile WaterRemote/1.1"
    }
}
