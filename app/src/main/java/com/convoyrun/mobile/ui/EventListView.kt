package com.convoyrun.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoyrun.mobile.R
import com.convoyrun.mobile.model.*
import com.convoyrun.mobile.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val ALL_LANGUAGES = listOf(
    "es" to "ES", "en" to "EN", "pt" to "PT", "fr" to "FR", "de" to "DE",
    "it" to "IT", "nl" to "NL", "pl" to "PL", "ru" to "RU", "tr" to "TR",
    "cs" to "CS", "ro" to "RO", "sv" to "SV", "da" to "DA", "fi" to "FI",
    "no" to "NO", "hu" to "HU", "bg" to "BG", "ko" to "KO", "zh" to "ZH",
    "ja" to "JA"
)

@Composable
fun EventListView(
    events: List<ConvoyEvent>,
    onEventClicked: (ConvoyEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedType by remember { mutableStateOf<EventType?>(null) }
    var selectedLang by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredEvents = remember(events, selectedType, selectedLang, searchQuery) {
        events.filter { event ->
            val matchesType = selectedType == null || event.event.eventType == selectedType
            val matchesLang = selectedLang == null || event.event.languages.contains(selectedLang)
            val matchesSearch = searchQuery.isEmpty() ||
                    event.event.name.contains(searchQuery, ignoreCase = true) ||
                    event.event.server.contains(searchQuery, ignoreCase = true)
            matchesType && matchesLang && matchesSearch
        }.sortedBy { it.schedule.meetingTimestamp }
    }

    Column(modifier = modifier) {
        // Event type filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedType == null,
                onClick = { selectedType = null },
                label = {
                    Text(stringResource(R.string.filter_all), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.2f)),
                modifier = Modifier.height(26.dp)
            )
            EventType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = {
                        Text(stringResource(getEventTypeNameRes(type)), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = getEventTypeColor(type).copy(alpha = 0.2f)),
                    modifier = Modifier.height(26.dp)
                )
            }
        }

        // Language filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = selectedLang == null,
                onClick = { selectedLang = null },
                label = { Text("All langs", fontSize = 9.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.2f)),
                modifier = Modifier.height(24.dp)
            )
            ALL_LANGUAGES.forEach { (code, label) ->
                val count = events.count { it.event.languages.contains(code) }
                if (count > 0) {
                    FilterChip(
                        selected = selectedLang == code,
                        onClick = { selectedLang = if (selectedLang == code) null else code },
                        label = { Text("$label ($count)", fontSize = 9.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.2f)),
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            placeholder = {
                Text(stringResource(R.string.search_events), style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Divider
            )
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Event list
        if (filteredEvents.isEmpty()) {
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
                    items(filteredEvents, key = { it.id }) { event ->
                        EventCard(event = event, onClick = { onEventClicked(event) })
                    }
                }
                if (filteredEvents.size > 3) {
                    ScrollbarIndicator(modifier = Modifier.align(Alignment.CenterEnd), listState = listState)
                }
            }
        }
    }
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(4.dp), color = getGameColor(event.event.game).copy(alpha = 0.2f)) {
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
        Canvas(modifier = modifier.width(4.dp).fillMaxHeight().padding(vertical = 8.dp)) {
            val trackHeight = size.height
            val thumbHeight = (trackHeight * 0.2f).coerceAtLeast(20.dp.toPx())
            val thumbY = scrollPercentage * (trackHeight - thumbHeight)
            drawRoundRect(color = Divider.copy(alpha = 0.3f), size = size.copy(width = 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
            drawRoundRect(color = Accent.copy(alpha = 0.6f), size = Size(4.dp.toPx(), thumbHeight), topLeft = Offset(0f, thumbY), cornerRadius = CornerRadius(2.dp.toPx()))
        }
    }
}
