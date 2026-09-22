package com.burikktv.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.burikktv.iptv.R
import com.burikktv.iptv.data.model.Channel
import com.burikktv.iptv.data.model.FAVORITES_KEY
import com.burikktv.iptv.data.model.MANAGE_PLAYLISTS_KEY
import com.burikktv.iptv.data.model.SEARCH_KEY

/**
 * Below this width, a side-by-side country list + content Row doesn't leave
 * enough room for either pane (a phone in portrait is typically 360-430dp
 * wide) — matches Material's compact/medium width class boundary.
 */
private const val COMPACT_WIDTH_BREAKPOINT_DP = 600

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

                val currentChannels = when (selectedKey) {
                    FAVORITES_KEY, null -> favoriteChannels
                    else -> state.channelsByCountry[selectedKey].orEmpty()
                }
                val emptyMessage = if (selectedKey == FAVORITES_KEY || selectedKey == null) {
                    stringRes(R.string.no_favorites)
                } else {
                    stringRes(R.string.no_channels)
                }

                val isCompactWidth = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_BREAKPOINT_DP

                // Phone-portrait navigation: the country/category list and its
                // content are two full-width screens instead of a side-by-side
                // Row, since a phone-width Row would leave neither pane enough
                // room. Reset to the list whenever the width class itself
                // changes (e.g. a rotation crosses the breakpoint) so a stale
                // drill-down state never strands the user on a screen that no
                // longer applies. When opened as an overlay over an already
                // playing channel, MainActivity has just pointed selectedKey
                // at that channel's own country (see openOverlay), so this
                // starts straight on that category's content instead of
                // forcing the user back through the top-level category list
                // every single time they open the channel switcher — including
                // right after picking that channel from Favorites, since
                // selectedKey by then reflects the channel's real country, not
                // the Favorites tab it was originally picked from.
                var showCategoryList by remember(isCompactWidth) { mutableStateOf(!isOverlay) }
                BackHandler(enabled = isCompactWidth && !showCategoryList) {
                    showCategoryList = true
                }

                val content: @Composable (Modifier) -> Unit = { contentModifier ->
                    when (selectedKey) {
                        SEARCH_KEY -> SearchPane(
                            query = searchQuery,
                            onQueryChange = viewModel::updateSearchQuery,
                            results = searchResults,
                            favoriteIds = favoriteIds,
                            onPlay = onPlayChannel,
                            onToggleFavorite = { viewModel.toggleFavorite(it.id) },
                            compact = isOverlay,
                            modifier = contentModifier,
                        )
                        MANAGE_PLAYLISTS_KEY -> ManagePlaylistsPane(
                            playlistUrls = customPlaylistUrls.toList(),
                            onAdd = viewModel::addCustomPlaylist,
                            onRemove = viewModel::removeCustomPlaylist,
                            modifier = contentModifier,
                        )
                        else -> if (isOverlay) {
                            CompactChannelList(
                                channels = currentChannels,
                                favoriteIds = favoriteIds,
                                onPlay = onPlayChannel,
                                onToggleFavorite = { viewModel.toggleFavorite(it.id) },
                                emptyMessage = emptyMessage,
                                currentChannelId = currentChannelId,
                                modifier = contentModifier,
                            )
                        } else {
                            ChannelGrid(
                                channels = currentChannels,
                                favoriteIds = favoriteIds,
                                onPlay = onPlayChannel,
                                onToggleFavorite = { viewModel.toggleFavorite(it.id) },
                                emptyMessage = emptyMessage,
                                modifier = contentModifier,
                            )
                        }
                    }
                }

                if (isCompactWidth) {
                    if (showCategoryList) {
                        CountryListPane(
                            entries = entries,
                            selectedKey = selectedKey,
                            onSelect = { key ->
                                viewModel.selectCountry(key)
                                showCategoryList = false
                            },
                            isOverlay = isOverlay,
                            fullWidth = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            CompactContentHeader(
                                title = entries.firstOrNull { it.key == selectedKey }?.label.orEmpty(),
                                onBack = { showCategoryList = true },
                            )
                            content(Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        CountryListPane(
                            entries = entries,
                            selectedKey = selectedKey,
                            onSelect = viewModel::selectCountry,
                            isOverlay = isOverlay,
                        )
                        content(Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactContentHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Surface(
            onClick = onBack,
            shape = ClickableSurfaceDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.pointerInput(onBack) {
                detectTapGestures(onTap = { onBack() })
            },
        ) {
            Text(text = "←", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
        }
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
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
