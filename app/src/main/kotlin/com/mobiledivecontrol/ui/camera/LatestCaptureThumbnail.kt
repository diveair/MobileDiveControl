package com.mobiledivecontrol.ui.camera

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mobiledivecontrol.platform.LatestCapture
import com.mobiledivecontrol.platform.LatestCaptureRepository
import com.mobiledivecontrol.theme.DiveColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val LocalCaptureCover = staticCompositionLocalOf<Pair<LatestCapture, ImageBitmap>?> { null }

/** Keep the cover and its observer outside mode-specific rails; a mode change is not a save. */
@Composable
internal fun LatestCaptureProvider(lifecycleOwner: LifecycleOwner?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { LatestCaptureRepository(context) }
    var cover by remember { mutableStateOf<Pair<LatestCapture, ImageBitmap>?>(null) }
    LaunchedEffect(repository, lifecycleOwner) {
        suspend fun observe() {
        repository.changes().collect {
            try {
                val item = withContext(Dispatchers.IO) { repository.latest() }
                if (item == null) {
                    cover = null
                } else if (item != cover?.first) {
                    // Keep the previous cover visible during IO. Some providers publish their
                    // row just before the thumbnail is ready, so retry only this new capture.
                    for (attempt in 0..2) {
                        val bitmap = withContext(Dispatchers.IO) {
                            try { repository.thumbnail(item)?.asImageBitmap() }
                            catch (error: Exception) {
                                if (error is CancellationException) throw error
                                null
                            }
                        }
                        if (bitmap != null) {
                            cover = item to bitmap
                            Log.d("DiveGalleryCover", "Cover published: ${item.name}")
                            break
                        }
                        if (attempt < 2) delay(150L * (attempt + 1))
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("DiveGalleryCover", "Unable to refresh latest capture", error)
            }
        }
        }
        if (lifecycleOwner == null) observe()
        else lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { observe() }
    }
    CompositionLocalProvider(LocalCaptureCover provides cover, content = content)
}

@Composable
internal fun LatestCaptureThumbnail(selected: Boolean, onClick: (() -> Unit)?) {
    val current = LocalCaptureCover.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DiveColors.SurfaceCard.copy(alpha = 0.65f))
            .border(2.dp, if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary, CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics {
                contentDescription = current?.let {
                    "Open gallery, latest ${if (it.first.isVideo) "video" else "photo"}: ${it.first.name}"
                } ?: "Open gallery, no thumbnail available"
            }
            .padding(3.dp)
            .clip(CircleShape),
    ) {
        if (current != null) {
            Image(current.second, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            if (current.first.isVideo) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = DiveColors.TextPrimary,
                    modifier = Modifier.size(18.dp).background(DiveColors.DeepBlack.copy(alpha = 0.6f), CircleShape))
            }
        } else {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = DiveColors.TextMuted,
                modifier = Modifier.size(22.dp))
        }
    }
}
