package com.mobiledivecontrol.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.BleSignal
import com.mobiledivecontrol.core.HousingButtonEvent
import com.mobiledivecontrol.core.SensorUpdate
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.viewmodel.DiveViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug simulation panel with the housing button layout.
 *
 * The launcher and the expanded panel move as one floating unit so the
 * interface can be positioned anywhere on screen during device testing.
 */
@Composable
fun DebugSimulationPanel(
    viewModel: DiveViewModel,
    modifier: Modifier = Modifier,
) {
    var panelVisible by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var interfaceOffset by remember { mutableStateOf<Offset?>(null) }
    var floatingSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) {
        if (!initialized) {
            initialized = true

            viewModel.advanceBle(BleSignal.StartScan)
            delay(300)
            viewModel.advanceBle(BleSignal.Connect)
            delay(200)
            viewModel.advanceBle(BleSignal.DiscoverServices)
            delay(200)
            viewModel.advanceBle(BleSignal.Subscribe)
            delay(200)
            viewModel.advanceBle(BleSignal.Ready)

            viewModel.onNotification("2A29", "UMEING".toByteArray())
            viewModel.onNotification("2A26", "A4.0".toByteArray())
            viewModel.onNotification("2A27", "V2.1".toByteArray())
            viewModel.onNotification("2A28", "S1.3".toByteArray())
            viewModel.onNotification("2A25", "DC-240526".toByteArray())

            viewModel.updateBattery(85)
            viewModel.updateSensor(SensorUpdate.WaterPressure(223.5))
            viewModel.updateSensor(SensorUpdate.WaterTemperature(24.3))
            viewModel.updateSensor(SensorUpdate.BarometricPressure(101.3))
            viewModel.updateSensor(SensorUpdate.CoverState(false))
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val buttonSizePx = with(density) { 16.dp.toPx() }
        val edgePaddingPx = with(density) { 4.dp.toPx() }
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val floatingWidthPx = floatingSize.width.toFloat().takeIf { it > 0f } ?: buttonSizePx
        val floatingHeightPx = floatingSize.height.toFloat().takeIf { it > 0f } ?: buttonSizePx
        val maxX = (containerWidthPx - floatingWidthPx - edgePaddingPx).coerceAtLeast(0f)
        val maxY = (containerHeightPx - floatingHeightPx).coerceAtLeast(0f)
        val defaultOffset = Offset(
            x = maxX,
            y = ((containerHeightPx - floatingHeightPx) / 2f).coerceAtLeast(0f),
        )

        LaunchedEffect(maxWidth, maxHeight, floatingSize, panelVisible) {
            interfaceOffset = interfaceOffset?.let { current ->
                Offset(
                    x = current.x.coerceIn(0f, maxX),
                    y = current.y.coerceIn(0f, maxY),
                )
            } ?: defaultOffset
        }

        val currentOffset = interfaceOffset ?: defaultOffset
        val dragModifier = Modifier.pointerInput(maxX, maxY, panelVisible) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                val nextOffset = interfaceOffset ?: defaultOffset
                interfaceOffset = Offset(
                    x = (nextOffset.x + dragAmount.x).coerceIn(0f, maxX),
                    y = (nextOffset.y + dragAmount.y).coerceIn(0f, maxY),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = currentOffset.x.roundToInt(),
                        y = currentOffset.y.roundToInt(),
                    )
                }
                .onGloballyPositioned { coordinates ->
                    floatingSize = coordinates.size
                },
        ) {
            AnimatedVisibility(
                visible = panelVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .then(dragModifier),
                ) {
                    DebugButtonCluster(viewModel = viewModel)
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = dragModifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(DiveColors.SurfaceCard.copy(alpha = 0.7f))
                    .border(1.dp, DiveColors.DiveCyan.copy(alpha = 0.2f), CircleShape)
                    .clickable { panelVisible = !panelVisible },
            ) {
                Icon(
                    imageVector = if (panelVisible) Icons.Rounded.Close else Icons.Rounded.BugReport,
                    contentDescription = "Toggle debug",
                    tint = DiveColors.DiveCyan.copy(alpha = 0.6f),
                    modifier = Modifier.size(8.dp),
                )
            }
        }
    }
}

@Composable
private fun DebugButtonCluster(
    viewModel: DiveViewModel,
) {
    Column(
        modifier = Modifier.width(150.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            HousingButton(
                label = "Z-",
                color = Color.Black,
                width = 31.dp,
                backgroundColor = Color.White,
                borderColor = Color.Black,
            ) {
                viewModel.simulateButton(HousingButtonEvent.ZoomOut)
            }
            HousingButton(
                label = "Z+",
                color = Color.Black,
                width = 31.dp,
                backgroundColor = Color.White,
                borderColor = Color.Black,
            ) {
                viewModel.simulateButton(HousingButtonEvent.ZoomIn)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            HousingButton(
                label = "OK",
                color = Color.Black,
                width = 30.dp,
                backgroundColor = Color.White,
                borderColor = Color.Black,
            ) {
                viewModel.simulateButton(HousingButtonEvent.Ok)
            }
            HousingButton(
                label = "SHUTTER",
                color = Color.Black,
                width = 74.dp,
                backgroundColor = Color.White,
                borderColor = Color.Black,
            ) {
                viewModel.simulateButton(HousingButtonEvent.Shutter)
            }
        }
        Spacer(modifier = Modifier.height(11.dp))
        HousingButton(
            label = "UP",
            color = Color.Black,
            width = 45.dp,
            backgroundColor = Color.White,
            borderColor = Color.Black,
        ) {
            viewModel.simulateButton(HousingButtonEvent.Up)
        }
        Spacer(modifier = Modifier.height(5.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            HousingButton(
                label = "LEFT",
                color = Color.Black,
                width = 52.dp,
                backgroundColor = Color.White,
                borderColor = Color.Black,
            ) {
                viewModel.simulateButton(HousingButtonEvent.Left)
            }
            HousingButton(
                label = "RIGHT",
                color = Color.Black,
                width = 52.dp,
                backgroundColor = Color.White,
                borderColor = Color.Black,
            ) {
                viewModel.simulateButton(HousingButtonEvent.Right)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        HousingButton(
            label = "DOWN",
            color = Color.Black,
            width = 48.dp,
            backgroundColor = Color.White,
            borderColor = Color.Black,
        ) {
            viewModel.simulateButton(HousingButtonEvent.Down)
        }
    }
}

@Composable
private fun HousingButton(
    label: String,
    color: Color,
    subtitle: String? = null,
    width: Dp = 52.dp,
    backgroundColor: Color = color.copy(alpha = 0.1f),
    borderColor: Color = color.copy(alpha = 0.3f),
    onClick: () -> Unit,
) {
    val currentOnClick = rememberUpdatedState(onClick)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        currentOnClick.value()
                        coroutineScope {
                            val repeatJob = launch {
                                delay(400)
                                while (true) {
                                    currentOnClick.value()
                                    delay(60)
                                }
                            }
                            tryAwaitRelease()
                            repeatJob.cancel()
                        }
                    }
                )
            }
            .padding(horizontal = 5.dp, vertical = 4.dp)
            .width(width),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = color.copy(alpha = 0.5f),
                fontSize = 4.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
