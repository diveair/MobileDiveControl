package com.mobiledivecontrol.ui.gallery

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.GalleryItem
import com.mobiledivecontrol.core.GalleryState
import com.mobiledivecontrol.core.GalleryTab
import com.mobiledivecontrol.core.GalleryViewMode
import com.mobiledivecontrol.platform.GalleryRepository
import com.mobiledivecontrol.theme.DiveColors

@Composable
fun GalleryScreen(
    galleryState: GalleryState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack),
    ) {
        when (galleryState.viewMode) {
            GalleryViewMode.Browser -> GalleryBrowser(galleryState)
            GalleryViewMode.Preview -> GalleryPreview(galleryState)
            GalleryViewMode.ConfirmDelete -> {
                GalleryBrowser(galleryState)
                ConfirmationOverlay(
                    title = "Delete Item?",
                    message = galleryState.items.getOrNull(galleryState.selectedIndex)?.name ?: "",
                    confirmLabel = "Delete",
                    cancelLabel = "Cancel",
                    selectedIndex = galleryState.confirmButtonIndex,
                )
            }
            GalleryViewMode.ConfirmFolderDelete -> {
                GalleryBrowser(galleryState)
                ConfirmationOverlay(
                    title = "Delete Folder?",
                    message = galleryState.items.getOrNull(galleryState.selectedIndex)?.name ?: "",
                    confirmLabel = "Delete",
                    cancelLabel = "Cancel",
                    selectedIndex = galleryState.confirmButtonIndex,
                )
            }
            GalleryViewMode.CreateFolder -> {
                GalleryBrowser(galleryState)
                ConfirmationOverlay(
                    title = "Create Folder",
                    message = galleryState.folderName,
                    confirmLabel = "Create",
                    cancelLabel = "Cancel",
                    selectedIndex = galleryState.confirmButtonIndex,
                )
            }
        }
    }
}

@Composable
private fun GalleryBrowser(galleryState: GalleryState) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Tab bar
        GalleryTabBar(
            currentTab = galleryState.tab,
            currentFolder = galleryState.currentFolder,
        )

        // Item list
        if (galleryState.items.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        tint = DiveColors.TextMuted,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No items found",
                        color = DiveColors.TextMuted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(galleryState.selectedIndex) {
                listState.animateScrollToItem(galleryState.selectedIndex.coerceAtLeast(0))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                itemsIndexed(galleryState.items) { index, item ->
                    GalleryItemRow(
                        item = item,
                        isSelected = index == galleryState.selectedIndex,
                    )
                    if (index < galleryState.items.lastIndex) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTabBar(
    currentTab: GalleryTab,
    currentFolder: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DiveColors.SurfaceCard.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth(),
        ) {
            GalleryTab.entries.forEach { tab ->
                val selected = tab == currentTab
                val icon = when (tab) {
                    GalleryTab.Photos -> Icons.Rounded.Image
                    GalleryTab.Videos -> Icons.Rounded.VideoLibrary
                    GalleryTab.Folders -> Icons.Rounded.Folder
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.2f) else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) DiveColors.DiveCyan else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tab.name,
                        color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        if (currentFolder != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "📂 ${currentFolder.substringAfterLast("/")}",
                color = DiveColors.DiveCyan,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun GalleryItemRow(
    item: GalleryItem,
    isSelected: Boolean,
) {
    val context = LocalContext.current
    var thumbnail by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        if (!item.isFolder) {
            thumbnail = try {
                val repo = GalleryRepository(context)
                val contentUri = repo.contentUriForItem(item)
                context.contentResolver.loadThumbnail(contentUri, Size(128, 128), null)
            } catch (_: Exception) {
                null
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isSelected) DiveColors.DiveCyan.copy(alpha = 0.15f) else DiveColors.SurfaceCard.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = if (isSelected) DiveColors.DiveCyan else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Thumbnail or folder icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DiveColors.DeepBlack.copy(alpha = 0.6f)),
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = when {
                        item.isFolder -> Icons.Rounded.Folder
                        item.isVideo -> Icons.Rounded.Videocam
                        else -> Icons.Rounded.Image
                    },
                    contentDescription = null,
                    tint = if (isSelected) DiveColors.DiveCyan else DiveColors.TextMuted,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = if (isSelected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.isFolder && item.sizeBytes > 0) {
                Text(
                    text = formatSize(item.sizeBytes),
                    color = DiveColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (item.width > 0 && item.height > 0 && !item.isFolder) {
            Text(
                text = "${item.width}×${item.height}",
                color = DiveColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun GalleryPreview(galleryState: GalleryState) {
    val context = LocalContext.current
    val item = galleryState.items.getOrNull(galleryState.selectedIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack),
    ) {
        // Preview header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DiveColors.SurfaceCard.copy(alpha = 0.7f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = item?.name ?: "Preview",
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Full image / placeholder
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(DiveColors.DeepBlack),
        ) {
            if (item != null && !item.isFolder) {
                var fullBitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(item.id) {
                    fullBitmap = try {
                        val repo = GalleryRepository(context)
                        val contentUri = repo.contentUriForItem(item)
                        context.contentResolver.loadThumbnail(contentUri, Size(1024, 1024), null)
                    } catch (_: Exception) {
                        null
                    }
                }

                val bmp = fullBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = if (item.isVideo) Icons.Rounded.Videocam else Icons.Rounded.Image,
                        contentDescription = null,
                        tint = DiveColors.TextMuted,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }

            // Navigation indicators
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val current = galleryState.selectedIndex + 1
                val total = galleryState.items.size
                Text(
                    text = "◀ $current / $total ▶",
                    color = DiveColors.DiveCyan,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(DiveColors.DeepBlack.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // EXIF data panel
        if (galleryState.previewExifLines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DiveColors.SurfaceCard.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = DiveColors.DiveCyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "File Details",
                        color = DiveColors.DiveCyan,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                galleryState.previewExifLines.forEach { line ->
                    Text(
                        text = line,
                        color = DiveColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }

        // Button hints
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .background(DiveColors.SurfaceCard.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            ButtonHint(label = "◀▶ Navigate")
            ButtonHint(label = "⏎ Back")
            ButtonHint(label = "🗑 Shutter=Del")
        }
    }
}

@Composable
private fun ConfirmationOverlay(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    selectedIndex: Int, // 0 = confirm, 1 = cancel
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack.copy(alpha = 0.85f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(DiveColors.SurfaceCard, RoundedCornerShape(20.dp))
                .border(1.5.dp, DiveColors.DiveCyan.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = DiveColors.Critical,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = DiveColors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Two selectable buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(0.8f),
            ) {
                // Confirm button (index 0)
                ConfirmDialogButton(
                    label = confirmLabel,
                    isSelected = selectedIndex == 0,
                    isDestructive = true,
                    modifier = Modifier.weight(1f),
                )
                // Cancel button (index 1)
                ConfirmDialogButton(
                    label = cancelLabel,
                    isSelected = selectedIndex == 1,
                    isDestructive = false,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "▲▼ ◀▶  Select  ·  OK  Execute",
                color = DiveColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ConfirmDialogButton(
    label: String,
    isSelected: Boolean,
    isDestructive: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor = when {
        isSelected && isDestructive -> DiveColors.Critical.copy(alpha = 0.25f)
        isSelected -> DiveColors.DiveCyan.copy(alpha = 0.2f)
        else -> DiveColors.SurfaceCard.copy(alpha = 0.4f)
    }
    val borderColor = when {
        isSelected && isDestructive -> DiveColors.Critical
        isSelected -> DiveColors.DiveCyan
        else -> DiveColors.SurfaceBorder.copy(alpha = 0.3f)
    }
    val textColor = when {
        isSelected && isDestructive -> DiveColors.Critical
        isSelected -> DiveColors.DiveCyan
        else -> DiveColors.TextMuted
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ButtonHint(label: String) {
    Text(
        text = label,
        color = DiveColors.TextMuted,
        style = MaterialTheme.typography.labelSmall,
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
