package com.burikktv.iptv

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.burikktv.iptv.data.CustomPlaylistRepository
import com.burikktv.iptv.data.FavoritesRepository
import com.burikktv.iptv.data.LastWatchedRepository
import com.burikktv.iptv.data.PlaylistRepository

class BurikkTvApplication : Application(), SingletonImageLoader.Factory {

    val playlistRepository: PlaylistRepository by lazy { PlaylistRepository(this) }
    val favoritesRepository: FavoritesRepository by lazy { FavoritesRepository(this) }
    val customPlaylistRepository: CustomPlaylistRepository by lazy { CustomPlaylistRepository(this) }
    val lastWatchedRepository: LastWatchedRepository by lazy { LastWatchedRepository(this) }

    // Coil's default memory cache reserves a percentage of the app's memory
    // class (commonly ~25%), which on a device with a genuinely tiny Java
    // heap (some Android TV boxes cap it around 128MB) competes directly
    // with ExoPlayer's own buffering for the same limited space and was a
    // contributing factor in production OutOfMemoryError crashes on such
    // hardware. Channel logos here are small (list/grid thumbnails), so an
    // explicit small cap is plenty and leaves far more headroom for playback.
    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(6 * 1024 * 1024)
                    .build()
            }
            .build()
}
