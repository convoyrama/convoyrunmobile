package com.convoyrama.convoyrun.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.convoyrama.convoyrun.R
import com.convoyrama.convoyrun.p2p.P2pManager

/**
 * Status indicator component
 *
 * Shows the current P2P connection status with a colored dot.
 * - Green: Online (connected to peers)
 * - Yellow: Searching (looking for peers)
 * - Gray: Offline (node not started)
 */
@Composable
fun StatusIndicator(
    status: P2pManager.Status,
    peerCount: Int,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            P2pManager.Status.ONLINE -> Color(0xFF4CAF50)    // Green
            P2pManager.Status.SEARCHING -> Color(0xFFFFC107) // Yellow
            P2pManager.Status.OFFLINE -> Color(0xFF757575)   // Gray
        },
        animationSpec = tween(durationMillis = 300),
        label = "statusDotColor"
    )

    val displayText = when {
        status == P2pManager.Status.ONLINE && peerCount > 0 -> "$peerCount"
        status == P2pManager.Status.SEARCHING -> stringResource(R.string.status_searching)
        else -> stringResource(R.string.status_offline)
    }

    // Pulsing animation only when searching
    val alpha: Float = if (status == P2pManager.Status.SEARCHING) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseAlpha = infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        pulseAlpha.value
    } else {
        1f
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = alpha))
        )

        // Status text
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
