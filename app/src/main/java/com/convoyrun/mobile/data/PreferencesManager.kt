package com.convoyrun.mobile.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("convoyrun_prefs", Context.MODE_PRIVATE)

    private val _blockedAuthors = MutableStateFlow<Map<String, String>>(emptyMap())
    val blockedAuthors: StateFlow<Map<String, String>> = _blockedAuthors.asStateFlow()

    private val _filteredLanguages = MutableStateFlow<Set<String>>(emptySet())
    val filteredLanguages: StateFlow<Set<String>> = _filteredLanguages.asStateFlow()

    init {
        loadBlockedAuthors()
        loadFilteredLanguages()
    }

    // --- App Language ---

    fun getAppLanguage(): String? =
        prefs.getString("app_language", null)

    fun setAppLanguage(language: String?) {
        val editor = prefs.edit()
        if (language == null) {
            editor.remove("app_language")
        } else {
            editor.putString("app_language", language)
        }
        editor.apply()
    }

    // --- Language Filter ---

    private fun loadFilteredLanguages() {
        _filteredLanguages.value = prefs.getStringSet("filtered_languages", emptySet()) ?: emptySet()
    }

    fun setFilteredLanguages(languages: Set<String>) {
        prefs.edit().putStringSet("filtered_languages", languages).apply()
        _filteredLanguages.value = languages
    }

    fun isLanguageFiltered(language: String): Boolean {
        val filtered = _filteredLanguages.value
        if (filtered.isEmpty()) return true
        return filtered.contains(language)
    }

    fun matchesLanguageFilter(eventLanguages: List<String>): Boolean {
        val filtered = _filteredLanguages.value
        if (filtered.isEmpty()) return true
        return eventLanguages.any { filtered.contains(it) }
    }

    // --- Blocked Authors ---

    private fun loadBlockedAuthors() {
        val raw = prefs.getStringSet("blocked_authors", emptySet()) ?: emptySet()
        val map = mutableMapOf<String, String>()
        for (entry in raw) {
            val parts = entry.split("|||", limit = 2)
            if (parts.size == 2) {
                map[parts[0]] = parts[1]
            }
        }
        _blockedAuthors.value = map
    }

    private fun saveBlockedAuthors() {
        val raw = _blockedAuthors.value.map { (peerId, nick) -> "$peerId|||$nick" }.toSet()
        prefs.edit().putStringSet("blocked_authors", raw).apply()
    }

    fun blockAuthor(peerId: String, nick: String) {
        val current = _blockedAuthors.value.toMutableMap()
        current[peerId] = nick.ifEmpty { peerId.take(8) }
        _blockedAuthors.value = current
        saveBlockedAuthors()
    }

    fun unblockAuthor(peerId: String) {
        val current = _blockedAuthors.value.toMutableMap()
        current.remove(peerId)
        _blockedAuthors.value = current
        saveBlockedAuthors()
    }

    fun isBlocked(peerId: String): Boolean =
        _blockedAuthors.value.containsKey(peerId)
}
