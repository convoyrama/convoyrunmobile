package com.convoyrama.convoyrun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoyrama.convoyrun.R
import com.convoyrama.convoyrun.model.*
import com.convoyrama.convoyrun.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EventListView(
    todayEvents: List<ConvoyEvent>,
    upcomingEvents: List<ConvoyEvent>,
    onEventClicked: (ConvoyEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredToday = remember(todayEvents, searchQuery) {
        todayEvents.filter { event ->
            searchQuery.isEmpty() ||
                    event.event.name.contains(searchQuery, ignoreCase = true) ||
                    event.event.server.contains(searchQuery, ignoreCase = true) ||
                    event.nickname.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredUpcoming = remember(upcomingEvents, searchQuery) {
        upcomingEvents.filter { event ->
            searchQuery.isEmpty() ||
                    event.event.name.contains(searchQuery, ignoreCase = true) ||
                    event.event.server.contains(searchQuery, ignoreCase = true) ||
                    event.nickname.contains(searchQuery, ignoreCase = true)
        }
    }

    val hasAnyEvents = filteredToday.isNotEmpty() || filteredUpcoming.isNotEmpty()

    Column(modifier = modifier) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.search_events),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Divider
            )
        )

        if (!hasAnyEvents) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_events),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
            }
        } else {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Today section
                    if (filteredToday.isNotEmpty()) {
                        item {
                            SectionHeader(text = stringResource(R.string.section_today))
                        }
                        items(filteredToday, key = { "today-${it.id}" }) { event ->
                            EventCard(event = event, onClick = { onEventClicked(event) })
                        }
                    }

                    // Upcoming section
                    if (filteredUpcoming.isNotEmpty()) {
                        item {
                            SectionHeader(text = stringResource(R.string.section_upcoming))
                        }
                        items(filteredUpcoming, key = { "upcoming-${it.id}" }) { event ->
                            EventCard(event = event, onClick = { onEventClicked(event) })
                        }
                    }
                }
                if ((filteredToday.size + filteredUpcoming.size) > 3) {
                    ScrollbarIndicator(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        listState = listState
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Accent,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun EventCard(event: ConvoyEvent, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = BgCard)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            // Name + type badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = event.event.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    modifier = Modifier.padding(start = 6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = getEventTypeColor(event.event.eventType).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = stringResource(getEventTypeNameRes(event.event.eventType)),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = getEventTypeColor(event.event.eventType),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Game + mode
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = getGameColor(event.event.game).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = event.event.game.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = getGameColor(event.event.game),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Text(
                    text = event.event.mode.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontSize = 9.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Time + server
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMeetingTime(event.schedule.meetingTimestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Accent,
                    fontSize = 12.sp
                )
                if (event.event.server.isNotEmpty()) {
                    Text(
                        text = event.event.server,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp
                    )
                }
            }

            // Route
            if (event.event.route.startCity.isNotEmpty() || event.event.route.destCity.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                val routeText = buildString {
                    if (event.event.route.startCity.isNotEmpty()) append(event.event.route.startCity)
                    if (event.event.route.destCity.isNotEmpty()) {
                        if (isNotEmpty()) append(" → ")
                        append(event.event.route.destCity)
                    }
                }
                Text(
                    text = routeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp
                )
            }

            // Nickname
            if (event.nickname.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.nickname,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 9.sp
                )
            }
        }
    }
}

private fun formatMeetingTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp * 1000))
}

@Composable
private fun ScrollbarIndicator(modifier: Modifier = Modifier, listState: LazyListState) {
    val firstVisibleItemIndex = listState.firstVisibleItemIndex
    val totalItems = listState.layoutInfo.totalItemsCount
    if (totalItems > 0) {
        val scrollPercentage = firstVisibleItemIndex.toFloat() / (totalItems - 1).coerceAtLeast(1)
        Canvas(
            modifier = modifier
                .width(4.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
        ) {
            val trackHeight = size.height
            val thumbHeight = (trackHeight * 0.2f).coerceAtLeast(20.dp.toPx())
            val thumbY = scrollPercentage * (trackHeight - thumbHeight)
            drawRoundRect(
                color = Divider.copy(alpha = 0.3f),
                size = size.copy(width = 4.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            drawRoundRect(
                color = Accent.copy(alpha = 0.6f),
                size = Size(4.dp.toPx(), thumbHeight),
                topLeft = Offset(0f, thumbY),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}
