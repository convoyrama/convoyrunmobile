package com.convoyrun.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convoyrun.mobile.R
import com.convoyrun.mobile.data.PreferencesManager
import com.convoyrun.mobile.p2p.P2pManager
import com.convoyrun.mobile.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefsManager: PreferencesManager,
    p2pManager: P2pManager,
    onBack: () -> Unit
) {
    val blockedAuthors by prefsManager.blockedAuthors.collectAsState()
    val filteredLanguages by prefsManager.filteredLanguages.collectAsState()
    val allEvents = p2pManager.getAllEvents()

    var currentLang by remember { mutableStateOf(prefsManager.getAppLanguage()) }
    var selectedLangs by remember { mutableStateOf(filteredLanguages) }

    val supportedLanguages = listOf("en", "es", "pt")
    val languageNames = mapOf("en" to "English", "es" to "Español", "pt" to "Português")

    fun countEventsForLang(lang: String): Int =
        allEvents.count { it.event.languages.contains(lang) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSecondary)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                        tint = TextSecondary
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BgPrimary)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // --- App Language ---
            item {
                GroupLabel(stringResource(R.string.settings_lang_group))
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_lang_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.settings_lang_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // System default option
                            LangChip(
                                label = "Auto",
                                selected = currentLang == null,
                                onClick = {
                                    currentLang = null
                                    prefsManager.setAppLanguage(null)
                                }
                            )
                            for (lang in supportedLanguages) {
                                LangChip(
                                    label = lang.uppercase(),
                                    selected = currentLang == lang,
                                    onClick = {
                                        currentLang = lang
                                        prefsManager.setAppLanguage(lang)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // --- Language Filter ---
            item {
                GroupLabel(stringResource(R.string.settings_filter_group))
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
                    Column {
                        for (lang in supportedLanguages) {
                            val count = countEventsForLang(lang)
                            val isChecked = selectedLangs.isEmpty() || selectedLangs.contains(lang)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newSet = if (selectedLangs.isEmpty()) {
                                            supportedLanguages.filter { it != lang }.toSet()
                                        } else if (selectedLangs.contains(lang)) {
                                            selectedLangs - lang
                                        } else {
                                            selectedLangs + lang
                                        }
                                        selectedLangs = newSet
                                        prefsManager.setFilteredLanguages(newSet)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Accent,
                                        uncheckedColor = Divider,
                                        checkmarkColor = androidx.compose.ui.graphics.Color.White
                                    ),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = languageNames[lang] ?: lang,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$count events",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }

            // --- Blocked Authors ---
            item {
                GroupLabel(stringResource(R.string.settings_blocked_group))
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
                    if (blockedAuthors.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_blocked_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        Column {
                            blockedAuthors.forEach { (peerId, nick) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = nick,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = peerId.take(8) + "..." + peerId.takeLast(4),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                    TextButton(
                                        onClick = { prefsManager.unblockAuthor(peerId) },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = EventTypeCompetition
                                        )
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_unblock),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        fontWeight = FontWeight.Bold,
        letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) Accent else androidx.compose.ui.graphics.Color.Transparent,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Divider),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) androidx.compose.ui.graphics.Color.White else TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
