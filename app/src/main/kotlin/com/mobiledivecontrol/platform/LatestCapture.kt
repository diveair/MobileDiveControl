package com.mobiledivecontrol.platform

/** Small MediaStore record; no image pixels or Android objects are needed to choose the cover. */
internal data class LatestCapture(
    val id: Long,
    val uri: String,
    val name: String,
    val relativePath: String,
    val isVideo: Boolean,
    val capturedAtMillis: Long,
    val sizeBytes: Long,
) {
    val isRaw: Boolean get() = name.endsWith(".dng", ignoreCase = true)
    val pairKey: String get() = "$relativePath${name.substringBeforeLast('.')}"
}

internal fun latestCaptureCover(candidates: List<LatestCapture>): LatestCapture? {
    val completed = candidates.filter { it.sizeBytes > 0 }
    val newest = completed.maxWithOrNull(
        compareBy<LatestCapture> { it.capturedAtMillis }.thenBy { it.id },
    ) ?: return null
    // RAW may finish after its JPEG. Keep the renderable JPEG cover for the same exposure,
    // while still supporting RAW-only captures and never choosing an unrelated older JPEG.
    return if (newest.isRaw) completed.firstOrNull {
        !it.isRaw && !it.isVideo && it.pairKey == newest.pairKey
    } ?: newest else newest
}
