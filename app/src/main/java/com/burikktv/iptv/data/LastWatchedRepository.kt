package com.burikktv.iptv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lastWatchedDataStore by preferencesDataStore(name = "last_watched")

/**
 * Remembers which channel was playing last, so the app can resume straight
 * into it on the next launch instead of opening on a menu.
 */
class LastWatchedRepository(private val context: Context) {

    private val channelIdKey = stringPreferencesKey("last_watched_channel_id")

    val lastWatchedChannelId: Flow<String?> = context.lastWatchedDataStore.data.map { prefs ->
        prefs[channelIdKey]
    }

    suspend fun save(channelId: String) {
        context.lastWatchedDataStore.edit { prefs ->
            prefs[channelIdKey] = channelId
        }
    }
}
