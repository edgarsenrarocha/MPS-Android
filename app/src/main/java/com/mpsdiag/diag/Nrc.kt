package com.mpsdiag.diag

/** Negative response codes (ISO 14229 / ISO 14230). */
object Nrc {
    const val RESPONSE_PENDING = 0x78

    fun describe(code: Int): String = when (code) {
        0x10 -> "general reject"
        0x11 -> "service not supported"
        0x12 -> "sub-function not supported"
        0x13 -> "incorrect message length"
        0x21 -> "busy, repeat request"
        0x22 -> "conditions not correct"
        0x24 -> "request sequence error"
        0x31 -> "request out of range"
        0x33 -> "security access denied"
        0x35 -> "invalid key"
        0x36 -> "exceeded number of attempts"
        0x37 -> "required time delay not expired"
        0x72 -> "general programming failure"
        0x78 -> "response pending"
        0x7E -> "sub-function not supported in active session"
        0x7F -> "service not supported in active session"
        else -> "code %02X".format(code)
    }.let { "%s (NRC %02X)".format(it, code) }
}
