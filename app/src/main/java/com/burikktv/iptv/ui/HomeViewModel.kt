package com.burikktv.iptv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.burikktv.iptv.BurikkTvApplication
import com.burikktv.iptv.data.CustomPlaylistRepository
import com.burikktv.iptv.data.EpgRepository
import com.burikktv.iptv.data.FavoritesRepository
import com.burikktv.iptv.data.PlaylistRepository
import com.burikktv.iptv.data.model.Channel
import com.burikktv.iptv.data.model.EpgProgramme
import com.burikktv.iptv.data.model.FAVORITES_KEY
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val epgRepository: EpgRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState

    /** Programmes keyed by `tvg-id`, filled in once the EPG feed(s) finish
     * loading in the background — channel browsing never waits on this. */
    private val _epgByChannelId = MutableStateFlow<Map<String, List<EpgProgramme>>>(emptyMap())
    val epgByChannelId: StateFlow<Map<String, List<EpgProgramme>>> = _epgByChannelId

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
                    loadEpg(result.epgUrls)
                }
                .onFailure { error ->
                    _uiState.value = HomeUiState.Error(error.message ?: "Terjadi kesalahan")
                }
        }
    }

    private fun loadEpg(epgUrls: Set<String>) {
        if (epgUrls.isEmpty()) {
            _epgByChannelId.value = emptyMap()
            return
        }
        viewModelScope.launch {
            _epgByChannelId.value = runCatching { epgRepository.load(epgUrls) }.getOrDefault(emptyMap())
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

    companion object {
        fun factory(app: BurikkTvApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(
                        app.playlistRepository,
                        app.favoritesRepository,
                        app.customPlaylistRepository,
                        app.epgRepository,
                    ) as T
                }
            }
    }
}
