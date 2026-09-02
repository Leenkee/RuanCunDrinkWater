package com.young.waterremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class LegacyWaterApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun requestCode(mobile: String) {
        val encrypted = LegacyCrypto.encrypt(Protocol.mobilePayload(mobile))
        post(
            url = "$USER_API/sendCode",
            envelope = Protocol.envelope("sendCode", encrypted)
        )
    }

    suspend fun requestRegistrationCode(mobile: String) {
        val encrypted = LegacyCrypto.encrypt(Protocol.mobilePayload(mobile, type = "register"))
        post(
            url = "$USER_API/sendCode",
            envelope = Protocol.envelope("sendCode", encrypted)
        )
    }

    suspend fun login(mobile: String, code: String): UserSession {
        val encrypted = LegacyCrypto.encrypt(Protocol.loginPayload(mobile, code))
        val decrypted = post(
            url = "$USER_API/loginByCode",
            envelope = Protocol.envelope("loginByCode", encrypted)
        )
        return Protocol.parseSession(decrypted, mobile)
    }

    suspend fun loginWithPassword(mobile: String, password: String): UserSession {
        val encrypted = LegacyCrypto.encrypt(Protocol.passwordLoginPayload(mobile, password))
        val decrypted = post(
            url = "$USER_API/loginByPwd",
            envelope = Protocol.envelope("loginByPwd", encrypted)
        )
        return Protocol.parseSession(decrypted, mobile)
    }

    suspend fun register(mobile: String, code: String, password: String): UserSession? {
        val encrypted = LegacyCrypto.encrypt(
            Protocol.registrationPayload(mobile, code, password)
        )
        val decrypted = post(
            url = "$USER_API/register",
            envelope = Protocol.envelope("register", encrypted)
        )
        return Protocol.parseSessionOrNull(decrypted, mobile)
    }

    suspend fun fetchBalance(session: UserSession): String {
        val encrypted = LegacyCrypto.encrypt(Protocol.mobilePayload(session.mobile))
        val decrypted = post(
            url = "$USER_INFO_API/getUserInfo",
            envelope = Protocol.envelope("getUserInfo", encrypted, session.token)
        )
        return Protocol.parseBalance(decrypted)
    }

    suspend fun sendCommand(token: String, deviceKey: String): CommandReceipt {
        val encrypted = LegacyCrypto.encrypt(Protocol.devicePayload(deviceKey))
        val decrypted = post(
            url = DRINK_COMMAND_API,
            envelope = Protocol.envelope("send", encrypted, token)
        )
        return Protocol.parseReceipt(decrypted)
    }

    private suspend fun post(url: String, envelope: String): String = withContext(Dispatchers.IO) {
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
                if (!response.isSuccessful) {
                    throw RemoteApiException("网络请求失败（${response.code}）")
                }
                Protocol.decryptResponse(responseBody)
            }
        } catch (error: RemoteApiException) {
            throw error
        } catch (error: IOException) {
            throw RemoteApiException("无法连接饮水机服务，请检查网络", error)
        }
    }

    private companion object {
        const val USER_API = "http://newxiaotian.tianji-inc.com/api/v1/UserApi"
        const val USER_INFO_API = "http://newxiaotian.tianji-inc.com/api/v1/UserInfoApi"
        const val DRINK_COMMAND_API =
            "http://iot.tianji-inc.com/index.php/drinking/send_command/send"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Mobile WaterRemote/1.1"
    }
}
