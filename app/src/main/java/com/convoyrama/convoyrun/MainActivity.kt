package com.convoyrama.convoyrun

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.convoyrama.convoyrun.data.PreferencesManager
import com.convoyrama.convoyrun.model.ConvoyEvent
import com.convoyrama.convoyrun.p2p.P2pManager
import com.convoyrama.convoyrun.ui.*
import com.convoyrama.convoyrun.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.datetime.*

class MainActivity : AppCompatActivity() {

    private var prefsManager: PreferencesManager? = null
    private var p2pManager: P2pManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val prefs = PreferencesManager(applicationContext)
            prefsManager = prefs
            p2pManager = P2pManager(applicationContext, prefs)
            android.util.Log.i("MainActivity", "Init OK, nativeLoaded=${P2pManager.nativeLoaded}")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Init failed: ${e.message}", e)
        }

        setContent {
            ConvoyRunTheme {
                ConvoyRunApp(p2pManager, prefsManager)
            }
        }

        // Start P2P once on create — survives background/foreground transitions
        lifecycleScope.launch(Dispatchers.IO) {
            p2pManager?.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop P2P and cleanup only when Activity is truly destroyed
        p2pManager?.stop()
        p2pManager?.destroy()
    }
}

@Composable
fun ConvoyRunApp(p2pManager: P2pManager?, prefsManager: PreferencesManager?) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings && p2pManager != null && prefsManager != null) {
        SettingsScreen(
            prefsManager = prefsManager,
            p2pManager = p2pManager,
            onBack = { showSettings = false }
        )
        return
    }

    val status = p2pManager?.status?.collectAsStateWithLifecycle()
    val peerCount = p2pManager?.peerCount?.collectAsStateWithLifecycle()
    val events = p2pManager?.events?.collectAsStateWithLifecycle()
    val votes = p2pManager?.votes?.collectAsStateWithLifecycle()
    val myVotes = p2pManager?.myVotes?.collectAsStateWithLifecycle()

    var selectedDay by remember {
        mutableStateOf(
            Clock.System.todayIn(TimeZone.currentSystemDefault())
                .atStartOfDayIn(TimeZone.currentSystemDefault())
                .epochSeconds
        )
    }
    var selectedEvent by remember { mutableStateOf<ConvoyEvent?>(null) }

    val todayTimestamp = remember {
        Clock.System.todayIn(TimeZone.currentSystemDefault())
            .atStartOfDayIn(TimeZone.currentSystemDefault())
            .epochSeconds
    }
    val isTodaySelected = selectedDay == todayTimestamp

    val myPeerId = remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        myPeerId.value = p2pManager?.getMyPeerId() ?: ""
    }

    val dayEvents = remember(selectedDay, events?.value) {
        p2pManager?.getEventsForDate(selectedDay) ?: emptyList()
    }
    val todayEvents = remember(events?.value) {
        p2pManager?.getEventsForDate(todayTimestamp) ?: emptyList()
    }
    val upcomingEvents = remember(events?.value) {
        p2pManager?.getUpcomingEvents(7) ?: emptyList()
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSecondary)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Image(
                        painter = painterResource(id = R.drawable.logo_text),
                        contentDescription = stringResource(R.string.app_title),
                        modifier = Modifier.height(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.app_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusIndicator(
                        status = status?.value ?: P2pManager.Status.OFFLINE,
                        peerCount = peerCount?.value ?: 0
                    )
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgPrimary)
                .padding(paddingValues)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            CalendarView(
                events = events?.value ?: emptyList(),
                onDaySelected = { selectedDay = it },
                selectedDay = selectedDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.35f)
            )

            HorizontalDivider(color = Divider, thickness = 1.dp)

            EventListView(
                todayEvents = if (isTodaySelected) todayEvents else dayEvents,
                upcomingEvents = if (isTodaySelected) upcomingEvents else emptyList(),
                onEventClicked = { selectedEvent = it },
                votes = votes?.value ?: emptyMap(),
                myVotes = myVotes?.value ?: emptyMap(),
                myPeerId = myPeerId.value,
                onVote = { convoyId, direction ->
                    this@MainActivity.lifecycleScope.launch(Dispatchers.IO) {
                        p2pManager?.vote(convoyId, direction)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.65f)
            )
        }
    }

    selectedEvent?.let { event ->
        val eventVotes = votes?.value?.get(event.id) ?: emptyList()
        val eventScore = eventVotes.sumOf { it.vote }
        val eventMyVote = myVotes?.value?.get(event.id)
        EventDetailView(
            event = event,
            onDismiss = { selectedEvent = null },
            onBlockAuthor = { peerId, nick ->
                p2pManager?.blockAuthor(peerId, nick)
                selectedEvent = null
            },
            score = eventScore,
            myVote = eventMyVote
        )
    }
}
