package com.convoyrun.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.convoyrun.mobile.R
import com.convoyrun.mobile.model.ConvoyEvent
import com.convoyrun.mobile.ui.theme.*
import androidx.compose.ui.res.stringResource
import kotlinx.datetime.*
import java.time.format.TextStyle
import java.util.Locale

/**
 * Calendar view showing 3 months at a time
 *
 * Features:
 * - Swipe left/right to navigate months
 * - Days with events are highlighted in accent color
 * - Tap on a day to see events for that day
 */
@Composable
fun CalendarView(
    events: List<ConvoyEvent>,
    onDaySelected: (Long) -> Unit,
    selectedDay: Long?,
    modifier: Modifier = Modifier
) {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    var currentMonth by remember { mutableStateOf(today.month) }
    var currentYear by remember { mutableStateOf(today.year) }

    // Map events by day timestamp (start of day)
    val eventsByDay = remember(events) {
        events.groupBy { event ->
            val instant = Instant.fromEpochSeconds(event.schedule.meetingTimestamp)
            val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            localDate.atStartOfDayIn(TimeZone.currentSystemDefault()).epochSeconds
        }
    }

    Column(modifier = modifier) {
        // Month navigation header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                if (currentMonth == Month.JANUARY) {
                    currentMonth = Month.DECEMBER
                    currentYear -= 1
                } else {
                    currentMonth = Month.entries[currentMonth.ordinal - 1]
                }
            }) {
                Text("‹", color = TextSecondary, fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }

            Text(
                text = "${java.time.Month.valueOf(currentMonth.name).getDisplayName(TextStyle.FULL, Locale.getDefault())} $currentYear",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            TextButton(onClick = {
                if (currentMonth == Month.DECEMBER) {
                    currentMonth = Month.JANUARY
                    currentYear += 1
                } else {
                    currentMonth = Month.entries[currentMonth.ordinal + 1]
                }
            }) {
                Text("›", color = TextSecondary, fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }
        }

        // Current month view
        MonthView(
            year = currentYear,
            month = currentMonth,
            eventsByDay = eventsByDay,
            selectedDay = selectedDay,
            onDaySelected = onDaySelected
        )
    }
}

/**
 * Single month view
 */
@Composable
private fun MonthView(
    year: Int,
    month: Month,
    eventsByDay: Map<Long, List<ConvoyEvent>>,
    selectedDay: Long?,
    onDaySelected: (Long) -> Unit
) {
    val daysInMonth = month.length(isLeapYear(year))
    val firstDayOfMonth = LocalDate(year, month, 1)
    val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value // 1=Monday, 7=Sunday
    val todayDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Day of week headers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf(
                stringResource(R.string.day_mon),
                stringResource(R.string.day_tue),
                stringResource(R.string.day_wed),
                stringResource(R.string.day_thu),
                stringResource(R.string.day_fri),
                stringResource(R.string.day_sat),
                stringResource(R.string.day_sun)
            ).forEach { day ->
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Calendar grid
        val totalCells = 42 // 6 weeks
        val rows = (totalCells / 7)

        for (week in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (dayOfWeek in 1..7) {
                    val cellIndex = week * 7 + dayOfWeek
                    val dayOfMonth = cellIndex - firstDayOfWeek + 1

                    if (dayOfMonth in 1..daysInMonth) {
                        val date = LocalDate(year, month, dayOfMonth)
                        val dayTimestamp = date.atStartOfDayIn(TimeZone.currentSystemDefault()).epochSeconds
                        val hasEvents = eventsByDay.containsKey(dayTimestamp)
                        val isSelected = selectedDay == dayTimestamp
                        val isToday = date == todayDate

                        DayCell(
                            day = dayOfMonth,
                            hasEvents = hasEvents,
                            isSelected = isSelected,
                            isToday = isToday,
                            onClick = { onDaySelected(dayTimestamp) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // Empty cell
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Single day cell
 */
@Composable
private fun DayCell(
    day: Int,
    hasEvents: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        isSelected -> Accent
        isToday -> Accent.copy(alpha = 0.2f)
        else -> Color.Transparent
    }

    val textColor = when {
        isSelected -> Color.White
        isToday -> Accent
        else -> TextPrimary
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )

            // Event indicator dot
            if (hasEvents && !isSelected) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(Accent)
                )
            }
        }
    }
}

/**
 * Check if a year is a leap year
 */
private fun isLeapYear(year: Int): Boolean {
    return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

/**
 * Get the length of a month
 */
private fun Month.length(isLeapYear: Boolean): Int {
    return when (this) {
        Month.JANUARY -> 31
        Month.FEBRUARY -> if (isLeapYear) 29 else 28
        Month.MARCH -> 31
        Month.APRIL -> 30
        Month.MAY -> 31
        Month.JUNE -> 30
        Month.JULY -> 31
        Month.AUGUST -> 31
        Month.SEPTEMBER -> 30
        Month.OCTOBER -> 31
        Month.NOVEMBER -> 30
        Month.DECEMBER -> 31
    }
}
