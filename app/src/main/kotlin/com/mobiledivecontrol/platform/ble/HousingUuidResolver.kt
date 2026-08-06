package com.mobiledivecontrol.platform.ble

import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.HousingService

/**
 * Maps the 128-bit UUIDs Android reports during service discovery onto the housing's
 * logical characteristics.
 *
 * The vendor protocol document transcribes the private base UUID as
 * `23D1BCEA-5F78-2315-DEEF-1212xxxx-00000000` — thirty-six hex digits where a UUID has
 * thirty-two. It is a byte array printed forwards with dashes inserted by hand, so no
 * hardcoded 128-bit value derived from it can be trusted. Guessing wrong means the app
 * finds no characteristics at all on real hardware and there is no way to tell from the
 * document which guess is right.
 *
 * So this resolver never compares whole UUIDs. It recovers the 16-bit short code that the
 * vendor embedded in the UUID and matches on that, which works unchanged whether the
 * housing exposes true 16-bit UUIDs, the Bluetooth SIG base, the Nordic-style base
 * (`0000XXXX-1212-EFDE-1523-785FEABCD123`, the most likely form given the firmware reuses
 * Nordic's LED Button Service short codes verbatim), the base currently guessed by
 * [com.mobiledivecontrol.core.HousingBleProfile], or some fourth arrangement.
 *
 * Pure Kotlin with no Android dependencies: this is the riskiest inference in the BLE
 * stack, so it has to be exercised by unit tests rather than by a diver.
 */
object HousingUuidResolver {

    /** Where in the UUID the short code was found — recorded so ambiguity can be logged. */
    enum class MatchSource {
        /** Canonical Bluetooth SIG form: `0000XXXX-0000-1000-8000-00805F9B34FB`, or a bare 16/32-bit UUID. */
        SigBase,

        /** Vendor base carrying the code in the leading group: `0000XXXX-<anything>`. */
        LeadingShortCode,

        /** Code found by scanning the UUID's 4-digit-aligned groups. */
        EmbeddedShortCode,
    }

    /** Outcome of resolving one discovered UUID. */
    sealed interface Match {
        /** Exactly one characteristic claims this UUID. */
        data class Unique(
            val characteristic: HousingCharacteristic,
            val source: MatchSource,
        ) : Match

        /** Several distinct short codes appear in the UUID; refusing to guess is safer than picking. */
        data class Ambiguous(val candidates: List<HousingCharacteristic>) : Match

        /** No known short code appears in the UUID. */
        data object None : Match
    }

    /**
     * Returns the characteristic this UUID denotes, or null when nothing matched or more
     * than one characteristic could claim it. Callers wanting to log *why* should use
     * [matchCharacteristic].
     */
    fun resolveCharacteristic(uuid: String): HousingCharacteristic? =
        (matchCharacteristic(uuid) as? Match.Unique)?.characteristic

    /**
     * Full resolution result, including the ambiguity detail needed for the discovery dump.
     *
     * When the UUID carries its short code in a well-known position (SIG base, or a
     * `0000XXXX-…` vendor base) that reading wins outright and no scan is performed — a
     * vendor base could itself contain a digit group that collides with a real short code,
     * and letting the scan override a positional match would map every characteristic on
     * the device to the same entry.
     */
    fun matchCharacteristic(uuid: String): Match {
        val hex = normalize(uuid) ?: return Match.None

        positionalShortCode(hex)?.let { (code, source) ->
            val characteristic = characteristicFor(code) ?: return Match.None
            return Match.Unique(characteristic, source)
        }

        if (hex.length != FULL_UUID_HEX_LENGTH) return Match.None

        val candidates = LinkedHashSet<HousingCharacteristic>()
        forEachAlignedGroup(hex) { code -> characteristicFor(code)?.let { candidates += it } }

        return when (candidates.size) {
            0 -> Match.None
            1 -> Match.Unique(candidates.first(), MatchSource.EmbeddedShortCode)
            else -> Match.Ambiguous(candidates.toList())
        }
    }

    /**
     * Returns the 16-bit short code a service UUID carries, or null when it cannot be
     * determined.
     *
     * Unknown codes are still returned when they sit in a well-known position, because the
     * discovery dump should name every service the housing exposes, not only the four the
     * protocol document lists. The positional reading matters here more than for
     * characteristics: the predicted Nordic base
     * `0000XXXX-1212-EFDE-1523-785FEABCD123` embeds `1523` — a real service code — in every
     * UUID on the device, so a pure scan would report the key service for all of them.
     */
    fun resolveServiceShortCode(uuid: String): Int? {
        val hex = normalize(uuid) ?: return null

        positionalShortCode(hex)?.let { (code, _) -> return code }

        if (hex.length != FULL_UUID_HEX_LENGTH) return null

        val candidates = LinkedHashSet<Int>()
        forEachAlignedGroup(hex) { code -> if (code in knownServiceCodes) candidates += code }
        return candidates.singleOrNull()
    }

    /** One-line human summary of a UUID for the discovery dump. */
    fun describe(uuid: String): String = when (val match = matchCharacteristic(uuid)) {
        is Match.Unique -> "${match.characteristic.label} (${match.characteristic.shortHex}) via ${match.source}"
        is Match.Ambiguous -> "ambiguous: ${match.candidates.joinToString { "${it.label} (${it.shortHex})" }}"
        Match.None -> "unmapped"
    }

    private fun positionalShortCode(hex: String): Pair<Int, MatchSource>? = when {
        hex.length == SHORT_UUID_HEX_LENGTH -> hex.toInt(16) to MatchSource.SigBase
        hex.length == MEDIUM_UUID_HEX_LENGTH && hex.startsWith(LEADING_ZEROS) ->
            hex.substring(4, 8).toInt(16) to MatchSource.SigBase
        hex.length != FULL_UUID_HEX_LENGTH -> null
        hex.startsWith(LEADING_ZEROS) && hex.substring(8) == SIG_BASE_SUFFIX ->
            hex.substring(4, 8).toInt(16) to MatchSource.SigBase
        hex.startsWith(LEADING_ZEROS) -> hex.substring(4, 8).toInt(16) to MatchSource.LeadingShortCode
        else -> null
    }

    private inline fun forEachAlignedGroup(hex: String, action: (Int) -> Unit) {
        var offset = 0
        while (offset + 4 <= hex.length) {
            action(hex.substring(offset, offset + 4).toInt(16))
            offset += 4
        }
    }

    private fun characteristicFor(code: Int): HousingCharacteristic? =
        HousingCharacteristic.from(code.toString(16).padStart(4, '0'))

    /**
     * Reduces a UUID to bare lowercase hex, or null when the input is not hex at all.
     * Accepts the dashed form Android produces, an undashed form, braces, and a `0x`
     * prefixed short code.
     */
    private fun normalize(uuid: String): String? {
        val trimmed = uuid.trim().removePrefix("{").removeSuffix("}")
        val stripped = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed.substring(2) else trimmed

        val builder = StringBuilder(FULL_UUID_HEX_LENGTH)
        for (character in stripped) {
            when {
                character == '-' || character.isWhitespace() -> Unit
                character in '0'..'9' || character.lowercaseChar() in 'a'..'f' ->
                    builder.append(character.lowercaseChar())
                else -> return null
            }
        }

        val hex = builder.toString()
        return if (hex.isEmpty()) null else hex
    }

    private val knownServiceCodes: Set<Int> =
        HousingService.entries.map { it.shortHex.toInt(16) }.toSet()

    private const val SHORT_UUID_HEX_LENGTH = 4
    private const val MEDIUM_UUID_HEX_LENGTH = 8
    private const val FULL_UUID_HEX_LENGTH = 32
    private const val LEADING_ZEROS = "0000"
    private const val SIG_BASE_SUFFIX = "00001000800000805f9b34fb"
}
