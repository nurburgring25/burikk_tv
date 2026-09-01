package com.burikktv.iptv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.burikktv.iptv.BurikkTvApplication
import com.burikktv.iptv.data.CustomPlaylistRepository
import com.burikktv.iptv.data.FavoritesRepository
import com.burikktv.iptv.data.LastWatchedRepository
import com.burikktv.iptv.data.PlaylistRepository
import com.burikktv.iptv.data.model.Channel
import com.burikktv.iptv.data.model.FAVORITES_KEY
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class Success(
        val channelsByCountry: Map<String, List<Channel>>,
        val allChannels: List<Channel>,
        val countryFlags: Map<String, String>,
        val fromCache: Boolean,
    ) : HomeUiState
}

class HomeViewModel(
    private val playlistRepository: PlaylistRepository,
    private val favoritesRepository: FavoritesRepository,
    private val customPlaylistRepository: CustomPlaylistRepository,
    private val lastWatchedRepository: LastWatchedRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState

    val favoriteIds: StateFlow<Set<String>> = favoritesRepository.favoriteIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val customPlaylistUrls: StateFlow<Set<String>> = customPlaylistRepository.playlistUrls
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _selectedCountry = MutableStateFlow<String?>(null)
    val selectedCountry: StateFlow<String?> = _selectedCountry

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    init {
        viewModelScope.launch {
            // Reacts to the persisted custom-playlist set on startup and to every
            // later add/remove, so the channel list always reflects it without a
            // manual refresh.
            customPlaylistRepository.playlistUrls.collect { urls ->
                loadPlaylist(urls)
            }
        }
    }

    fun refresh() {
        loadPlaylist(customPlaylistUrls.value)
    }

    private fun loadPlaylist(customUrls: Set<String>) {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            playlistRepository.load(customUrls)
                .onSuccess { result ->
                    val grouped = result.channels
                        .groupBy { it.country }
                        .toSortedMap(compareBy { it.lowercase() })
                    _uiState.value = HomeUiState.Success(
                        channelsByCountry = grouped,
                        allChannels = result.channels,
                        countryFlags = result.countryFlags,
                        fromCache = result.fromCache,
                    )
                    if (_selectedCountry.value == null) {
                        _selectedCountry.value = FAVORITES_KEY
                    }
                }
                .onFailure { error ->
                    _uiState.value = HomeUiState.Error(error.message ?: "Terjadi kesalahan")
                }
        }
    }

    fun selectCountry(country: String) {
        _selectedCountry.value = country
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch {
            favoritesRepository.toggle(channelId)
        }
    }

    fun addCustomPlaylist(url: String) {
        viewModelScope.launch {
            customPlaylistRepository.add(url)
        }
    }

    fun removeCustomPlaylist(url: String) {
        viewModelScope.launch {
            customPlaylistRepository.remove(url)
        }
    }

    /** One-shot read (not a StateFlow) so callers can tell "not saved yet"
     * apart from "still loading the persisted value" — a StateFlow would need
     * a placeholder initial value that's indistinguishable from "never watched
     * anything", which would race the caller into always falling back to the
     * first channel on every cold start. */
    suspend fun getLastWatchedChannelId(): String? = lastWatchedRepository.lastWatchedChannelId.first()

    fun saveLastWatched(channelId: String) {
        viewModelScope.launch {
            lastWatchedRepository.save(channelId)
        }
    }

    companion object {
        fun factory(app: BurikkTvApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(
                        app.playlistRepository,
                        app.favoritesRepository,
                        app.customPlaylistRepository,
                        app.lastWatchedRepository,
                    ) as T
                }
            }
    }
}
