package com.young.waterremote.diagnostics

import com.google.gson.JsonParser
import com.young.waterremote.data.LegacyCrypto
import com.young.waterremote.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun masksNestedCredentialsAndEncodedJsonWhilePreservingBusinessCodes() {
        val json = JsonParser.parseString("""
            {
              "code":200,"stringCode":{"code":"200"},"header":{"auth":"00001","token":"secret-token"},
              "users":[{"mobile":"13800138000","PWD":"secret-password","sms_code":"123456"}],
              "encoded":"{\"refresh_token\":\"nested-secret\"}",
              "device_key":"http://qr.example/private-device",
              "message":"联系 13800138000; token=echoed-secret"
            }
        """.trimIndent())

        val safe = DiagnosticRedactor.sanitize(json).asJsonObject
        val text = safe.toString()

        listOf("secret-token", "secret-password", "123456", "nested-secret", "13800138000",
            "private-device", "echoed-secret").forEach { assertFalse(it, text.contains(it)) }
        assertEquals(200, safe.get("code").asInt)
        assertEquals("200", safe.getAsJsonObject("stringCode").get("code").asString)
        assertEquals("00001", safe.getAsJsonObject("header").get("auth").asString)
    }

    @Test
    fun waterResponseKeepsMissingOrderEvidenceAndRemovesCiphertextAndPersonalData() {
        val decoded = """{"order":{"order_sn":"order-1"},"mobile":"13800138000","token":"secret-token"}"""
        val encrypted = LegacyCrypto.encrypt(decoded)
        val body = """{"code":-1,"msg":"设备忙","data":"$encrypted"}"""

        val safe = DiagnosticRedactor.sanitize(Protocol.diagnosticResponse(body, true)).asJsonObject

        assertEquals(-1, safe.get("code").asInt)
        assertEquals("设备忙", safe.get("msg").asString)
        val payload = safe.getAsJsonObject("decryptedData")
        assertFalse(payload.has("order_sn"))
        assertEquals("order-1", payload.getAsJsonObject("order").get("order_sn").asString)
        assertFalse(safe.toString().contains(encrypted))
        assertFalse(safe.toString().contains("13800138000"))
        assertFalse(safe.toString().contains("secret-token"))
    }

    @Test
    fun loginResponseNeverIncludesCiphertextOrDecryptedSession() {
        val encrypted = LegacyCrypto.encrypt("""{"token":"secret-token","pwd":"secret-password"}""")

        val safe = Protocol.diagnosticResponse("""{"code":200,"data":"$encrypted"}""", false)

        assertTrue(safe.get("hasData").asBoolean)
        assertFalse(safe.has("decryptedData"))
        assertFalse(safe.toString().contains(encrypted))
        assertFalse(safe.toString().contains("secret-token"))
    }

    @Test
    fun malformedResponseIsDescribedWithoutPersistingUnstructuredSensitiveText() {
        val safe = Protocol.diagnosticResponse("<html>password=private-password</html>", true)
        assertTrue(safe.get("format").asString.contains("invalid JSON"))
        assertFalse(safe.toString().contains("private-password"))
    }

    @Test
    fun fingerprintCorrelatesADeviceWithoutSavingItsQrCode() {
        val key = "http://qr.example/private-device"
        val fingerprint = DiagnosticRedactor.deviceFingerprint(key)
        assertEquals(12, fingerprint.length)
        assertEquals(fingerprint, DiagnosticRedactor.deviceFingerprint(key))
        assertFalse(fingerprint.contains("private-device"))
        assertFalse(fingerprint == DiagnosticRedactor.deviceFingerprint("different-device"))
    }
}
