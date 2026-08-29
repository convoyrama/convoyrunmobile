package com.convoyrun.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.convoyrun.mobile.model.ConvoyEvent
import com.convoyrun.mobile.p2p.P2pManager
import com.convoyrun.mobile.ui.*
import com.convoyrun.mobile.ui.theme.*
import kotlinx.datetime.*

class MainActivity : ComponentActivity() {

    private lateinit var p2pManager: P2pManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        p2pManager = P2pManager(applicationContext)

        setContent {
            ConvoyRunTheme {
                ConvoyRunApp(p2pManager)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Start P2P node when app comes to foreground
        lifecycleScope.launch {
            p2pManager.start()
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop P2P node when app goes to background
        p2pManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        p2pManager.destroy()
    }
}

/**
 * Main ConvoyRun app composable
 */
@Composable
fun ConvoyRunApp(p2pManager: P2pManager) {
    val status by p2pManager.status.collectAsStateWithLifecycle()
    val peerCount by p2pManager.peerCount.collectAsStateWithLifecycle()
    val events by p2pManager.events.collectAsStateWithLifecycle()

    var selectedDay by remember {
        mutableStateOf(
            Clock.System.todayIn(TimeZone.currentSystemDefault())
                .atStartOfDayIn(TimeZone.currentSystemDefault())
                .epochSeconds
        )
    }
    var selectedEvent by remember { mutableStateOf<ConvoyEvent?>(null) }

    // Get events for selected day
    val dayEvents = remember(selectedDay, events) {
        p2pManager.getEventsForDate(selectedDay)
    }

    Scaffold(
        topBar = {
            // App bar with title and status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSecondary)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Accent
                    )
                    Text(
                        text = stringResource(R.string.app_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                StatusIndicator(
                    status = status,
                    peerCount = peerCount
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgPrimary)
                .padding(paddingValues)
        ) {
            // Calendar view (takes 50% of screen)
            CalendarView(
                events = events,
                onDaySelected = { selectedDay = it },
                selectedDay = selectedDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
            )

            // Divider
            HorizontalDivider(
                color = Divider,
                thickness = 1.dp
            )

            // Event list for selected day (takes 50% of screen)
            EventListView(
                events = dayEvents,
                onEventClicked = { selectedEvent = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
            )
        }
    }

    // Event detail modal
    selectedEvent?.let { event ->
        EventDetailView(
            event = event,
            onDismiss = { selectedEvent = null }
        )
    }
}
