package com.burikktv.iptv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.customPlaylistsDataStore by preferencesDataStore(name = "custom_playlists")

class CustomPlaylistRepository(private val context: Context) {

    private val urlsKey = stringSetPreferencesKey("custom_playlist_urls")

    val playlistUrls: Flow<Set<String>> = context.customPlaylistsDataStore.data.map { prefs ->
        prefs[urlsKey] ?: emptySet()
    }

    suspend fun add(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        context.customPlaylistsDataStore.edit { prefs ->
            val current = prefs[urlsKey] ?: emptySet()
            prefs[urlsKey] = current + trimmed
        }
    }

    suspend fun remove(url: String) {
        context.customPlaylistsDataStore.edit { prefs ->
            val current = prefs[urlsKey] ?: emptySet()
            prefs[urlsKey] = current - url
        }
    }
}
