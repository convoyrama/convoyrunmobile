package com.convoyrun.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convoyrun.mobile.R
import com.convoyrun.mobile.model.*
import com.convoyrun.mobile.ui.theme.*
import kotlinx.datetime.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Event list view showing events for a selected day
 *
 * Features:
 * - List of events sorted by meeting time
 * - Filter chips for event types
 * - Search bar
 * - Event cards with type, game, mode, server info
 */
@Composable
fun EventListView(
    events: List<ConvoyEvent>,
    onEventClicked: (ConvoyEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf<EventType?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Filter events
    val filteredEvents = remember(events, selectedFilter, searchQuery) {
        events.filter { event ->
            val matchesFilter = selectedFilter == null || event.event.eventType == selectedFilter
            val matchesSearch = searchQuery.isEmpty() ||
                    event.event.name.contains(searchQuery, ignoreCase = true) ||
                    event.event.server.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }.sortedBy { it.schedule.meetingTimestamp }
    }

    Column(modifier = modifier) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_events)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Divider
            )
        )

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text(stringResource(R.string.filter_all)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent.copy(alpha = 0.2f)
                )
            )

            EventType.values().forEach { type ->
                FilterChip(
                    selected = selectedFilter == type,
                    onClick = { selectedFilter = type },
                    label = { Text(getEventTypeName(type)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = getEventTypeColor(type).copy(alpha = 0.2f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Event list
        if (filteredEvents.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_events),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredEvents, key = { it.id }) { event ->
                    EventCard(
                        event = event,
                        onClick = { onEventClicked(event) }
                    )
                }
            }
        }
    }
}

/**
 * Event card component
 */
@Composable
private fun EventCard(
    event: ConvoyEvent,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = BgCard
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Event type badge + name
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = event.event.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Event type badge
                Surface(
                    modifier = Modifier.padding(start = 8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = getEventTypeColor(event.event.eventType).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = getEventTypeName(event.event.eventType),
                        style = MaterialTheme.typography.labelSmall,
                        color = getEventTypeColor(event.event.eventType),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Game + Mode
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Game badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = getGameColor(event.event.game).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = event.event.game.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = getGameColor(event.event.game),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Mode
                Text(
                    text = event.event.mode.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Meeting time + server
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Meeting time
                val meetingTime = formatMeetingTime(event.schedule.meetingTimestamp)
                Text(
                    text = "🕐 $meetingTime",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accent
                )

                // Server
                if (event.event.server.isNotEmpty()) {
                    Text(
                        text = event.event.server,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Route (if available)
            if (event.event.route.startCity.isNotEmpty() || event.event.route.destCity.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val routeText = buildString {
                    if (event.event.route.startCity.isNotEmpty()) {
                        append(event.event.route.startCity)
                        if (event.event.route.destCity.isNotEmpty()) {
                            append(" → ")
                            append(event.event.route.destCity)
                        }
                    } else if (event.event.route.destCity.isNotEmpty()) {
                        append(event.event.route.destCity)
                    }
                }
                Text(
                    text = "📍 $routeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Nickname
            if (event.nickname.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "👤 ${event.nickname}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Get event type display name
 */
private fun getEventTypeName(type: EventType): String {
    return when (type) {
        EventType.Convoy -> "Convoy"
        EventType.TruckShow -> "Truck Show"
        EventType.Exploration -> "Exploration"
        EventType.Competition -> "Competition"
        EventType.Cruise -> "Cruise"
        EventType.Other -> "Other"
    }
}

/**
 * Get event type color
 */
private fun getEventTypeColor(type: EventType): androidx.compose.ui.graphics.Color {
    return when (type) {
        EventType.Convoy -> EventTypeConvoy
        EventType.TruckShow -> EventTypeTruckShow
        EventType.Exploration -> EventTypeExploration
        EventType.Competition -> EventTypeCompetition
        EventType.Cruise -> EventTypeCruise
        EventType.Other -> EventTypeOther
    }
}

/**
 * Get game color
 */
private fun getGameColor(game: Game): androidx.compose.ui.graphics.Color {
    return when (game) {
        Game.ATS -> GameATS
        Game.ETS2 -> GameETS2
        Game.Other -> TextSecondary
    }
}

/**
 * Format meeting timestamp to readable time
 */
private fun formatMeetingTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp * 1000))
}
