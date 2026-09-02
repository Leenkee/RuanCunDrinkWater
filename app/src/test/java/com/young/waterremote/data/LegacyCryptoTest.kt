package com.young.waterremote.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCryptoTest {
    @Test
    fun encryptAndDecrypt_roundTripsUtf8Payload() {
        val original = "{\"mobile\":\"13800138000\",\"type\":\"login\"}"

        val encrypted = LegacyCrypto.encrypt(original)

        assertFalse(encrypted.contains(original))
        assertEquals(original, LegacyCrypto.decrypt(encrypted))
    }

    @Test
    fun decryptResponse_parsesEncryptedData() {
        val userJson = """{"mobile":"13800138000","token":"token-1","wallet":{"balance":"12.50"}}"""
        val response = JsonObject().apply {
            addProperty("data", LegacyCrypto.encrypt(userJson))
        }.toString()

        val decrypted = Protocol.decryptResponse(response)
        val session = Protocol.parseSession(decrypted, "")

        assertEquals("13800138000", session.mobile)
        assertEquals("token-1", session.token)
        assertEquals("12.50", session.balance)
    }

    @Test
    fun inputValidator_acceptsOnlyExpectedLoginInputs() {
        assertTrue(InputValidator.isValidMobile("13800138000"))
        assertFalse(InputValidator.isValidMobile("1380013800"))
        assertFalse(InputValidator.isValidMobile("23800138000"))
        assertTrue(InputValidator.isValidCode("123456"))
        assertFalse(InputValidator.isValidCode("12a456"))
        assertTrue(InputValidator.isValidPassword("example-password@1"))
        assertFalse(InputValidator.isValidPassword(""))
    }

    @Test
    fun passwordLoginPayload_usesLegacyPwdField() {
        val payload = Protocol.passwordLoginPayload(
            mobile = "13800138000",
            password = "example-password@1"
        )
        val json = JsonParser.parseString(payload).asJsonObject

        assertEquals("13800138000", json.get("mobile").asString)
        assertEquals("example-password@1", json.get("pwd").asString)
        assertFalse(json.has("password"))
    }

    @Test
    fun drinkingStatusPayload_usesDeviceKeyAndOrderNumber() {
        val payload = JsonParser.parseString(
            Protocol.drinkingStatusPayload(
                deviceKey = "http://qr.example/device-1",
                orderNumber = "order-1"
            )
        ).asJsonObject

        assertEquals("http://qr.example/device-1", payload.get("device_key").asString)
        assertEquals("order-1", payload.get("order_sn").asString)
    }

    @Test
    fun drinkingStatusResponse_reportsInProgressForUnfinishedOrder() {
        val response = JsonObject().apply {
            addProperty("code", -1)
            addProperty("msg", "订单还未结束！")
            addProperty("data", LegacyCrypto.encrypt("{\"send_time\":1788366208}"))
        }.toString()

        assertEquals(
            DrinkingStatus.IN_PROGRESS,
            Protocol.parseDrinkingStatusResponse(response, "order-1")
        )
    }

    @Test
    fun drinkingStatusResponse_reportsFinishedForMatchingClosedOrder() {
        val decrypted = """{"order":{"order_sn":"order-1","order_state":"4"}}"""
        val response = JsonObject().apply {
            addProperty("code", 200)
            addProperty("msg", "获取成功！")
            addProperty("data", "\"${LegacyCrypto.encrypt(decrypted)}\"")
        }.toString()

        assertEquals(
            DrinkingStatus.FINISHED,
            Protocol.parseDrinkingStatusResponse(response, "order-1")
        )
    }

    @Test
    fun balanceResponse_reportsInvalidSessionWhenUserInfoIsMissing() {
        val response = JsonObject().apply {
            addProperty("code", -1)
            addProperty("msg", "未获取到用户信息")
        }.toString()

        assertThrows(SessionInvalidException::class.java) {
            Protocol.parseBalanceResponse(response)
        }
    }

}
