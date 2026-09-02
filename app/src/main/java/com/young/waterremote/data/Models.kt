package com.young.waterremote.data

data class UserSession(
    val mobile: String,
    val token: String,
    val balance: String?
)

data class DeviceBindings(
    val hotKey: String = "",
    val coldKey: String = ""
) {
    val hasHot: Boolean get() = hotKey.isNotBlank()
    val hasCold: Boolean get() = coldKey.isNotBlank()
    val count: Int get() = listOf(hasHot, hasCold).count { it }
}

enum class WaterType(val label: String) {
    HOT("热水"),
    COLD("冷水")
}

data class CommandReceipt(val orderNumber: String?)

enum class DrinkingStatus {
    IN_PROGRESS,
    FINISHED
}

open class RemoteApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SessionInvalidException : RemoteApiException("服务器没有返回当前用户信息")
