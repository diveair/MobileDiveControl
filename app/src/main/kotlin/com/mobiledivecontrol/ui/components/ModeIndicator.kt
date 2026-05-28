package com.mobiledivecontrol.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.theme.DiveColors

@Composable
fun ModeIndicator(
    mode: AppMode,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (mode) {
        AppMode.CameraLive -> "📷 Camera" to DiveColors.DiveCyan
        AppMode.CameraAdjust -> "🎛 Adjust" to DiveColors.DiveTeal
        AppMode.PhoneCursor -> "📱 Cursor" to DiveColors.DeepBlue
        AppMode.PhoneTarget -> "🎯 Target" to DiveColors.DeepBlue
        AppMode.Safety -> "🛡 Safety" to DiveColors.Warning
        AppMode.Diagnostics -> "🔧 Diagnostics" to DiveColors.TextSecondary
        AppMode.Gallery -> "🖼 Gallery" to DiveColors.DiveCyan
    }

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300),
        label = "mode_color",
    )

    Text(
        text = label,
        color = animatedColor,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(
                color = animatedColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
