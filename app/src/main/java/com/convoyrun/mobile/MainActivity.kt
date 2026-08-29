package com.convoyrun.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.convoyrun.mobile.data.PreferencesManager
import com.convoyrun.mobile.model.ConvoyEvent
import com.convoyrun.mobile.p2p.P2pManager
import com.convoyrun.mobile.ui.*
import com.convoyrun.mobile.ui.theme.*
import kotlinx.datetime.*

class MainActivity : ComponentActivity() {

    private lateinit var prefsManager: PreferencesManager
    private lateinit var p2pManager: P2pManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefsManager = PreferencesManager(applicationContext)
        p2pManager = P2pManager(applicationContext, prefsManager)

        applyLocaleOverride()

        setContent {
            ConvoyRunTheme {
                ConvoyRunApp(p2pManager, prefsManager)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            p2pManager.start()
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            p2pManager.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        p2pManager.destroy()
    }

    private fun applyLocaleOverride() {
        val lang = prefsManager.getAppLanguage()
        if (lang != null) {
            val locales = LocaleListCompat.forLanguageTags(lang)
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

@Composable
fun ConvoyRunApp(p2pManager: P2pManager, prefsManager: PreferencesManager) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            prefsManager = prefsManager,
            p2pManager = p2pManager,
            onBack = { showSettings = false }
        )
        return
    }

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

    val dayEvents = remember(selectedDay, events) {
        p2pManager.getEventsForDate(selectedDay)
    }

    Scaffold(
        topBar = {
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIndicator(
                        status = status,
                        peerCount = peerCount
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
        ) {
            CalendarView(
                events = events,
                onDaySelected = { selectedDay = it },
                selectedDay = selectedDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
            )

            HorizontalDivider(color = Divider, thickness = 1.dp)

            EventListView(
                events = dayEvents,
                onEventClicked = { selectedEvent = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
            )
        }
    }

    selectedEvent?.let { event ->
        EventDetailView(
            event = event,
            onDismiss = { selectedEvent = null },
            onBlockAuthor = { peerId, nick ->
                p2pManager.blockAuthor(peerId, nick)
                selectedEvent = null
            }
        )
    }
}
