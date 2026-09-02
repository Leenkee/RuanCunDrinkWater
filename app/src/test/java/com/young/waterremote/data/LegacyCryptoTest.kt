package com.young.waterremote.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun registrationPayload_matchesCapturedFieldStructure() {
        val payload = Protocol.registrationPayload(
            mobile = "13800138000",
            code = "123456",
            password = "example-password@1"
        )
        val json = JsonParser.parseString(payload).asJsonObject

        assertEquals("123456", json.get("code").asString)
        assertEquals("", json.get("invite_code").asString)
        assertEquals("13800138000", json.get("mobile").asString)
        assertEquals("example-password@1", json.get("password").asString)
        assertFalse(json.has("pwd"))
    }

    @Test
    fun registrationCodePayload_usesRegisterPurpose() {
        val json = JsonParser.parseString(
            Protocol.mobilePayload("13800138000", type = "register")
        ).asJsonObject

        assertEquals("13800138000", json.get("mobile").asString)
        assertEquals("register", json.get("type").asString)
    }
}
