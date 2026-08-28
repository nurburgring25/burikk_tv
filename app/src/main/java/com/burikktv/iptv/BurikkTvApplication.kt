package com.burikktv.iptv

import android.app.Application
import com.burikktv.iptv.data.CustomPlaylistRepository
import com.burikktv.iptv.data.EpgRepository
import com.burikktv.iptv.data.FavoritesRepository
import com.burikktv.iptv.data.PlaylistRepository

class BurikkTvApplication : Application() {

    val playlistRepository: PlaylistRepository by lazy { PlaylistRepository(this) }
    val favoritesRepository: FavoritesRepository by lazy { FavoritesRepository(this) }
    val customPlaylistRepository: CustomPlaylistRepository by lazy { CustomPlaylistRepository(this) }
    val epgRepository: EpgRepository by lazy { EpgRepository(this) }
}
