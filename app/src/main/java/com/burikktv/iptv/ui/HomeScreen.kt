package com.burikktv.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.burikktv.iptv.R
import com.burikktv.iptv.data.model.Channel
import com.burikktv.iptv.data.model.FAVORITES_KEY
import com.burikktv.iptv.data.model.MANAGE_PLAYLISTS_KEY
import com.burikktv.iptv.data.model.SEARCH_KEY

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlayChannel: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    isOverlay: Boolean = false,
    currentChannelId: String? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val selectedKey by viewModel.selectedCountry.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val customPlaylistUrls by viewModel.customPlaylistUrls.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (isOverlay) Color.Black.copy(alpha = 0.65f) else MaterialTheme.colorScheme.background,
            ),
    ) {
        when (val state = uiState) {
            is HomeUiState.Loading -> LoadingContent()
            is HomeUiState.Error -> ErrorContent(message = state.message, onRetry = viewModel::refresh)
            is HomeUiState.Success -> {
                val favoriteChannels = state.channelsByCountry.values
                    .asSequence()
                    .flatten()
                    .filter { it.id in favoriteIds }
                    .toList()

                val entries = buildList {
                    add(CountryEntry(SEARCH_KEY, stringRes(R.string.search), null))
                    add(CountryEntry(FAVORITES_KEY, stringRes(R.string.favorites), null, favoriteChannels.size))
                    add(CountryEntry(MANAGE_PLAYLISTS_KEY, stringRes(R.string.manage_playlists), null))
                    state.channelsByCountry.forEach { (country, list) ->
                        add(CountryEntry(country, country, state.countryFlags[country], list.size))
                    }
                }

                val searchResults = remember(searchQuery, state.allChannels) {
                    val trimmed = searchQuery.trim()
                    if (trimmed.isBlank()) {
                        emptyList()
                    } else {
                        state.allChannels.filter { it.name.contains(trimmed, ignoreCase = true) }
                    }
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    CountryListPane(
                        entries = entries,
                        selectedKey = selectedKey,
                        onSelect = viewModel::selectCountry,
                        isOverlay = isOverlay,
                    )
                    if (selectedKey == SEARCH_KEY) {
                        SearchPane(
                            query = searchQuery,
                            onQueryChange = viewModel::updateSearchQuery,
                            results = searchResults,
                            favoriteIds = favoriteIds,
                            onPlay = onPlayChannel,
                            onToggleFavorite = { viewModel.toggleFavorite(it.id) },
                            compact = isOverlay,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    } else if (selectedKey == MANAGE_PLAYLISTS_KEY) {
                        ManagePlaylistsPane(
                            playlistUrls = customPlaylistUrls.toList(),
                            onAdd = viewModel::addCustomPlaylist,
                            onRemove = viewModel::removeCustomPlaylist,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    } else {
                        val currentChannels = when (selectedKey) {
                            FAVORITES_KEY, null -> favoriteChannels
                            else -> state.channelsByCountry[selectedKey].orEmpty()
                        }
                        val emptyMessage = if (selectedKey == FAVORITES_KEY || selectedKey == null) {
                            stringRes(R.string.no_favorites)
                        } else {
                            stringRes(R.string.no_channels)
                        }
                        if (isOverlay) {
                            CompactChannelList(
                                channels = currentChannels,
                                favoriteIds = favoriteIds,
                                onPlay = onPlayChannel,
                                onToggleFavorite = { viewModel.toggleFavorite(it.id) },
                                emptyMessage = emptyMessage,
                                currentChannelId = currentChannelId,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        } else {
                            ChannelGrid(
                                channels = currentChannels,
                                favoriteIds = favoriteIds,
                                onPlay = onPlayChannel,
                                onToggleFavorite = { viewModel.toggleFavorite(it.id) },
                                emptyMessage = emptyMessage,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringRes(R.string.loading_playlist),
            modifier = Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = stringRes(R.string.error_loading), color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.pointerInput(onRetry) {
                detectTapGestures(onTap = { onRetry() })
            },
        ) {
            Text(text = stringRes(R.string.retry))
        }
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id = id)
