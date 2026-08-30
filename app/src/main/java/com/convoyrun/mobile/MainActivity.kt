package com.convoyrun.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
import com.convoyrun.mobile.data.PreferencesManager
import com.convoyrun.mobile.model.ConvoyEvent
import com.convoyrun.mobile.p2p.P2pManager
import com.convoyrun.mobile.ui.*
import com.convoyrun.mobile.ui.theme.*
import kotlinx.datetime.*

class MainActivity : ComponentActivity() {

    private var prefsManager: PreferencesManager? = null
    private var p2pManager: P2pManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val prefs = PreferencesManager(applicationContext)
            prefsManager = prefs
            p2pManager = P2pManager(applicationContext, prefs)
            applyLocaleOverride(prefs)
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
        p2pManager?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop P2P and cleanup only when Activity is truly destroyed
        p2pManager?.stop()
        p2pManager?.destroy()
    }

    private fun applyLocaleOverride(prefs: PreferencesManager) {
        val lang = prefs.getAppLanguage()
        if (lang != null) {
            val locales = LocaleListCompat.forLanguageTags(lang)
            AppCompatDelegate.setApplicationLocales(locales)
        }
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

    var selectedDay by remember {
        mutableStateOf(
            Clock.System.todayIn(TimeZone.currentSystemDefault())
                .atStartOfDayIn(TimeZone.currentSystemDefault())
                .epochSeconds
        )
    }
    var selectedEvent by remember { mutableStateOf<ConvoyEvent?>(null) }

    val dayEvents = remember(selectedDay, events?.value) {
        p2pManager?.getEventsForDate(selectedDay) ?: emptyList()
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSecondary)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Image(
                        painter = painterResource(id = R.drawable.logo_text),
                        contentDescription = stringResource(R.string.app_title),
                        modifier = Modifier.height(40.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    .weight(0.4f)
            )

            HorizontalDivider(color = Divider, thickness = 1.dp)

            EventListView(
                events = dayEvents,
                onEventClicked = { selectedEvent = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.6f)
            )
        }
    }

    selectedEvent?.let { event ->
        EventDetailView(
            event = event,
            onDismiss = { selectedEvent = null },
            onBlockAuthor = { peerId, nick ->
                p2pManager?.blockAuthor(peerId, nick)
                selectedEvent = null
            }
        )
    }
}
