package com.mobiledivecontrol.platform

import android.content.Context
import com.mobiledivecontrol.core.HousingSealTransitions.Observation
import com.mobiledivecontrol.core.HousingSealTransitions.Location
import com.mobiledivecontrol.core.HousingSealTransitions.Vacuum
import com.mobiledivecontrol.core.SealIncident
import com.mobiledivecontrol.core.SealMonitor
import com.mobiledivecontrol.core.SealReading
import com.mobiledivecontrol.core.Verification
import org.json.JSONObject

/** One preferences edit commits the comparison reading, incident, and retry count together. */
class HousingSealStore(context: Context) {
    private val prefs = context.getSharedPreferences("housing_seal_history", Context.MODE_PRIVATE)

    fun read(): SealMonitor {
        val reading = readReading("reading")
        val verification = Verification.valueOf(prefs.getString("verification", null) ?: Verification.Idle.name)
        val origin = readReading("incidentOrigin")
        val incidents = prefs.getStringSet("incidents", emptySet()).orEmpty().mapTo(mutableSetOf(), SealIncident::valueOf)
        val priorUnderwater = reading?.observation?.location == Location.Underwater || origin?.observation?.location == Location.Underwater ||
            incidents.any { it == SealIncident.UnderwaterLeak || it == SealIncident.UnsealedDive || it == SealIncident.SuspectedLoss }
        return SealMonitor(snapshot = reading, incidentOrigin = origin,
            submergedSinceSealCheck = prefs.getBoolean("submergedSinceSealCheck", priorUnderwater),
            releaseQuestion = prefs.getBoolean("releaseQuestion", false),
            lostWhileOfflineMinutes = prefs.getLong("lossMinutes", -1).takeIf { it >= 0 },
            confirmedRelease = prefs.getBoolean("confirmedRelease", false), incidents = incidents,
            disconnectReason = prefs.getString("disconnectReason", null)?.let(com.mobiledivecontrol.core.DisconnectReason::valueOf),
            failures = prefs.getInt("failures", 0), wasDisconnected = reading != null,
            verification = when (verification) {
                Verification.Testing -> Verification.Interrupted
                Verification.Inspection, Verification.Passed -> Verification.Idle
                else -> verification
            })
    }

    private fun readReading(key: String): SealReading? = prefs.getString(key, null)?.let {
        val json = JSONObject(it)
        SealReading(Observation(Location.valueOf(json.getString("location")), Vacuum.valueOf(json.getString("vacuum"))),
            json.getDouble("magnitude"), json.getDouble("depth"), json.getLong("time"), json.getDouble("ambient"), com.mobiledivecontrol.core.VacuumTrend.valueOf(
                json.optString("trend", "Unknown")), json.optBoolean("verifiedHold"))
    }

    private fun encode(reading: SealReading?): String? = reading?.let {
        JSONObject().put("location", it.observation.location.name).put("vacuum", it.observation.vacuum.name)
            .put("depth", it.depthM).put("magnitude", it.magnitudeKpa).put("time", it.timeMs)
            .put("ambient", it.ambientKpa).put("trend", it.trend.name).put("verifiedHold", it.verifiedHold).toString()
    }

    fun update(old: SealMonitor, next: SealMonitor) {
        val reading = next.snapshot ?: next.last
        val before = old.snapshot ?: old.last
        if (old.confirmedRelease == next.confirmedRelease && old.releaseQuestion == next.releaseQuestion &&
            old.submergedSinceSealCheck == next.submergedSinceSealCheck &&
            before?.verifiedHold == reading?.verifiedHold && old.incidentOrigin == next.incidentOrigin &&
            old.disconnectReason == next.disconnectReason && old.snapshot == next.snapshot && before?.observation == reading?.observation &&
            old.incidents == next.incidents && old.verification == next.verification && old.failures == next.failures) return
        prefs.edit().putBoolean("releaseQuestion", next.releaseQuestion).putString("disconnectReason", next.disconnectReason?.name).putString("reading", encode(reading))
            .putString("incidentOrigin", encode(next.incidentOrigin)).putBoolean("confirmedRelease", next.confirmedRelease)
            .putBoolean("submergedSinceSealCheck", next.submergedSinceSealCheck)
            .putString("verification", next.verification.name).putLong("lossMinutes", next.lostWhileOfflineMinutes ?: -1)
            .putStringSet("incidents", next.incidents.mapTo(mutableSetOf()) { it.name })
            .putInt("failures", next.failures).apply()
    }
}
