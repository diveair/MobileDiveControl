package com.mobiledivecontrol.core

fun jsonObject(vararg pairs: Pair<String, Any?>): String {
    return pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${escapeJson(key)}\":${jsonValue(value)}"
    }
}

private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${escapeJson(value)}\""
    is Number, is Boolean -> value.toString()
    is Enum<*> -> "\"${escapeJson(value.name)}\""
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
    else -> "\"${escapeJson(value.toString())}\""
}

fun escapeJson(value: String): String {
    val builder = StringBuilder(value.length + 8)
    value.forEach { character ->
        when (character) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> {
                if (character.code < 0x20) {
                    builder.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(character)
                }
            }
        }
    }
    return builder.toString()
}
