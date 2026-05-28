package com.mobiledivecontrol.core

fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
}

fun ByteArray.toSpacedHexString(): String = joinToString(separator = " ") { byte ->
    (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
}

fun UByte.toHexString(): String = "0x" + toInt().toString(16).uppercase().padStart(2, '0')

fun UShort.toHexString(): String = "0x" + toInt().toString(16).uppercase().padStart(4, '0')

fun parseHexPayload(tokens: List<String>): ByteArray {
    require(tokens.isNotEmpty()) { "Payload requires at least one hex token." }

    val digits = tokens.joinToString(separator = "") { token ->
        token.trim()
            .removePrefix("0x")
            .removePrefix("0X")
            .replace(",", "")
            .replace("_", "")
    }

    require(digits.isNotEmpty()) { "Payload cannot be empty." }
    require(digits.length % 2 == 0) { "Payload hex must contain an even number of digits." }

    return digits
        .chunked(2)
        .map { chunk -> chunk.toInt(16).toByte() }
        .toByteArray()
}

fun parseHexByte(token: String): Byte {
    val payload = parseHexPayload(listOf(token))
    require(payload.size == 1) { "Button value must fit in one byte." }
    return payload[0]
}
