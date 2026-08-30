package com.mobiledivecontrol.ui.camera

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class SkyGuidePoint(
    val constellation: String,
    val star: String,
    val x: Float,
    val y: Float,
)

internal data class SkyGuideSegment(
    val from: SkyGuidePoint,
    val to: SkyGuidePoint,
)

internal data class SkyGuideProjection(
    val points: List<SkyGuidePoint>,
    val segments: List<SkyGuideSegment>,
)

private data class CatalogueStar(
    val constellation: String,
    val name: String,
    val rightAscensionHours: Double,
    val declinationDegrees: Double,
)

/**
 * Small offline bright-star catalogue used by the Expert RAW Sky Guide. Coordinates are J2000;
 * sub-degree precession is immaterial at this preview scale. No network or cloud ephemeris is
 * needed, and observer coordinates never leave the device.
 */
internal object SkyGuideAstronomy {
    private val stars = listOf(
        CatalogueStar("Orion", "Betelgeuse", 5.9195, 7.4071),
        CatalogueStar("Orion", "Bellatrix", 5.4189, 6.3497),
        CatalogueStar("Orion", "Mintaka", 5.5334, -0.2991),
        CatalogueStar("Orion", "Alnilam", 5.6036, -1.2019),
        CatalogueStar("Orion", "Alnitak", 5.6793, -1.9426),
        CatalogueStar("Orion", "Saiph", 5.7959, -9.6696),
        CatalogueStar("Orion", "Rigel", 5.2423, -8.2016),
        CatalogueStar("Big Dipper", "Dubhe", 11.0621, 61.7508),
        CatalogueStar("Big Dipper", "Merak", 11.0307, 56.3824),
        CatalogueStar("Big Dipper", "Phecda", 11.8972, 53.6948),
        CatalogueStar("Big Dipper", "Megrez", 12.2571, 57.0326),
        CatalogueStar("Big Dipper", "Alioth", 12.9005, 55.9598),
        CatalogueStar("Big Dipper", "Mizar", 13.3987, 54.9254),
        CatalogueStar("Big Dipper", "Alkaid", 13.7923, 49.3133),
        CatalogueStar("Cassiopeia", "Caph", 0.1529, 59.1498),
        CatalogueStar("Cassiopeia", "Schedar", 0.6751, 56.5373),
        CatalogueStar("Cassiopeia", "Navi", 0.9451, 60.7167),
        CatalogueStar("Cassiopeia", "Ruchbah", 1.4303, 60.2353),
        CatalogueStar("Cassiopeia", "Segin", 1.9066, 63.6701),
    )

    private val links = listOf(
        "Betelgeuse" to "Bellatrix",
        "Betelgeuse" to "Alnitak",
        "Bellatrix" to "Mintaka",
        "Mintaka" to "Alnilam",
        "Alnilam" to "Alnitak",
        "Alnitak" to "Saiph",
        "Saiph" to "Rigel",
        "Rigel" to "Mintaka",
        "Dubhe" to "Merak",
        "Merak" to "Phecda",
        "Phecda" to "Megrez",
        "Megrez" to "Dubhe",
        "Megrez" to "Alioth",
        "Alioth" to "Mizar",
        "Mizar" to "Alkaid",
        "Caph" to "Schedar",
        "Schedar" to "Navi",
        "Navi" to "Ruchbah",
        "Ruchbah" to "Segin",
    )

    fun project(
        epochMillis: Long,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        cameraAzimuthDegrees: Double,
        cameraAltitudeDegrees: Double,
        horizontalFovDegrees: Double = 70.0,
        verticalFovDegrees: Double = 44.0,
    ): SkyGuideProjection {
        val latitude = latitudeDegrees.toRadians()
        val localSiderealDegrees = localSiderealDegrees(epochMillis, longitudeDegrees)
        val projected = stars.mapNotNull { star ->
            val hourAngle = wrapSignedDegrees(
                localSiderealDegrees - star.rightAscensionHours * 15.0,
            ).toRadians()
            val declination = star.declinationDegrees.toRadians()
            val altitude = asin(
                sin(declination) * sin(latitude) +
                    cos(declination) * cos(latitude) * cos(hourAngle),
            )
            val azimuth = atan2(
                -sin(hourAngle) * cos(declination),
                sin(declination) * cos(latitude) -
                    cos(declination) * sin(latitude) * cos(hourAngle),
            )
            val azimuthDegrees = wrapDegrees(Math.toDegrees(azimuth))
            val altitudeDegrees = Math.toDegrees(altitude)
            val horizontalOffset = wrapSignedDegrees(azimuthDegrees - cameraAzimuthDegrees)
            val verticalOffset = altitudeDegrees - cameraAltitudeDegrees
            val margin = 0.08
            val x = 0.5 + horizontalOffset / horizontalFovDegrees
            val y = 0.5 - verticalOffset / verticalFovDegrees
            if (x !in -margin..(1.0 + margin) || y !in -margin..(1.0 + margin)) return@mapNotNull null
            SkyGuidePoint(
                constellation = star.constellation,
                star = star.name,
                x = x.toFloat(),
                y = y.toFloat(),
            )
        }
        val byName = projected.associateBy { it.star }
        return SkyGuideProjection(
            points = projected,
            segments = links.mapNotNull { (from, to) ->
                val a = byName[from] ?: return@mapNotNull null
                val b = byName[to] ?: return@mapNotNull null
                SkyGuideSegment(a, b)
            },
        )
    }

    internal fun localSiderealDegrees(epochMillis: Long, longitudeDegrees: Double): Double {
        val julianDate = epochMillis / 86_400_000.0 + 2_440_587.5
        val daysSinceJ2000 = julianDate - 2_451_545.0
        val greenwich = 280.46061837 + 360.98564736629 * daysSinceJ2000
        return wrapDegrees(greenwich + longitudeDegrees)
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
    private fun wrapDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun wrapSignedDegrees(value: Double): Double {
        val wrapped = wrapDegrees(value)
        return if (wrapped > 180.0) wrapped - 360.0 else wrapped
    }
}
