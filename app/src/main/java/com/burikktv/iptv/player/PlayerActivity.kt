package com.burikktv.iptv.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.burikktv.iptv.R
import com.burikktv.iptv.ui.theme.BurikkTvTheme

class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val referrer = intent.getStringExtra(EXTRA_REFERRER)
        val clearKeyLicenseJson = intent.getStringExtra(EXTRA_CLEAR_KEY_LICENSE_JSON)
        val widevineLicenseUrl = intent.getStringExtra(EXTRA_WIDEVINE_LICENSE_URL)
        val widevineHeaders = intent.getBundleExtra(EXTRA_WIDEVINE_LICENSE_HEADERS)
            ?.let { bundle -> bundle.keySet().associateWith { bundle.getString(it).orEmpty() } }
            .orEmpty()
        val forceDash = intent.getBooleanExtra(EXTRA_FORCE_DASH, false)
        val nowPlaying = intent.getStringExtra(EXTRA_NOW_PLAYING)

        setContent {
            BurikkTvTheme {
                if (url != null) {
                    PlayerScreen(
                        title = title,
                        url = url,
                        userAgent = userAgent,
                        referrer = referrer,
                        clearKeyLicenseJson = clearKeyLicenseJson,
                        widevineLicenseUrl = widevineLicenseUrl,
                        widevineHeaders = widevineHeaders,
                        forceDash = forceDash,
                        nowPlaying = nowPlaying,
                    )
                } else {
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_REFERRER = "extra_referrer"
        const val EXTRA_CLEAR_KEY_LICENSE_JSON = "extra_clear_key_license_json"
        const val EXTRA_WIDEVINE_LICENSE_URL = "extra_widevine_license_url"
        const val EXTRA_WIDEVINE_LICENSE_HEADERS = "extra_widevine_license_headers"
        const val EXTRA_FORCE_DASH = "extra_force_dash"
        const val EXTRA_NOW_PLAYING = "extra_now_playing"
    }
}

private sealed interface PlaybackUiState {
    data object Buffering : PlaybackUiState
    data object Ready : PlaybackUiState
    data class Error(val message: String) : PlaybackUiState
}

@SuppressLint("OpaqueUnitKey")
@Composable
private fun PlayerScreen(
    title: String,
    url: String,
    userAgent: String?,
    referrer: String?,
    clearKeyLicenseJson: String?,
    widevineLicenseUrl: String?,
    widevineHeaders: Map<String, String>,
    forceDash: Boolean,
    nowPlaying: String?,
) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf<PlaybackUiState>(PlaybackUiState.Buffering) }

    val exoPlayer = remember(url) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            if (!userAgent.isNullOrBlank()) setUserAgent(userAgent)
            val headers = buildMap {
                if (!referrer.isNullOrBlank()) put("Referer", referrer)
            }
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)

        // Clear Key streams carry their own decryption key inline (from the
        // playlist's #KODIPROP tags) rather than fetching one from a license
        // server, so the DRM session is built from a LocalMediaDrmCallback
        // instead of the default HTTP-based license request flow.
        if (!clearKeyLicenseJson.isNullOrBlank()) {
            val drmSessionManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalMediaDrmCallback(clearKeyLicenseJson.toByteArray(Charsets.UTF_8)))
            mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { if (forceDash) setMimeType(MimeTypes.APPLICATION_MPD) }
            .apply {
                // Unlike Clear Key, Widevine has no embedded key to hand ExoPlayer
                // directly — it's just a license server URL (plus optional auth
                // headers) from the playlist. ExoPlayer's default DRM session
                // manager already knows how to POST a Widevine challenge to a
                // license server and use the response, so setting this is enough;
                // no custom MediaDrmCallback like the Clear Key path needs.
                if (!widevineLicenseUrl.isNullOrBlank()) {
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(widevineLicenseUrl)
                            .setLicenseRequestHeaders(widevineHeaders)
                            .setMultiSession(true)
                            .build(),
                    )
                }
            }
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                // Switching between this HLS stream's bitrate renditions (adaptive
                // selection normally upgrades from a low rendition to a higher one
                // a few seconds into playback) reliably makes the video appear to
                // zoom in/crop and never recover. Three different attempts to
                // correct this after the fact from the app UI side — reacting to
                // onVideoSizeChanged once, reacting via AspectRatioListener, and
                // continuously re-asserting the aspect ratio on a timer — all
                // failed to fix it in testing, which points to the actual bad
                // frame data coming from the video decoder itself (e.g. a crop
                // window/padding handling difference between this stream's
                // per-rendition H.264 profiles/levels) rather than anything
                // PlayerView's display-side aspect ratio math is doing, so no
                // amount of container-level correction can fix it after the
                // fact. Locking track selection to the single highest bitrate
                // rendition the device supports avoids the rendition switch
                // entirely, which is the only thing that has reliably prevented
                // this in testing.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setForceHighestSupportedBitrate(true)
                    .build()
                setMediaItem(mediaItem)
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        uiState = when (playbackState) {
                            Player.STATE_READY -> PlaybackUiState.Ready
                            Player.STATE_BUFFERING -> PlaybackUiState.Buffering
                            else -> uiState
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        uiState = PlaybackUiState.Error(error.message ?: "Unknown playback error")
                    }
                })
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                // Inflated so the XML-only `app:surface_type="texture_view"`
                // attribute takes effect (see res/layout/view_player.xml for why:
                // PlayerView's default SurfaceView can fall out of sync with
                // Compose's layout after a mid-playback resize, e.g. an HLS
                // quality switch, leaving the video looking cropped).
                (LayoutInflater.from(ctx).inflate(R.layout.view_player, null, false) as PlayerView).apply {
                    player = exoPlayer
                    useController = true
                    controllerAutoShow = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!nowPlaying.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text = title, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(text = nowPlaying, color = Color.White.copy(alpha = 0.8f))
            }
        }

        when (val state = uiState) {
            is PlaybackUiState.Buffering -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResCompat(R.string.player_loading),
                        color = Color.White,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            is PlaybackUiState.Error -> {
                Text(
                    text = stringResCompat(R.string.player_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            is PlaybackUiState.Ready -> Unit
        }
    }
}

@Composable
private fun stringResCompat(id: Int): String = androidx.compose.ui.res.stringResource(id = id)
