package com.mobiledivecontrol.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.GalleryAlbumAction
import com.mobiledivecontrol.core.GalleryCommand
import com.mobiledivecontrol.core.GalleryBrowserAction
import com.mobiledivecontrol.core.GalleryItem
import com.mobiledivecontrol.core.GalleryMediaAction
import com.mobiledivecontrol.core.GalleryPreviewAction
import com.mobiledivecontrol.core.GalleryState
import com.mobiledivecontrol.core.GalleryViewMode
import com.mobiledivecontrol.core.GALLERY_ALBUM_COLUMNS
import com.mobiledivecontrol.core.GALLERY_MEDIA_COLUMNS
import com.mobiledivecontrol.core.galleryPreviewRailActions
import com.mobiledivecontrol.platform.GalleryRepository
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.camera.LoopingVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GalleryScreen(
    galleryState: GalleryState,
    onCommand: (GalleryCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack),
    ) {
        when (galleryState.viewMode) {
            GalleryViewMode.Browser -> GalleryBrowser(galleryState, onCommand)
            GalleryViewMode.AlbumActions -> {
                GalleryBrowser(galleryState, onCommand)
                AlbumActionsOverlay(galleryState, onCommand)
            }
            GalleryViewMode.MediaActions -> {
                GalleryBrowser(galleryState, onCommand)
                MediaActionsOverlay(galleryState, onCommand)
            }
            GalleryViewMode.Preview -> GalleryPreview(galleryState, onCommand)
            GalleryViewMode.Options -> {
                GalleryPreview(galleryState, onCommand)
                OptionsOverlay(galleryState, onCommand)
            }
            GalleryViewMode.Move -> MoveDestinationGrid(galleryState, onCommand)
            GalleryViewMode.Rename -> {
                GalleryPreview(galleryState, onCommand)
                RenameOverlay(galleryState, onCommand)
            }
            GalleryViewMode.ConfirmDelete -> {
                if (galleryState.confirmationReturnToPreview) {
                    GalleryPreview(galleryState, onCommand)
                } else {
                    GalleryBrowser(galleryState, onCommand)
                }
                ConfirmationOverlay(
                    title = "Delete media?",
                    message = galleryState.operationMessage
                        ?: galleryState.items.getOrNull(galleryState.selectedIndex)?.name.orEmpty(),
                    confirmLabel = "Delete",
                    selectedIndex = galleryState.confirmButtonIndex,
                    busy = galleryState.pendingMutation != null,
                    onCommand = onCommand,
                )
            }
            GalleryViewMode.ConfirmFolderDelete -> {
                GalleryBrowser(galleryState, onCommand)
                val album = galleryState.items.getOrNull(galleryState.selectedIndex)
                ConfirmationOverlay(
                    title = "Delete album?",
                    message = "${album?.name.orEmpty()}  ·  ${album?.mediaCount ?: 0} items will be deleted",
                    confirmLabel = "Delete Album",
                    selectedIndex = galleryState.confirmButtonIndex,
                    busy = galleryState.pendingMutation != null,
                    onCommand = onCommand,
                )
            }
            GalleryViewMode.CreateFolder -> {
                GalleryBrowser(galleryState, onCommand)
                CreateAlbumOverlay(galleryState, onCommand)
            }
        }

        galleryState.operationMessage?.let { message ->
            if (galleryState.viewMode !in setOf(GalleryViewMode.ConfirmDelete, GalleryViewMode.Move, GalleryViewMode.Rename)) {
                OperationBanner(message = message, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun GalleryBrowser(galleryState: GalleryState, onCommand: (GalleryCommand) -> Unit) {
    val showingAlbums = galleryState.currentFolder == null
    val selectedAction = galleryState.browserAction
        ?: GalleryBrowserAction.Back.takeIf { galleryState.browserBackFocused }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GalleryHeader(
                title = if (showingAlbums) "Albums" else galleryState.currentFolderName ?: "Album",
                subtitle = if (showingAlbums) {
                    "${galleryState.items.size} albums"
                } else {
                    "${galleryState.items.size} photos and videos"
                },
                hint = if (showingAlbums) {
                    "Arrow past any edge → Back  ·  OK / Shutter Album options"
                } else {
                    "Arrow past any edge → Back  ·  OK / Shutter Open"
                },
            )

            if (galleryState.items.isEmpty()) {
                EmptyGallery(message = if (showingAlbums) "No albums available" else "This album is empty")
                return@Column
            }

            val columns = if (showingAlbums) GALLERY_ALBUM_COLUMNS else GALLERY_MEDIA_COLUMNS
            val gridState = rememberLazyGridState()
            val leadingItemKey = galleryState.items.firstOrNull()?.let { item ->
                if (item.isFolder) item.path else item.contentUri.ifBlank { "${item.id}:${item.isVideo}" }
            }
            LaunchedEffect(galleryState.currentFolder, galleryState.selectedIndex, leadingItemKey) {
                gridState.animateScrollToItem(galleryState.selectedIndex.coerceIn(galleryState.items.indices))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = 18.dp,
                        top = 12.dp,
                        end = 18.dp,
                        bottom = 78.dp,
                    ),
            ) {
                itemsIndexed(
                    galleryState.items,
                    key = { _, item -> if (item.isFolder) "album:${item.path}" else "media:${item.contentUri}:${item.isVideo}:${item.id}" },
                ) { index, item ->
                    if (showingAlbums) {
                        AlbumCard(
                            album = item,
                            selected = index == galleryState.selectedIndex && selectedAction == null,
                            onClick = { onCommand(GalleryCommand.OpenItem(index)) },
                        )
                    } else {
                        MediaGridCell(
                            item = item,
                            selected = index == galleryState.selectedIndex && selectedAction == null,
                            onClick = { onCommand(GalleryCommand.OpenItem(index)) },
                        )
                    }
                }
            }
        }
        GalleryBrowserActionRail(
            showingAlbums = showingAlbums,
            selectedAction = selectedAction,
            onCommand = onCommand,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun GalleryHeader(
    title: String,
    subtitle: String,
    hint: String,
    backLabel: String? = null,
    backSelected: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(DiveColors.SurfaceCard.copy(alpha = 0.72f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        if (backLabel != null && onBack != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (backSelected) DiveColors.DiveCyan.copy(alpha = 0.2f) else Color.Transparent)
                    .border(
                        width = if (backSelected) 1.5.dp else 0.dp,
                        color = if (backSelected) DiveColors.DiveCyan else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to $backLabel",
                    tint = if (backSelected) DiveColors.DiveCyan else DiveColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = backLabel,
                    color = if (backSelected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(text = subtitle, color = DiveColors.TextMuted, style = MaterialTheme.typography.labelMedium)
        }
        Text(text = hint, color = DiveColors.DiveCyan, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AlbumCard(album: GalleryItem, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) DiveColors.DiveCyan.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f)
                .clip(RoundedCornerShape(10.dp))
                .background(DiveColors.SurfaceCard),
        ) {
            MediaThumbnail(item = album, modifier = Modifier.fillMaxSize())
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .background(DiveColors.DeepBlack.copy(alpha = 0.72f), CircleShape)
                    .padding(5.dp)
                    .size(17.dp),
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = album.name,
            color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${album.mediaCount}",
            color = DiveColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun MediaGridCell(item: GalleryItem, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(9.dp))
            .background(DiveColors.SurfaceCard)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) DiveColors.DiveCyan else Color.Transparent,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick),
    ) {
        MediaThumbnail(item = item, modifier = Modifier.fillMaxSize())
        if (item.isVideo) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(DiveColors.DeepBlack.copy(alpha = 0.62f), CircleShape)
                    .padding(5.dp)
                    .size(24.dp),
            )
        }
    }
}

@Composable
private fun MediaThumbnail(item: GalleryItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(item.contentUri, item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.contentUri, item.id) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val uri = if (item.contentUri.isNotBlank()) {
                    Uri.parse(item.contentUri)
                } else {
                    GalleryRepository(context).contentUriForItem(item)
                }
                context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            }.getOrNull()
        }
    }
    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(contentAlignment = Alignment.Center, modifier = modifier) {
            Icon(
                imageVector = if (item.isFolder) Icons.Rounded.Folder else if (item.isVideo) Icons.Rounded.Videocam else Icons.Rounded.Image,
                contentDescription = null,
                tint = DiveColors.TextMuted,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun GalleryPreview(galleryState: GalleryState, onCommand: (GalleryCommand) -> Unit) {
    val context = LocalContext.current
    val item = galleryState.items.getOrNull(galleryState.selectedIndex)
    var videoPositionMs by remember(item?.contentUri, item?.id) { mutableStateOf(0L) }
    var videoDurationMs by remember(item?.contentUri, item?.id) { mutableStateOf(0L) }
    Box(modifier = Modifier.fillMaxSize().background(DiveColors.DeepBlack)) {
        if (item != null && !item.isFolder) {
            val contentUri = remember(item.contentUri, item.id) {
                GalleryRepository(context).contentUriForItem(item)
            }
            if (item.isVideo) {
                LoopingVideo(
                    uri = contentUri,
                    playing = galleryState.videoPlaying,
                    onProgress = { positionMs, durationMs ->
                        videoPositionMs = positionMs
                        videoDurationMs = durationMs
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FullResolutionPhoto(item = item, uri = contentUri)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(DiveColors.DeepBlack.copy(alpha = 0.68f))
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                text = item?.name ?: "Preview",
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(galleryState.selectedIndex + 1).coerceAtMost(galleryState.items.size)} / ${galleryState.items.size}",
                color = DiveColors.DiveCyan,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        DetailsPanel(galleryState = galleryState, modifier = Modifier.align(Alignment.CenterEnd))
        if (item?.isVideo == true) {
            VideoTimeline(
                positionMs = videoPositionMs,
                durationMs = videoDurationMs,
                playing = galleryState.videoPlaying,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = 156.dp),
            )
        }
        PreviewActionRail(
            galleryState = galleryState,
            item = item,
            onCommand = onCommand,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun VideoTimeline(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = modifier
            .fillMaxWidth(0.74f)
            .clip(RoundedCornerShape(12.dp))
            .background(DiveColors.DeepBlack.copy(alpha = 0.84f))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DiveColors.TextMuted.copy(alpha = 0.34f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(DiveColors.DiveCyan),
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatPlaybackTime(positionMs),
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = if (playing) "PLAYING" else "PAUSED",
                color = DiveColors.DiveCyan,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (durationMs > 0L) formatPlaybackTime(durationMs) else "--:--",
                color = DiveColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun FullResolutionPhoto(item: GalleryItem, uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(item.id, item.contentUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, item.contentUri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(2048, 2048), null) }.getOrNull()
        }
    }
    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = DiveColors.TextMuted, modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
private fun PreviewActionRail(
    galleryState: GalleryState,
    item: GalleryItem?,
    onCommand: (GalleryCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DiveColors.DeepBlack.copy(alpha = 0.84f))
            .border(1.dp, DiveColors.SurfaceBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        galleryPreviewRailActions(item?.isVideo == true).forEach { action ->
            val icon = when (action) {
                GalleryPreviewAction.Back -> Icons.AutoMirrored.Rounded.ArrowBack
                GalleryPreviewAction.Delete -> Icons.Rounded.Delete
                GalleryPreviewAction.Options -> Icons.Rounded.MoreVert
                GalleryPreviewAction.Previous -> Icons.Rounded.SkipPrevious
                GalleryPreviewAction.PlayPause -> if (galleryState.videoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                GalleryPreviewAction.Next -> Icons.Rounded.SkipNext
                GalleryPreviewAction.Details -> Icons.Rounded.Info
            }
            val label = when (action) {
                GalleryPreviewAction.Back -> "Back"
                GalleryPreviewAction.Delete -> "Delete"
                GalleryPreviewAction.Options -> "Options"
                GalleryPreviewAction.Previous -> "Previous"
                GalleryPreviewAction.PlayPause -> if (galleryState.videoPlaying) "Pause" else "Play"
                GalleryPreviewAction.Next -> "Next"
                GalleryPreviewAction.Details -> "Details"
            }
            val enabled = when (action) {
                GalleryPreviewAction.Back -> true
                GalleryPreviewAction.Previous, GalleryPreviewAction.Next -> galleryState.items.size > 1
                GalleryPreviewAction.PlayPause -> item?.isVideo == true
                else -> item != null
            }
            PreviewActionButton(
                label = label,
                icon = icon,
                selected = action == galleryState.previewAction,
                enabled = enabled,
                onClick = { onCommand(GalleryCommand.ActivatePreviewAction(action)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GalleryBrowserActionRail(
    showingAlbums: Boolean,
    selectedAction: GalleryBrowserAction?,
    onCommand: (GalleryCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        Spacer(modifier = Modifier.width(184.dp))
        GalleryBackActionButton(
            selected = selectedAction == GalleryBrowserAction.Back,
            onClick = {
                onCommand(GalleryCommand.ActivateBrowserAction(GalleryBrowserAction.Back))
            },
        )
        if (showingAlbums) {
            GalleryBrowserActionButton(
                label = "Create Album",
                icon = Icons.Rounded.CreateNewFolder,
                selected = selectedAction == GalleryBrowserAction.CreateAlbum,
                onClick = {
                    onCommand(GalleryCommand.ActivateBrowserAction(GalleryBrowserAction.CreateAlbum))
                },
            )
        } else {
            Spacer(modifier = Modifier.width(184.dp))
        }
    }
}

@Composable
private fun GalleryBackActionButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.width(220.dp),
    ) {
        DirectionChevron(
            label = "TOP",
            pointsUp = true,
            color = accent,
            labelColor = if (selected) DiveColors.DeepBlack else DiveColors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) DiveColors.DiveCyan.copy(alpha = 0.24f)
                    else DiveColors.DeepBlack.copy(alpha = 0.9f),
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder,
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onClick),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = null,
                tint = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "BACK",
                color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        DirectionChevron(
            label = "BOTTOM",
            pointsUp = false,
            color = accent,
            labelColor = if (selected) DiveColors.DeepBlack else DiveColors.TextPrimary,
        )
    }
}

@Composable
private fun DirectionChevron(
    label: String,
    pointsUp: Boolean,
    color: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.width(62.dp).height(11.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (pointsUp) {
                    moveTo(0f, h)
                    lineTo(w / 2f, 0f)
                    lineTo(w, h)
                    lineTo(w * 0.78f, h)
                    lineTo(w / 2f, h * 0.46f)
                    lineTo(w * 0.22f, h)
                } else {
                    moveTo(0f, 0f)
                    lineTo(w / 2f, h)
                    lineTo(w, 0f)
                    lineTo(w * 0.78f, 0f)
                    lineTo(w / 2f, h * 0.54f)
                    lineTo(w * 0.22f, 0f)
                }
                close()
            }
            drawPath(path = path, color = color.copy(alpha = 0.9f))
        }
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            fontSize = 7.sp,
            lineHeight = 7.sp,
        )
    }
}

@Composable
private fun GalleryBrowserActionButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    width: androidx.compose.ui.unit.Dp = 184.dp,
) {
    val accent = if (destructive) DiveColors.Critical else DiveColors.DiveCyan
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .width(width)
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) accent.copy(alpha = 0.24f)
                else DiveColors.DeepBlack.copy(alpha = 0.9f),
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else DiveColors.SurfaceBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) accent else DiveColors.TextSecondary,
            modifier = Modifier.size(23.dp),
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = label,
            color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PreviewActionButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) DiveColors.DiveCyan.copy(alpha = 0.2f) else Color.Transparent)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) DiveColors.DiveCyan else Color.Transparent,
                shape = RoundedCornerShape(11.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (!enabled) DiveColors.TextMuted.copy(alpha = 0.35f) else if (selected) DiveColors.DiveCyan else DiveColors.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = if (!enabled) DiveColors.TextMuted.copy(alpha = 0.35f) else if (selected) DiveColors.TextPrimary else DiveColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun AlbumActionsOverlay(
    galleryState: GalleryState,
    onCommand: (GalleryCommand) -> Unit,
) {
    val album = galleryState.items.getOrNull(galleryState.selectedIndex)
    GalleryItemActionsOverlay(
        title = album?.name ?: "Album",
        subtitle = "${album?.mediaCount ?: 0} photos and videos",
        headerIcon = Icons.Rounded.Folder,
        backSelected = galleryState.albumAction == GalleryAlbumAction.Back,
        previewSelected = galleryState.albumAction == GalleryAlbumAction.Preview,
        deleteSelected = galleryState.albumAction == GalleryAlbumAction.Delete,
        deleteLabel = "Delete Album",
        onBack = { onCommand(GalleryCommand.ActivateAlbumAction(GalleryAlbumAction.Back)) },
        onPreview = { onCommand(GalleryCommand.ActivateAlbumAction(GalleryAlbumAction.Preview)) },
        onDelete = { onCommand(GalleryCommand.ActivateAlbumAction(GalleryAlbumAction.Delete)) },
    )
}

@Composable
private fun MediaActionsOverlay(
    galleryState: GalleryState,
    onCommand: (GalleryCommand) -> Unit,
) {
    val item = galleryState.items.getOrNull(galleryState.selectedIndex)
    GalleryItemActionsOverlay(
        title = item?.name ?: "Media",
        subtitle = if (item?.isVideo == true) "Video" else "Photo",
        headerIcon = if (item?.isVideo == true) Icons.Rounded.Videocam else Icons.Rounded.Image,
        backSelected = galleryState.mediaAction == GalleryMediaAction.Back,
        previewSelected = galleryState.mediaAction == GalleryMediaAction.Preview,
        deleteSelected = galleryState.mediaAction == GalleryMediaAction.Delete,
        deleteLabel = "Delete",
        onBack = { onCommand(GalleryCommand.ActivateMediaAction(GalleryMediaAction.Back)) },
        onPreview = { onCommand(GalleryCommand.ActivateMediaAction(GalleryMediaAction.Preview)) },
        onDelete = { onCommand(GalleryCommand.ActivateMediaAction(GalleryMediaAction.Delete)) },
    )
}

@Composable
private fun GalleryItemActionsOverlay(
    title: String,
    subtitle: String,
    headerIcon: ImageVector,
    backSelected: Boolean,
    previewSelected: Boolean,
    deleteSelected: Boolean,
    deleteLabel: String,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(DiveColors.DeepBlack.copy(alpha = 0.78f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(650.dp)
                .background(DiveColors.SurfaceCard, RoundedCornerShape(20.dp))
                .border(1.5.dp, DiveColors.DiveCyan.copy(alpha = 0.58f), RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            Icon(headerIcon, contentDescription = null, tint = DiveColors.DiveCyan, modifier = Modifier.size(34.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = DiveColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogAction(
                    label = "Back",
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    selected = backSelected,
                    onClick = onBack,
                )
                DialogAction(
                    label = "Preview",
                    icon = Icons.Rounded.PlayArrow,
                    selected = previewSelected,
                    onClick = onPreview,
                )
                DialogAction(
                    label = deleteLabel,
                    icon = Icons.Rounded.Delete,
                    selected = deleteSelected,
                    destructive = true,
                    onClick = onDelete,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "←→ or ↑↓ Select  ·  OK / Shutter Execute",
                color = DiveColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DetailsPanel(galleryState: GalleryState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = galleryState.detailsVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.padding(end = 16.dp, bottom = 196.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.42f)
                .background(DiveColors.DeepBlack.copy(alpha = 0.9f), RoundedCornerShape(14.dp))
                .border(1.dp, DiveColors.DiveCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = DiveColors.DiveCyan, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(7.dp))
                Text("Details", color = DiveColors.DiveCyan, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(7.dp))
            if (galleryState.previewExifLines.isEmpty()) {
                Text("Loading…", color = DiveColors.TextSecondary)
            } else {
                galleryState.previewExifLines.forEach { line ->
                    Text(line, color = DiveColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun OptionsOverlay(galleryState: GalleryState, onCommand: (GalleryCommand) -> Unit) {
    val choices = listOf(
        Triple("Move", "Choose another album", Icons.AutoMirrored.Rounded.DriveFileMove),
        Triple("Rename", "Change the file name", Icons.Rounded.Edit),
        Triple("Cancel", "Return to preview", Icons.Rounded.Close),
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(DiveColors.DeepBlack.copy(alpha = 0.74f)),
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(DiveColors.SurfaceCard, RoundedCornerShape(18.dp))
                .border(1.5.dp, DiveColors.DiveCyan.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Text("Options", color = DiveColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            choices.forEachIndexed { index, (title, subtitle, icon) ->
                OptionRow(
                    title = title,
                    subtitle = subtitle,
                    icon = icon,
                    selected = index == galleryState.optionIndex,
                    onClick = { onCommand(GalleryCommand.SelectOption(index)) },
                )
                if (index < choices.lastIndex) Spacer(modifier = Modifier.height(7.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text("↑↓ or ←→ Select  ·  OK / Shutter Execute", color = DiveColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DiveColors.DiveCyan.copy(alpha = 0.18f) else DiveColors.DeepBlack.copy(alpha = 0.35f))
            .border(if (selected) 1.5.dp else 0.dp, if (selected) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = DiveColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text(subtitle, color = DiveColors.TextMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MoveDestinationGrid(galleryState: GalleryState, onCommand: (GalleryCommand) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        GalleryHeader(
            title = "Move to album",
            subtitle = galleryState.items.getOrNull(galleryState.selectedIndex)?.name.orEmpty(),
            hint = "↑↓←→ Navigate  ·  OK / Shutter Move  ·  Zoom− Cancel",
            backLabel = "Preview",
            onBack = { onCommand(GalleryCommand.Back) },
        )
        if (galleryState.moveTargets.isEmpty()) {
            EmptyGallery(galleryState.operationMessage ?: "No other albums available")
        } else {
            val gridState = rememberLazyGridState()
            LaunchedEffect(galleryState.moveTargetIndex) {
                gridState.animateScrollToItem(galleryState.moveTargetIndex.coerceIn(galleryState.moveTargets.indices))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(GALLERY_ALBUM_COLUMNS),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(18.dp),
            ) {
                itemsIndexed(galleryState.moveTargets, key = { _, item -> item.path }) { index, album ->
                    AlbumCard(
                        album = album,
                        selected = index == galleryState.moveTargetIndex,
                        onClick = { onCommand(GalleryCommand.SelectMoveTarget(index)) },
                    )
                }
            }
        }
        galleryState.operationMessage?.let { OperationBanner(it) }
    }
}

@Composable
private fun CreateAlbumOverlay(galleryState: GalleryState, onCommand: (GalleryCommand) -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(DiveColors.DeepBlack.copy(alpha = 0.8f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(500.dp)
                .background(DiveColors.SurfaceCard, RoundedCornerShape(18.dp))
                .border(1.5.dp, DiveColors.DiveCyan.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(22.dp),
        ) {
            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, tint = DiveColors.DiveCyan, modifier = Modifier.size(38.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text("Create Album", color = DiveColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = galleryState.folderName,
                onValueChange = { onCommand(GalleryCommand.SetFolderName(it)) },
                singleLine = true,
                label = { Text("Album name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DialogAction(
                    label = "Create",
                    icon = Icons.Rounded.CreateNewFolder,
                    selected = galleryState.confirmButtonIndex == 0,
                    onClick = { onCommand(GalleryCommand.SelectConfirmation(0)) },
                )
                DialogAction(
                    label = "Cancel",
                    icon = Icons.Rounded.Close,
                    selected = galleryState.confirmButtonIndex == 1,
                    onClick = { onCommand(GalleryCommand.SelectConfirmation(1)) },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text("←→ or ↑↓ Select  ·  OK / Shutter Execute", color = DiveColors.TextMuted, style = MaterialTheme.typography.labelSmall)
            galleryState.operationMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = DiveColors.Critical)
            }
        }
    }
}

@Composable
private fun RenameOverlay(galleryState: GalleryState, onCommand: (GalleryCommand) -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(DiveColors.DeepBlack.copy(alpha = 0.76f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(500.dp)
                .background(DiveColors.SurfaceCard, RoundedCornerShape(18.dp))
                .border(1.5.dp, DiveColors.DiveCyan.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(22.dp),
        ) {
            Text("Rename", color = DiveColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = galleryState.renameDraft,
                onValueChange = { onCommand(GalleryCommand.SetRenameDraft(it)) },
                enabled = galleryState.pendingMutation == null,
                singleLine = true,
                label = { Text("File name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogAction(
                    label = "Save",
                    icon = Icons.Rounded.Check,
                    selected = true,
                    onClick = { onCommand(GalleryCommand.Confirm) },
                )
                DialogAction(
                    label = "Cancel",
                    icon = Icons.Rounded.Close,
                    selected = false,
                    onClick = { onCommand(GalleryCommand.Back) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Touch to type  ·  OK / Shutter Save  ·  Zoom− Cancel", color = DiveColors.TextMuted, style = MaterialTheme.typography.labelSmall)
            galleryState.operationMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = if (galleryState.pendingMutation == null) DiveColors.Critical else DiveColors.DiveCyan)
            }
        }
    }
}

@Composable
private fun ConfirmationOverlay(
    title: String,
    message: String,
    confirmLabel: String,
    selectedIndex: Int,
    busy: Boolean,
    onCommand: (GalleryCommand) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(DiveColors.DeepBlack.copy(alpha = 0.84f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(480.dp)
                .background(DiveColors.SurfaceCard, RoundedCornerShape(20.dp))
                .border(1.5.dp, DiveColors.Critical.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = null, tint = DiveColors.Critical, modifier = Modifier.size(38.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(title, color = DiveColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(message, color = DiveColors.TextSecondary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DialogAction(
                    label = if (busy) "Working…" else confirmLabel,
                    icon = Icons.Rounded.Delete,
                    selected = selectedIndex == 0,
                    destructive = true,
                    enabled = !busy,
                    onClick = { onCommand(GalleryCommand.SelectConfirmation(0)) },
                )
                DialogAction(
                    label = "Cancel",
                    icon = Icons.Rounded.Close,
                    selected = selectedIndex == 1,
                    enabled = !busy,
                    onClick = { onCommand(GalleryCommand.SelectConfirmation(1)) },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text("←→ or ↑↓ Select  ·  OK / Shutter Execute", color = DiveColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = if (destructive) DiveColors.Critical else DiveColors.DiveCyan
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.2f) else DiveColors.DeepBlack.copy(alpha = 0.4f))
            .border(if (selected) 1.5.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) accent else DiveColors.TextMuted, modifier = Modifier.size(19.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = if (enabled) DiveColors.TextPrimary else DiveColors.TextMuted, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyGallery(message: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = DiveColors.TextMuted, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(14.dp))
            Text(message, color = DiveColors.TextMuted, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun OperationBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = DiveColors.TextPrimary,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .padding(top = 12.dp)
            .background(DiveColors.DeepBlack.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .border(1.dp, DiveColors.DiveCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
