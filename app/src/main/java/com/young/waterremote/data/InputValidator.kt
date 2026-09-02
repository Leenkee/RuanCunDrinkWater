package com.young.waterremote.data

object InputValidator {
    private val mobilePattern = Regex("^1\\d{10}$")
    private val codePattern = Regex("^\\d{4,8}$")

    fun isValidMobile(value: String): Boolean = mobilePattern.matches(value.trim())

    fun isValidCode(value: String): Boolean = codePattern.matches(value.trim())
}

