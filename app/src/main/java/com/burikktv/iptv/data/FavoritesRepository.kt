package com.burikktv.iptv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

class FavoritesRepository(private val context: Context) {

    private val favoritesKey = stringSetPreferencesKey("favorite_channel_ids")

    val favoriteIds: Flow<Set<String>> = context.favoritesDataStore.data.map { prefs ->
        prefs[favoritesKey] ?: emptySet()
    }

    suspend fun toggle(channelId: String) {
        context.favoritesDataStore.edit { prefs ->
            val current = prefs[favoritesKey] ?: emptySet()
            prefs[favoritesKey] = if (channelId in current) current - channelId else current + channelId
        }
    }
}
