package com.mobiledivecontrol.ui.tutorial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * The housing artwork, decoded once per process.
 *
 * The plates are 2340x1080 manufacturer drawings. `painterResource` would decode each one as
 * ARGB_8888 — 2340 * 1080 * 4 = 10.1 MB of Java heap per plate, 20 MB for both, re-decoded every
 * time the resource cache is trimmed. The intro sits in front of a camera pipeline that is already
 * the largest allocator in the app, so that headroom is not spare.
 *
 * [Bitmap.Config.HARDWARE] keeps the pixels in graphics memory instead: nothing on the Java heap,
 * immutable, and uploaded to the GPU once rather than on every frame. Where the device or the
 * decoder refuses it — some emulators, some software-rendered paths — RGB_565 halves the heap cost
 * instead, which is lossless enough for a grayscale line drawing on black. Full ARGB_8888 is the
 * last resort so a decode failure never means a blank intro.
 *
 * Cached at process scope rather than in a composable: the intro is torn down the moment the diver
 * presses a button, and re-decoding 10 MB on the next process-less activity recreation is exactly
 * the stall that would make the first frame of the app late.
 *
 * The cache is never evicted. Two immutable plates is a bounded cost, and holding them means a
 * relaunch of the intro within the same process is free.
 */
object HousingPlates {

    private val decoded = HashMap<Int, ImageBitmap>()

    /**
     * The plate for [resourceId], decoded on first ask.
     *
     * @return null if every decode strategy failed. The caller draws the overlays without the
     *   artwork rather than crashing — a diver who can still read "TURN ON THE HOUSING" over a
     *   black screen is better served than one looking at a crash dialog.
     */
    @Synchronized
    fun plate(context: Context, resourceId: Int): ImageBitmap? {
        decoded[resourceId]?.let { return it }

        val bitmap = decodeWith(context, resourceId, Bitmap.Config.HARDWARE)
            ?: decodeWith(context, resourceId, Bitmap.Config.RGB_565)
            ?: decodeWith(context, resourceId, Bitmap.Config.ARGB_8888)

        if (bitmap == null) {
            Log.e("DiveControl", "Housing plate $resourceId could not be decoded in any config")
            return null
        }

        return bitmap.asImageBitmap().also { decoded[resourceId] = it }
    }

    /**
     * One decode attempt.
     *
     * `inScaled = false` because the plates live in `drawable-nodpi` and every overlay coordinate is
     * expressed in their native 2340x1080 pixels. Letting the framework density-scale them would
     * silently move the button highlights off the buttons on any screen that is not xhdpi.
     */
    private fun decodeWith(context: Context, resourceId: Int, config: Bitmap.Config): Bitmap? =
        runCatching {
            BitmapFactory.decodeResource(
                context.applicationContext.resources,
                resourceId,
                BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = config
                },
            )
        }.onFailure { error ->
            Log.w("DiveControl", "Housing plate $resourceId refused $config", error)
        }.getOrNull()
}

/**
 * Composition-side handle on a plate.
 *
 * Keyed on the resource id alone: the bitmap is immutable and process-scoped, so there is nothing a
 * recomposition could invalidate.
 */
@Composable
internal fun rememberHousingPlate(resourceId: Int): ImageBitmap? {
    val context = LocalContext.current
    return remember(resourceId) { HousingPlates.plate(context, resourceId) }
}
