package com.convoyrun.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.convoyrun.mobile.R
import com.convoyrun.mobile.model.*
import com.convoyrun.mobile.ui.theme.*
import kotlinx.datetime.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Event detail view - shows full event details
 *
 * Read-only view with:
 * - Event name, type, game, mode
 * - Meeting time with timezone
 * - Route details
 * - Server info
 * - Description
 * - Languages
 * - Nickname
 * - Flyer image (if available)
 * - Share button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailView(
    event: ConvoyEvent,
    onDismiss: () -> Unit,
    onBlockAuthor: (peerId: String, nick: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgSecondary
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(scrollState)
        ) {
            // Header with close and share buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = TextSecondary
                    )
                }

                Text(
                    text = stringResource(R.string.event_details),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )

                TextButton(
                    onClick = { onBlockAuthor(event.peerId, event.nickname) },
                    colors = ButtonDefaults.textButtonColors(contentColor = EventTypeCompetition)
                ) {
                    Text(
                        text = stringResource(R.string.block_author),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(onClick = {
                    try {
                        val shareText = buildShareText(event)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    } catch (_: Exception) { /* no app to share */ }
                }) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.share),
                        tint = Accent
                    )
                }
            }

            // Event name
            Text(
                text = event.event.name,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Event type badge + Game + Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Event type badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = getEventTypeColor(event.event.eventType).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = stringResource(getEventTypeNameRes(event.event.eventType)),
                        style = MaterialTheme.typography.labelLarge,
                        color = getEventTypeColor(event.event.eventType),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Game badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = getGameColor(event.event.game).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = event.event.game.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = getGameColor(event.event.game),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Mode
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BgCard
                ) {
                    Text(
                        text = event.event.mode.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Meeting time
            DetailSection(
                title = stringResource(R.string.detail_meeting),
                content = formatFullMeetingTime(event.schedule.meetingTimestamp, event.schedule.ianaTimeZone)
            )

            // Route (if available)
            if (event.event.route.startCity.isNotEmpty() || event.event.route.destCity.isNotEmpty()) {
                DetailSection(
                    title = stringResource(R.string.detail_route),
                    content = buildRouteText(event.event.route)
                )
            }

            // Server
            if (event.event.server.isNotEmpty()) {
                DetailSection(
                    title = stringResource(R.string.detail_server),
                    content = event.event.server
                )
            }

            // Description
            if (event.event.description.isNotEmpty()) {
                DetailSection(
                    title = stringResource(R.string.detail_description),
                    content = event.event.description
                )
            }

            // Languages
            if (event.event.languages.isNotEmpty()) {
                DetailSection(
                    title = stringResource(R.string.detail_languages),
                    content = event.event.languages.joinToString(", ")
                )
            }

            // Link
            if (event.event.link.isNotEmpty()) {
                DetailSection(
                    title = stringResource(R.string.detail_link),
                    content = event.event.link,
                    isLink = true,
                    onLinkClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.event.link))
                            context.startActivity(intent)
                        } catch (_: Exception) { /* no browser or invalid URL */ }
                    }
                )
            }

            // Nickname
            if (event.nickname.isNotEmpty()) {
                DetailSection(
                    title = stringResource(R.string.detail_creator),
                    content = event.nickname
                )
            }

            // Flyer image
            if (event.flyer?.url?.isNotEmpty() == true) {
                Spacer(modifier = Modifier.height(16.dp))
                AsyncImage(
                    model = event.flyer.url,
                    contentDescription = stringResource(R.string.detail_flyer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Detail section component
 */
@Composable
private fun DetailSection(
    title: String,
    content: String,
    isLink: Boolean = false,
    onLinkClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (isLink) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = Accent,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .then(
                        if (onLinkClick != null) {
                            Modifier.background(Accent.copy(alpha = 0.1f))
                        } else {
                            Modifier
                        }
                    )
            )
        } else {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }
    }
}

/**
 * Build share text for event
 */
private fun buildShareText(event: ConvoyEvent): String {
    return buildString {
        appendLine(event.event.name)
        appendLine()
        appendLine("🎮 ${event.event.game.name} — ${event.event.mode.name}")
        appendLine("📅 ${formatFullMeetingTime(event.schedule.meetingTimestamp, event.schedule.ianaTimeZone)}")
        if (event.event.server.isNotEmpty()) {
            appendLine("🖥️ ${event.event.server}")
        }
        if (event.event.route.startCity.isNotEmpty() || event.event.route.destCity.isNotEmpty()) {
            appendLine("📍 ${buildRouteText(event.event.route)}")
        }
        if (event.event.link.isNotEmpty()) {
            appendLine("🔗 ${event.event.link}")
        }
        if (event.event.description.isNotEmpty()) {
            appendLine()
            appendLine(event.event.description)
        }
        appendLine()
        appendLine("— ConvoyRun Mobile")
    }
}

/**
 * Format meeting time with timezone
 */
private fun formatFullMeetingTime(timestamp: Long, ianaTimeZone: String): String {
    val date = Date(timestamp * 1000)
    val tzLabel = ianaTimeZone.split("/").last().replace("_", " ")
    return try {
        val zoneId = java.time.ZoneId.of(ianaTimeZone)
        val sdf = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault())
        sdf.timeZone = java.util.TimeZone.getTimeZone(zoneId)
        "${sdf.format(date)} ($tzLabel)"
    } catch (_: Exception) {
        val sdf = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault())
        "${sdf.format(date)} ($tzLabel)"
    }
}

/**
 * Build route text
 */
private fun buildRouteText(route: Route): String {
    return buildString {
        if (route.startCity.isNotEmpty()) {
            append(route.startCity)
            if (route.startLocation.isNotEmpty()) {
                append(" (${route.startLocation})")
            }
        }
        if (route.destCity.isNotEmpty()) {
            if (isNotEmpty()) append(" → ")
            append(route.destCity)
            if (route.destLocation.isNotEmpty()) {
                append(" (${route.destLocation})")
            }
        }
    }
}

