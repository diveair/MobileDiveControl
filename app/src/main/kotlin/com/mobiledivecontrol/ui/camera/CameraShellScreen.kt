package com.mobiledivecontrol.ui.camera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Filter
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.HdrAuto
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.CameraCaptureType
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraFeatureStatus
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.CameraModeProfile
import com.mobiledivecontrol.core.CameraSettingKind
import com.mobiledivecontrol.core.CameraSettingSpec
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.core.CameraUiZone
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.core.SliderEditTarget
import com.mobiledivecontrol.core.SliderSensitivity
import com.mobiledivecontrol.core.selectedSetting
import com.mobiledivecontrol.core.BottomBarItem
import com.mobiledivecontrol.theme.DiveColors
import kotlinx.coroutines.delay
import androidx.compose.material3.Icon

@Composable
fun CameraShellScreen(
    cameraState: CameraState,
    safetyState: SafetyState,
    cameraPermissionGranted: Boolean = false,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
    effects: List<PlatformEffect> = emptyList(),
    onEffectsConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val profile = CameraCatalog.profile(cameraState.activeMode, cameraState.deviceVariant)
    val settings = CameraCatalog.settingsFor(cameraState.activeMode, cameraState.deviceVariant)
    val settingsVisible = settings.isNotEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        // Full-screen camera preview
        if (cameraPermissionGranted && lifecycleOwner != null) {
            StateDrivenCameraPreview(
                lifecycleOwner = lifecycleOwner,
                cameraState = cameraState,
                safetyState = safetyState,
                effects = effects,
                onEffectsConsumed = onEffectsConsumed,
            )
        } else {
            CameraPreviewPlaceholder()
        }

        // Top-left: recording badge
        RecordingBadge(
            visible = cameraState.recording,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 72.dp),
        )

        // Center: zoom overlay (fades after 1.4s)
        ZoomOverlay(
            zoomFactor = cameraState.zoomFactor,
            modifier = Modifier.align(Alignment.Center),
        )

        // Top-right: active camera mode indicator (when ModeRail is closed)
        AnimatedVisibility(
            visible = cameraState.focusedZone != CameraUiZone.ModeRail,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 72.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = DiveColors.DeepBlack.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = DiveColors.DiveCyan.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "MODE: ${profile.modeName.uppercase()}",
                    color = DiveColors.DiveCyan,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Right side: mode rail (only visible when in ModeRail zone)
        AnimatedVisibility(
            visible = cameraState.focusedZone == CameraUiZone.ModeRail,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 18.dp, top = 56.dp, bottom = 56.dp),
        ) {
            RightModeRail(
                cameraState = cameraState,
            )
        }

        // Bottom center: persistent mode-specific control bar
        AnimatedVisibility(
            visible = settingsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomSettingsTray(
                cameraState = cameraState,
            )
        }
    }
}

/**
 * Compact strip of mode parameter badges along the bottom edge.
 * Shows key settings for the current mode at a glance.
 */
@Composable
private fun ModeParametersStrip(
    settings: List<CameraSettingSpec>,
    cameraState: CameraState,
) {
    if (settings.isEmpty()) return

    // Show up to 6 key settings as compact badges
    val displaySettings = settings.take(6)

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                DiveColors.DeepBlack.copy(alpha = 0.55f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        displaySettings.forEach { spec ->
            val value = displaySettingValue(spec, CameraCatalog.currentValue(cameraState, spec))
            ParameterBadge(label = spec.label, value = value)
        }
    }
}

@Composable
private fun ParameterBadge(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                DiveColors.SurfaceCard.copy(alpha = 0.7f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = DiveColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = value,
            color = DiveColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Right-side mode rail using LazyColumn.
 * Always keeps the selected/highlighted item visible (scrolls to it).
 * Fills available vertical space.
 */
@Composable
private fun RightModeRail(
    cameraState: CameraState,
    modifier: Modifier = Modifier,
) {
    val items = CameraCatalog.primaryRailEntries
    val highlightedIndex = cameraState.highlightedPrimaryIndex
    val listState = rememberLazyListState()

    // Auto-scroll to keep highlighted item visible
    LaunchedEffect(highlightedIndex) {
        listState.animateScrollToItem(
            index = highlightedIndex.coerceIn(0, items.lastIndex),
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(162.dp)
            .fillMaxHeight()
            .background(DiveColors.DeepBlack.copy(alpha = 0.42f), RoundedCornerShape(24.dp))
            .border(1.dp, DiveColors.SurfaceBorder.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Modes",
            color = DiveColors.TextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(items) { index, entry ->
                val selected = index == highlightedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.16f)
                            else DiveColors.SurfaceCard.copy(alpha = 0.66f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .border(
                            width = if (selected) 1.dp else 0.dp,
                            color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = if (selected) 10.dp else 8.dp),
                ) {
                    Text(
                        text = entry.label,
                        color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                        style = if (selected) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomSettingsTrayLegacy(
    cameraState: CameraState,
    settings: List<CameraSettingSpec>,
    profile: CameraModeProfile,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .background(DiveColors.DeepBlack.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
            .border(1.5.dp, DiveColors.SurfaceBorder.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (cameraState.settingsEditing) {
            val spec = cameraState.selectedSetting
            if (spec != null) {
                val rawValue = CameraCatalog.currentValue(cameraState, spec)
                val value = displaySettingValue(spec, rawValue)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "${spec.label.uppercase()} ADJUSTMENT",
                        color = DiveColors.DiveCyan,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // 1. Value Slider Row
                    val isValueFocused = cameraState.sliderEditTarget == SliderEditTarget.Value
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (isValueFocused) DiveColors.DiveCyan.copy(alpha = 0.12f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = if (isValueFocused) 1.dp else 0.dp,
                                color = if (isValueFocused) DiveColors.DiveCyan else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Value",
                                color = if (isValueFocused) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = value,
                                color = if (isValueFocused) DiveColors.DiveCyan else DiveColors.TextMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        SliderMeterAdjuster(spec = spec, value = rawValue)
                    }

                    // 2. Sensitivity Slider Row
                    if (spec.supportsSensitivity) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val isSensitivityFocused = cameraState.sliderEditTarget == SliderEditTarget.Sensitivity
                        val sensitivity = cameraState.sliderSensitivities[spec.id] ?: SliderSensitivity.DEFAULT
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isSensitivityFocused) DiveColors.DiveCyan.copy(alpha = 0.12f) else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = if (isSensitivityFocused) 1.dp else 0.dp,
                                    color = if (isSensitivityFocused) DiveColors.DiveCyan else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Sensitivity",
                                    color = if (isSensitivityFocused) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Level ${sensitivity.level} Hold",
                                    color = if (isSensitivityFocused) DiveColors.DiveCyan else DiveColors.TextMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            SliderSensitivityMeter(current = sensitivity)
                        }
                    }
                }
            }
        } else {
            val items = CameraCatalog.settingsBarItems(
                cameraState.activeMode,
                cameraState.deviceVariant,
                cameraState.showMoreSettings
            )
            val listState = rememberLazyListState()

            LaunchedEffect(cameraState.settingsCursor) {
                if (items.isNotEmpty()) {
                    listState.animateScrollToItem(cameraState.settingsCursor.coerceIn(0, items.lastIndex))
                }
            }

            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(items) { index, item ->
                    val selected = cameraState.settingsCursor == index
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.24f)
                                        else DiveColors.SurfaceCard.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        when (item) {
                            is BottomBarItem.ModesButton -> {
                                Text(
                                    text = "🔄 Modes",
                                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            is BottomBarItem.LensShortcut -> {
                                Text(
                                    text = if (item.value == "front") "Front" else item.value.removeSuffix("x"),
                                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            is BottomBarItem.GalleryShortcut -> {
                                Text(
                                    text = "Gallery",
                                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            is BottomBarItem.MoreSettings -> {
                                Text(
                                    text = if (cameraState.showMoreSettings) "⚙️ Less Settings" else "⚙️ More Settings",
                                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            is BottomBarItem.Setting -> {
                                val value = displaySettingValue(item.spec, CameraCatalog.currentValue(cameraState, item.spec))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.spec.label,
                                        color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = value,
                                        color = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomSettingsTray(
    cameraState: CameraState,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(
                DiveColors.DeepBlack.copy(alpha = 0.84f),
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            )
            .border(
                1.5.dp,
                DiveColors.SurfaceBorder.copy(alpha = 0.78f),
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (cameraState.settingsEditing) {
            val spec = cameraState.selectedSetting
            if (spec != null) {
                val rawValue = CameraCatalog.currentValue(cameraState, spec)
                val value = displaySettingValue(spec, rawValue)
                val sensitivity = cameraState.sliderSensitivities[spec.id] ?: SliderSensitivity.DEFAULT

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = spec.label.uppercase(),
                        color = DiveColors.DiveCyan,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    ) {
                        BottomEditCard(
                            title = "Value",
                            value = value,
                            selected = cameraState.sliderEditTarget == SliderEditTarget.Value,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SliderMeterAdjuster(spec = spec, value = rawValue)
                        }

                        if (spec.supportsSensitivity) {
                            BottomEditCard(
                                title = "Sensitivity",
                                value = "Level ${sensitivity.level}",
                                selected = cameraState.sliderEditTarget == SliderEditTarget.Sensitivity,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                SliderSensitivityMeter(current = sensitivity)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "▲▼ Select  ·  ◀▶ Adjust",
                        color = DiveColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            val items = CameraCatalog.settingsBarItems(
                cameraState.activeMode,
                cameraState.deviceVariant,
                cameraState.showMoreSettings,
            )
            CenteredModesBar(
                items = items,
                cameraState = cameraState,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CenteredModesBar(
    items: List<BottomBarItem>,
    cameraState: CameraState,
    modifier: Modifier = Modifier,
) {
    val modesIndex = items.indexOfFirst { it is BottomBarItem.ModesButton }.coerceAtLeast(0)
    val leftItems = items.take(modesIndex)
    val centerItem = items.getOrNull(modesIndex) ?: BottomBarItem.ModesButton
    val rightItems = items.drop(modesIndex + 1)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        ModesBarSide(
            items = leftItems,
            cameraState = cameraState,
            startIndex = 0,
            alignToEnd = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        BottomBarChip(
            item = centerItem,
            cameraState = cameraState,
            selected = cameraState.settingsCursor == modesIndex,
            compact = false,
        )
        Spacer(modifier = Modifier.width(4.dp))
        ModesBarSide(
            items = rightItems,
            cameraState = cameraState,
            startIndex = modesIndex + 1,
            alignToEnd = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModesBarSide(
    items: List<BottomBarItem>,
    cameraState: CameraState,
    startIndex: Int,
    alignToEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = if (alignToEnd) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                BottomBarChip(
                    item = item,
                    cameraState = cameraState,
                    selected = cameraState.settingsCursor == startIndex + index,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun BottomEditCard(
    title: String,
    value: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .background(
                color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.14f) else DiveColors.SurfaceCard.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = value,
                color = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun BottomBarChip(
    item: BottomBarItem,
    cameraState: CameraState,
    selected: Boolean,
    compact: Boolean = false,
) {
    val icon = bottomBarIcon(item)
    val label = bottomBarLabel(item)
    val value = bottomBarValue(item, cameraState)
    val horizontalPadding = if (compact) 7.dp else 10.dp
    val verticalPadding = if (compact) 3.dp else 5.dp
    val iconSize = when {
        item is BottomBarItem.GalleryShortcut && compact -> 28.dp
        item is BottomBarItem.GalleryShortcut -> 32.dp
        compact -> 12.dp
        else -> 15.dp
    }
    val textStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val valueStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium

    val shouldStackValue = !value.isNullOrBlank() && " + " in value

    if (shouldStackValue) {
        // Vertical layout for multi-part values like "RAW + JPEG"
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.2f) else DiveColors.SurfaceCard.copy(alpha = 0.54f),
                    shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.46f),
                    shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                    modifier = Modifier.size(iconSize),
                )
                if (!label.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(if (compact) 4.dp else 6.dp))
                    Text(
                        text = label,
                        color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                        style = textStyle,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Stack each part of the value vertically
            value!!.split(" + ").forEach { part ->
                Text(
                    text = part.trim(),
                    color = if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    } else {
        // Standard horizontal layout
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.2f) else DiveColors.SurfaceCard.copy(alpha = 0.54f),
                    shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.46f),
                    shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            if (item is BottomBarItem.GalleryShortcut) {
                GalleryChipPreview(
                    selected = selected,
                    size = iconSize,
                    captureCounter = cameraState.captureCounter,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                    modifier = Modifier.size(iconSize),
                )
            }
            if (!label.isNullOrBlank() || !value.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
            }
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                    style = textStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!value.isNullOrBlank()) {
                if (!label.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(if (compact) 4.dp else 6.dp))
                }
                Text(
                    text = value,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary,
                    style = valueStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun bottomBarIcon(item: BottomBarItem): ImageVector = when (item) {
    is BottomBarItem.ModesButton -> Icons.Rounded.Autorenew
    is BottomBarItem.GalleryShortcut -> Icons.Rounded.PhotoLibrary
    is BottomBarItem.LensShortcut -> Icons.Rounded.CameraAlt
    is BottomBarItem.MoreSettings -> Icons.Rounded.Tune
    is BottomBarItem.Setting -> when {
        item.spec.id.endsWith(".flash") -> Icons.Rounded.FlashOn
        item.spec.id.endsWith(".megapixels") -> Icons.Rounded.PhotoSizeSelectLarge
        item.spec.id.endsWith(".save_format") -> Icons.Rounded.Image
        item.spec.id.endsWith(".lens") -> Icons.Rounded.CameraAlt
        item.spec.id.endsWith(".exposure_compensation") || item.spec.id.endsWith(".exposure_value") -> Icons.Rounded.Exposure
        item.spec.id.endsWith(".hdr") || item.spec.id.endsWith(".hdr_log") || item.spec.id.endsWith(".log") -> Icons.Rounded.HdrAuto
        item.spec.id.endsWith(".filters") -> Icons.Rounded.Filter
        else -> Icons.Rounded.Tune
    }
}

private fun bottomBarLabel(item: BottomBarItem): String? = when (item) {
    is BottomBarItem.ModesButton -> null
    is BottomBarItem.GalleryShortcut -> null
    is BottomBarItem.LensShortcut -> null
    is BottomBarItem.MoreSettings -> null
    is BottomBarItem.Setting -> null
}

private fun bottomBarValue(item: BottomBarItem, cameraState: CameraState): String? = when (item) {
    is BottomBarItem.ModesButton -> cameraState.activeMode.label
    is BottomBarItem.GalleryShortcut -> null
    is BottomBarItem.LensShortcut -> formatLensValue(item.value)
    is BottomBarItem.MoreSettings -> if (cameraState.showMoreSettings) "Less" else null
    is BottomBarItem.Setting -> displaySettingValue(item.spec, CameraCatalog.currentValue(cameraState, item.spec))
}

private fun displaySettingValue(spec: CameraSettingSpec, value: String): String {
    return when {
        spec.id.endsWith(".lens") -> formatLensValue(value)
        else -> value
    }
}

private fun formatLensValue(value: String): String = when (value) {
    "0.6x" -> "0.6"
    "1x" -> "1"
    "2x" -> "2"
    "3x" -> "3"
    "5x" -> "5"
    "front" -> "Front"
    else -> value
}

@Composable
private fun GalleryChipPreview(
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    captureCounter: Int = 0,
) {
    val thumbnail = rememberLatestGalleryThumbnail(captureCounter)

    if (thumbnail != null) {
        Image(
            bitmap = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.6f),
                    shape = CircleShape,
                ),
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun rememberLatestGalleryThumbnail(refreshKey: Int = 0): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    var thumbnail by remember(context) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(context, refreshKey) {
        // Small delay after capture to let MediaStore index the new file
        if (refreshKey > 0) kotlinx.coroutines.delay(500)
        thumbnail = loadLatestGalleryThumbnail(context)?.asImageBitmap()
    }

    return thumbnail
}

private fun loadLatestGalleryThumbnail(context: Context): Bitmap? {
    return try {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = buildString {
            append("(")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=? OR ")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=?)")
        }
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        context.contentResolver.query(filesUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
            val contentUri = ContentUris.withAppendedId(filesUri, id)
            context.contentResolver.loadThumbnail(contentUri, Size(96, 96), null)
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun VerticalSliderMeter(
    spec: CameraSettingSpec,
    value: String,
    modifier: Modifier = Modifier,
) {
    val options = spec.options
    val currentIndex = options.indexOf(value).coerceAtLeast(0)
    val visibleRange = visibleWindow(options.size, currentIndex, maxVisible = 5)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(DiveColors.DeepBlack.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
            .border(1.5.dp, DiveColors.SurfaceBorder.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .width(160.dp)
    ) {
        Text(
            text = spec.label.uppercase(),
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(180.dp)
        ) {
            // Sleek vertical track
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Gray.copy(alpha = 0.3f))
            ) {
                val fraction = if (options.size <= 1) 1f else currentIndex.toFloat() / (options.lastIndex.toFloat())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0.02f, 1f))
                        .background(DiveColors.DiveCyan, RoundedCornerShape(999.dp))
                )
            }

            // 5 closest options
            Column(
                verticalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxHeight()
            ) {
                for (i in visibleRange.reversed()) {
                    val opt = options[i]
                    val displayOpt = displaySettingValue(spec, opt)
                    val selected = i == currentIndex
                    Text(
                        text = displayOpt,
                        color = if (selected) DiveColors.DiveCyan else DiveColors.TextSecondary,
                        style = if (selected) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSensitivitySelector(
    current: SliderSensitivity,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 4.dp).fillMaxWidth(),
    ) {
        Text(
            text = "${current.level}",
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "/ ${SliderSensitivity.MAX.level}",
            color = DiveColors.TextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SliderMeterAdjuster(
    spec: CameraSettingSpec,
    value: String,
) {
    val index = spec.options.indexOf(value).coerceAtLeast(0)
    val totalSteps = spec.options.size
    val fraction = if (totalSteps <= 1) 1f else index.toFloat() / (spec.options.lastIndex.toFloat())

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(0.94f)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(DiveColors.DeepBlack.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(12.dp)
                    .background(DiveColors.DiveCyan, RoundedCornerShape(999.dp))
            )

            if (totalSteps in 2..15) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                ) {
                    repeat(totalSteps) { step ->
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (step <= index) DiveColors.DeepBlack.copy(alpha = 0.5f)
                                    else DiveColors.TextMuted.copy(alpha = 0.6f)
                                )
                        )
                    }
                }
            }
        }

        if (totalSteps in 2..7) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp)
            ) {
                spec.options.forEachIndexed { optIndex, opt ->
                    Text(
                        text = displaySettingValue(spec, opt),
                        color = if (optIndex == index) DiveColors.DiveCyan else DiveColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (optIndex == index) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(DiveColors.Critical.copy(alpha = 0.88f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FiberManualRecord,
                contentDescription = null,
                tint = DiveColors.TextPrimary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "REC",
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ZoomOverlay(
    zoomFactor: Double,
    modifier: Modifier = Modifier,
) {
    var showZoom by remember { mutableStateOf(false) }
    var lastZoom by remember { mutableStateOf(1.0) }

    LaunchedEffect(zoomFactor) {
        if (zoomFactor != lastZoom) {
            lastZoom = zoomFactor
            showZoom = true
            delay(1400)
            showZoom = false
        }
    }

    AnimatedVisibility(visible = showZoom, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            text = "x%.1f".format(zoomFactor),
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(DiveColors.DeepBlack.copy(alpha = 0.66f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CameraPreviewPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack),
    )
}

private fun visibleWindow(size: Int, highlightedIndex: Int, maxVisible: Int = 5): IntRange {
    if (size <= 0) {
        return IntRange.EMPTY
    }
    if (size <= maxVisible) {
        return 0..(size - 1)
    }
    val half = maxVisible / 2
    val start = (highlightedIndex - half).coerceIn(0, size - maxVisible)
    return start..(start + maxVisible - 1)
}

@Composable
private fun SliderSensitivityMeter(
    current: SliderSensitivity,
    modifier: Modifier = Modifier,
) {
    val totalSteps = SliderSensitivity.MAX.level
    val index = current.level - 1
    val fraction = index.toFloat() / (totalSteps - 1).toFloat()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(0.94f)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(DiveColors.DeepBlack.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                    .height(8.dp)
                    .background(DiveColors.DiveCyan, RoundedCornerShape(999.dp))
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        ) {
            Text(
                text = "1",
                color = if (current.level <= 10) DiveColors.DiveCyan else DiveColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (current.level <= 10) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = "${current.level}",
                color = DiveColors.DiveCyan,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "100",
                color = if (current.level >= 90) DiveColors.DiveCyan else DiveColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (current.level >= 90) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
