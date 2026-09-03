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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoyrama.convoyrun.R
import com.convoyrama.convoyrun.model.*
import com.convoyrama.convoyrun.model.VoteRecord
import com.convoyrama.convoyrun.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EventListView(
    todayEvents: List<ConvoyEvent>,
    upcomingEvents: List<ConvoyEvent>,
    onEventClicked: (ConvoyEvent) -> Unit,
    votes: Map<String, List<VoteRecord>> = emptyMap(),
    myVotes: Map<String, Int> = emptyMap(),
    myPeerId: String = "",
    onVote: (String, Int) -> Unit = { _, _ -> },
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.search_events),
                    style = MaterialTheme.typography.labelSmall
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.labelSmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Divider,
                cursorColor = Accent
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
                            EventCard(
                                event = event,
                                onClick = { onEventClicked(event) },
                                score = computeScore(votes[event.id]),
                                myVote = myVotes[event.id],
                                isOwnEvent = event.peerId == myPeerId,
                                onVote = { direction -> onVote(event.id, direction) }
                            )
                        }
                    }

                    // Upcoming section
                    if (filteredUpcoming.isNotEmpty()) {
                        item {
                            SectionHeader(text = stringResource(R.string.section_upcoming))
                        }
                        items(filteredUpcoming, key = { "upcoming-${it.id}" }) { event ->
                            EventCard(
                                event = event,
                                onClick = { onEventClicked(event) },
                                score = computeScore(votes[event.id]),
                                myVote = myVotes[event.id],
                                isOwnEvent = event.peerId == myPeerId,
                                onVote = { direction -> onVote(event.id, direction) }
                            )
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 2.dp),
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing
    )
    HorizontalDivider(
        color = Accent.copy(alpha = 0.1f),
        thickness = 1.dp
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun EventCard(
    event: ConvoyEvent,
    onClick: () -> Unit,
    score: Int = 0,
    myVote: Int? = null,
    isOwnEvent: Boolean = false,
    onVote: (Int) -> Unit = {}
) {
    val borderColor = getEventTypeColor(event.event.eventType)
    var isPressed by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = if (isPressed) 0.99f else 1f
                scaleY = if (isPressed) 0.99f else 1f
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { isPressed = true; onClick(); isPressed = false }
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard)
    ) {
        Column(modifier = Modifier.fillMaxWidth().drawBehind {
            drawLine(
                color = borderColor,
                start = Offset(0f, 0f),
                end = Offset(0f, size.height),
                strokeWidth = 3.dp.toPx()
            )
        }.padding(start = 9.dp, end = 12.dp, top = 11.dp, bottom = 10.dp)) {
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
                    text = stringResource(event.event.mode.displayNameRes),
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
                    text = "▶ $routeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp
                )
            }

            // Votes row
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Upvote button
                IconButton(
                    onClick = { if (!isOwnEvent) onVote(1) },
                    enabled = !isOwnEvent,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ThumbUp,
                        contentDescription = stringResource(R.string.vote_up),
                        tint = if (myVote == 1) Accent else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
                // Score
                Text(
                    text = "$score",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        score > 0 -> Accent
                        score < 0 -> EventTypeCompetition
                        else -> TextSecondary
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                // Downvote button
                IconButton(
                    onClick = { if (!isOwnEvent) onVote(-1) },
                    enabled = !isOwnEvent,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ThumbDown,
                        contentDescription = stringResource(R.string.vote_down),
                        tint = if (myVote == -1) EventTypeCompetition else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (isOwnEvent) {
                    Text(
                        text = stringResource(R.string.vote_self_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 8.sp
                    )
                }
            }

            // Nickname
            if (event.nickname.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "● ${event.nickname}",
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

private fun computeScore(votes: List<VoteRecord>?): Int {
    return votes?.sumOf { it.vote } ?: 0
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
