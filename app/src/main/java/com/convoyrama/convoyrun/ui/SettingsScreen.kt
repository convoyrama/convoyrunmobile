package com.convoyrama.convoyrun.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.convoyrama.convoyrun.R
import com.convoyrama.convoyrun.data.PreferencesManager
import com.convoyrama.convoyrun.p2p.P2pManager
import com.convoyrama.convoyrun.ui.theme.*

@Composable
fun SettingsScreen(
    prefsManager: PreferencesManager,
    p2pManager: P2pManager,
    onBack: () -> Unit
) {
    val blockedAuthors by prefsManager.blockedAuthors.collectAsState()
    val filteredLanguages by prefsManager.filteredLanguages.collectAsState()
    val allEvents = remember { p2pManager.getAllEvents() }

    var currentLang by remember { mutableStateOf(prefsManager.getAppLanguage()) }
    var selectedLangs by remember { mutableStateOf(filteredLanguages) }

    // UI languages: only 3 (es, en, pt)
    val uiLanguages = listOf("es", "en", "pt")

    // Event language filter: all 21 from desktop
    val eventLanguages = listOf(
        "es", "en", "pt", "fr", "de", "it", "nl", "pl", "ru", "tr",
        "cs", "ro", "sv", "da", "fi", "no", "hu", "bg", "ko", "zh", "ja"
    )
    val eventLanguageNames = mapOf(
        "es" to "Español", "en" to "English", "pt" to "Português",
        "fr" to "Français", "de" to "Deutsch", "it" to "Italiano",
        "nl" to "Nederlands", "pl" to "Polski", "ru" to "Русский",
        "tr" to "Türkçe", "cs" to "Čeština", "ro" to "Română",
        "sv" to "Svenska", "da" to "Dansk", "fi" to "Suomi",
        "no" to "Norsk", "hu" to "Magyar", "bg" to "Български",
        "ko" to "한국어", "zh" to "中文", "ja" to "日本語"
    )

    val langCounts = remember(allEvents) {
        eventLanguages.associateWith { lang ->
            allEvents.count { it.event.languages.contains(lang) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        // TopBar with status bar padding
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSecondary)
                .windowInsetsPadding(WindowInsets.statusBars)
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

        // Content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // --- App Language (UI: only 3 languages) ---
            item {
                GroupLabel(stringResource(R.string.settings_lang_group))
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
                    Column(modifier = Modifier.padding(14.dp)) {
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LangChip(
                                label = "Auto",
                                selected = currentLang == null,
                                onClick = {
                                    currentLang = null
                                    prefsManager.setAppLanguage(null)
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                                }
                            )
                            for (lang in uiLanguages) {
                                LangChip(
                                    label = lang.uppercase(),
                                    selected = currentLang == lang,
                                    onClick = {
                                        currentLang = lang
                                        prefsManager.setAppLanguage(lang)
                                        val locales = LocaleListCompat.forLanguageTags(lang)
                                        AppCompatDelegate.setApplicationLocales(locales)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // --- Event Language Filter (all 21, scrollable) ---
            item {
                GroupLabel(stringResource(R.string.settings_filter_group))
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
                    Box(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        Column {
                            for (lang in eventLanguages) {
                                val count = langCounts[lang] ?: 0
                                val isChecked = selectedLangs.isEmpty() || selectedLangs.contains(lang)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val newSet = if (selectedLangs.isEmpty()) {
                                                eventLanguages.filter { it != lang }.toSet()
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
                                        text = eventLanguageNames[lang] ?: lang,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (count > 0) {
                                        Text(
                                            text = "$count",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    }
                                }
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
