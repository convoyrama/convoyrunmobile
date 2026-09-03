package com.convoyrama.convoyrun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoyrama.convoyrun.model.ConvoyEvent
import com.convoyrama.convoyrun.ui.theme.*
import kotlinx.datetime.*
import java.time.format.TextStyle
import java.util.Locale as JavaLocale

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

    val eventsByDay = remember(events) {
        events.groupBy { event ->
            val instant = Instant.fromEpochSeconds(event.schedule.meetingTimestamp)
            val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            localDate.atStartOfDayIn(TimeZone.currentSystemDefault()).epochSeconds
        }
    }

    Column(modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        // Month navigation header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    if (currentMonth == Month.JANUARY) {
                        currentMonth = Month.DECEMBER
                        currentYear -= 1
                    } else {
                        currentMonth = Month.entries[currentMonth.ordinal - 1]
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("‹", color = TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = "${currentMonth.name.lowercase().replaceFirstChar { it.uppercase() }} $currentYear",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )

            TextButton(
                onClick = {
                    if (currentMonth == Month.DECEMBER) {
                        currentMonth = Month.JANUARY
                        currentYear += 1
                    } else {
                        currentMonth = Month.entries[currentMonth.ordinal + 1]
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("›", color = TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Day of week headers
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            val locale = JavaLocale.getDefault()
            listOf(
                java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY,
                java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY,
                java.time.DayOfWeek.SUNDAY
            ).forEach { dayOfWeek ->
                Text(
                    text = dayOfWeek.getDisplayName(TextStyle.SHORT, locale).take(1).uppercase(locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    fontSize = 9.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Calendar grid
        MonthGrid(
            year = currentYear,
            month = currentMonth,
            eventsByDay = eventsByDay,
            selectedDay = selectedDay,
            onDaySelected = onDaySelected
        )
    }
}

@Composable
private fun MonthGrid(
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

    val totalCells = firstDayOfWeek - 1 + daysInMonth
    val weeksNeeded = (totalCells + 6) / 7

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        for (week in 0 until weeksNeeded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
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

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .padding(1.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSelected -> Accent
                                        isToday -> Accent.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    }
                                )
                                .then(
                                    if (isSelected) Modifier.shadow(4.dp, CircleShape)
                                    else Modifier
                                )
                                .clickable { onDaySelected(dayTimestamp) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        isSelected -> Color.White
                                        isToday -> Accent
                                        else -> TextPrimary
                                    },
                                    fontSize = 10.sp
                                )
                                if (hasEvents && !isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(AccentLight)
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f).height(30.dp))
                    }
                }
            }
        }
    }
}

private fun isLeapYear(year: Int): Boolean {
    return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

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
