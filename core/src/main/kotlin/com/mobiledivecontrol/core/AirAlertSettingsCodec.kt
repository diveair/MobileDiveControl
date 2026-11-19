package com.mobiledivecontrol.core

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/** Versioned settings only. Never persist live packets, gauge confirmations, or gas estimates. */
object AirAlertSettingsCodec {
    fun encode(s: AirAlertSettings): String {
        val p = Properties()
        fun put(key: String, value: Any) { p.setProperty(key, value.toString()) }
        put("version", 6)
        put("message", s.message)
        s.sequenceMessages.forEach { (time, message) -> put("message.$time", message) }
        s.sequenceActions.forEach { (time, action) -> put("action.$time", action.name) }
        put("enabled", s.enabled); put("schedule", s.schedule.name); put("interval", s.intervalSeconds)
        put("times", s.timesSeconds.joinToString(",")); put("confirmation", s.confirmation.name)
        put("every", s.confirmEvery); put("numbers", s.confirmationNumbers.joinToString(","))
        put("duration", s.displaySeconds); put("auto", s.autoDismiss); put("opacity", s.opacityPercent)
        put("colour", s.colour.name); put("flash", s.flash); put("unit", s.unit.name); put("source", s.source.name)
        put("sliderMin", s.sliderMinBar); put("sliderMax", s.sliderMaxBar)
        put("start", s.startBar); put("reserve", s.reserveBar)
        put("hud", s.hudMode != AirHudMode.Off); put("hudMode", s.hudMode.name); put("stale", s.staleSeconds)
        val m = s.mapping
        put("address", m.address); put("name", m.deviceName); put("service", m.service); put("characteristic", m.characteristic)
        put("format", m.format.name); put("little", m.littleEndian); put("byte", m.byteOffset)
        put("scale", m.scale); put("offset", m.offset); put("signalUnit", m.unit.name); put("verified", m.verified)
        put("valueCount", s.receiverValues.size)
        s.receiverValues.forEachIndexed { i, v ->
            val k = "value.$i."
            put(k + "label", v.label); put(k + "unit", v.unit)
            val map = v.mapping
            put(k + "address", map.address); put(k + "name", map.deviceName); put(k + "signalUnit", map.unit.name)
            put(k + "service", map.service); put(k + "characteristic", map.characteristic)
            put(k + "format", map.format.name); put(k + "little", map.littleEndian); put(k + "byte", map.byteOffset)
            put(k + "scale", map.scale); put(k + "offset", map.offset)
        }
        return StringWriter().also { p.store(it, "Dive Alerts v6") }.toString()
    }
    fun decode(text: String): AirAlertSettings {
        require(text.length <= 16_384)
        val p = Properties().apply { load(StringReader(text)) }
        fun str(k: String) = requireNotNull(p.getProperty(k))
        fun bool(k: String) = str(k).toBooleanStrict()
        fun int(k: String, range: IntRange) = str(k).toInt().also { require(it in range) }
        fun number(k: String, min: Double, max: Double) = str(k).toDouble().also { require(it.isFinite() && it in min..max) }
        fun list(k: String, range: IntRange) = str(k).split(',').also { require(it.size in 1..24) }.map { it.toInt().also { n -> require(n in range) } }
        val version = int("version", 1..6)
        val mapping = AirPressureMapping(str("address"), str("name"), str("service"), str("characteristic"),
            PressureFormat.valueOf(str("format")), bool("little"), int("byte", 0..508), number("scale", 0.000001, 10000.0),
            number("offset", -10000.0, 10000.0), AirPressureUnit.valueOf(str("signalUnit")), bool("verified"))
        if (mapping.verified) {
            require(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").matches(mapping.address))
            java.util.UUID.fromString(mapping.service); java.util.UUID.fromString(mapping.characteristic)
        }
        val s = AirAlertSettings(bool("enabled"), AirSchedule.valueOf(str("schedule")), int("interval", 30..21_600),
            list("times", 60..21_600), AirConfirmation.valueOf(str("confirmation")), int("every", 1..100), list("numbers", 1..999),
            int("duration", 5..120), bool("auto"), int("opacity", 20..100), AirColour.valueOf(str("colour")), bool("flash"),
            AirPressureUnit.valueOf(str("unit")), AirSource.valueOf(str("source")), number("start", 10.0, 350.0),
            number("reserve", 10.0, 349.0),
            if (version >= 4) AirHudMode.valueOf(str("hudMode")) else if (bool("hud")) AirHudMode.TimeAndLast else AirHudMode.Off,
            int("stale", 5..60), mapping,
            sliderMinBar = if (version >= 3) number("sliderMin", 0.0, 349.0) else 0.0,
            sliderMaxBar = if (version >= 3) number("sliderMax", 1.0, 350.0) else 350.0)
        require(s.startBar > s.reserveBar)
        require(s.sliderMaxBar - s.sliderMinBar >= 1.0 - 1e-9)
        if (version == 1) return s
        fun validMessage(value: String) = value.also { require(it.isNotBlank() && it == cleanAirMessage(it)) }
        val values = if (version < 6) emptyList() else (0 until int("valueCount", 0..8)).map { i ->
            val k = "value.$i."
            val unit = str(k + "unit").also { require(it == cleanAirMessage(it)) }
            AirReceiverValue(validMessage(str(k + "label")), unit, AirPressureMapping(
                address = str(k + "address"), deviceName = str(k + "name"), unit = AirPressureUnit.valueOf(str(k + "signalUnit")),
                service = str(k + "service"), characteristic = str(k + "characteristic"),
                format = PressureFormat.valueOf(str(k + "format")), littleEndian = bool(k + "little"),
                byteOffset = int(k + "byte", 0..508), scale = number(k + "scale", 0.000001, 10000.0),
                offset = number(k + "offset", -10000.0, 10000.0)))
        }
        return s.copy(message = validMessage(str("message")), receiverValues = values,
            sequenceMessages = s.timesSeconds.mapNotNull { time -> p.getProperty("message.$time")?.let { time to validMessage(it) } }.toMap(),
            sequenceActions = s.timesSeconds.mapNotNull { time -> p.getProperty("action.$time")?.let { time to AirSequenceAction.valueOf(it) } }.toMap())
    }
}
