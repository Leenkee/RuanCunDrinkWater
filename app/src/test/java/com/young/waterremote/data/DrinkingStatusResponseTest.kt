package com.young.waterremote.data

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DrinkingStatusResponseTest {
    @Test
    fun unfinishedOrderWithoutDispatchTimeWaitsForDevice() {
        listOf("0", "\"0\"", "-1", "null", "\"invalid\"").forEach { sendTime ->
            assertEquals(
                "send_time=$sendTime must not indicate readiness",
                DrinkingStatus.WAITING_FOR_DEVICE,
                Protocol.parseDrinkingStatusResponse(response("""{"send_time":$sendTime}"""), "order-1")
            )
        }
        assertEquals(
            DrinkingStatus.WAITING_FOR_DEVICE,
            Protocol.parseDrinkingStatusResponse(response("{}"), "order-1")
        )
    }

    @Test
    fun unfinishedOrderWithDispatchTimeReportsInProgress() {
        listOf("1794119824", "\"1794119824\"").forEach { sendTime ->
            assertEquals(
                DrinkingStatus.IN_PROGRESS,
                Protocol.parseDrinkingStatusResponse(response("""{"send_time":$sendTime}"""), "order-1")
            )
        }
    }

    @Test
    fun missingOrUnreadableStatusDataDoesNotIndicateReadiness() {
        listOf(
            """{"code":-1,"msg":"订单还未结束！"}""",
            """{"code":-1,"msg":"订单还未结束！","data":"invalid-ciphertext"}""",
            response("not JSON")
        ).forEach { body ->
            assertThrows(RemoteApiException::class.java) {
                Protocol.parseDrinkingStatusResponse(body, "order-1")
            }
        }
    }

    @Test
    fun openOrderInSuccessfulResponseStillRequiresDispatchTime() {
        listOf(
            """{"order":{"order_sn":"order-1","order_state":"1","send_time":0}}""",
            """{"order":{"order_sn":"order-1","order_state":"1"}}"""
        ).forEach { data ->
            assertEquals(
                DrinkingStatus.WAITING_FOR_DEVICE,
                Protocol.parseDrinkingStatusResponse(response(data, code = 200), "order-1")
            )
        }
        assertEquals(
            DrinkingStatus.IN_PROGRESS,
            Protocol.parseDrinkingStatusResponse(
                response(
                    """{"order":{"order_sn":"order-1","order_state":"1","send_time":1794119824}}""",
                    code = 200
                ),
                "order-1"
            )
        )
    }

    @Test
    fun completedOrderDoesNotWaitForDispatchTime() {
        assertEquals(
            DrinkingStatus.FINISHED,
            Protocol.parseDrinkingStatusResponse(
                response("""{"order":{"order_sn":"order-1","order_state":"4","send_time":0}}""", code = 200),
                "order-1"
            )
        )
    }

    @Test
    fun mismatchedOrderCannotConfirmReadinessOrCompletion() {
        listOf("1", "4").forEach { orderState ->
            val body = response(
                """{"order":{"order_sn":"another-order","order_state":"$orderState","send_time":1794119824}}""",
                code = 200
            )
            assertThrows(RemoteApiException::class.java) {
                Protocol.parseDrinkingStatusResponse(body, "order-1")
            }
        }
    }

    private fun response(data: String, code: Int = -1): String = JsonObject().apply {
        addProperty("code", code)
        addProperty("msg", if (code == -1) "订单还未结束！" else "获取成功！")
        addProperty("data", LegacyCrypto.encrypt(data))
    }.toString()
}
