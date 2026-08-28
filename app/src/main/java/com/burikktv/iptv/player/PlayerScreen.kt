package com.burikktv.iptv.player

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.burikktv.iptv.R

private sealed interface PlaybackUiState {
    data object Buffering : PlaybackUiState
    data object Ready : PlaybackUiState
    data class Error(val message: String) : PlaybackUiState
}

@SuppressLint("OpaqueUnitKey")
@Composable
fun PlayerScreen(
    title: String,
    url: String,
    userAgent: String?,
    referrer: String?,
    clearKeyLicenseJson: String?,
    widevineLicenseUrl: String?,
    widevineHeaders: Map<String, String>,
    forceDash: Boolean,
    nowPlaying: String?,
    onChangeChannel: () -> Unit,
    modifier: Modifier = Modifier,
    showChangeChannelButton: Boolean = true,
) {
    val context = LocalContext.current
    var uiState by remember(url) { mutableStateOf<PlaybackUiState>(PlaybackUiState.Buffering) }
    val changeChannelFocusRequester = remember { FocusRequester() }

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

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
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
            update = { view ->
                view.player = exoPlayer
                // On error, NoSignalScreen (and its own buttons) is drawn on
                // top of this PlayerView, but drawing over it doesn't stop
                // the native controller underneath from still taking D-pad
                // focus and clicks — confirmed by the settings (gear) popup
                // opening from a remote press that should have landed on our
                // Compose buttons instead. Turning the controller off
                // entirely (not just hiding it) removes it from the focus
                // chain, so there's nothing left to compete with those
                // buttons for input.
                view.useController = uiState !is PlaybackUiState.Error

                // res/layout/view_player_controller.xml (wired up via
                // app:controller_layout_id in view_player.xml) adds this
                // TextView into PlayerView's own native control row, right
                // next to the settings button, so it's part of the same
                // Android focus group as play/pause/etc. — D-pad navigation
                // reaches it exactly like any other control button, with no
                // Compose-side focus wiring needed.
                view.findViewById<TextView>(R.id.exo_change_channel)?.apply {
                    text = context.getString(R.string.change_channel)
                    visibility = if (showChangeChannelButton) View.VISIBLE else View.GONE
                    setOnClickListener { onChangeChannel() }
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
                Text(text = title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(text = nowPlaying, color = Color.White.copy(alpha = 0.8f))
            }
        }

        when (val state = uiState) {
            is PlaybackUiState.Buffering -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
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
                var showErrorDetail by remember(state.message) { mutableStateOf(false) }
                BackHandler(enabled = showErrorDetail) { showErrorDetail = false }

                NoSignalScreen(modifier = Modifier.fillMaxSize())

                // PlayerView's native controller (and the "Ganti Channel"
                // button embedded in it) is fully disabled during an error —
                // see the `useController` line above — so this is a separate
                // Compose button just for this state, with its own explicit
                // focus grab since nothing else here has focus to hand off
                // from.
                if (showChangeChannelButton) {
                    LaunchedEffect(state.message) {
                        runCatching { changeChannelFocusRequester.requestFocus() }
                    }
                    ChangeChannelButton(onClick = onChangeChannel, focusRequester = changeChannelFocusRequester)
                }

                Surface(
                    onClick = { showErrorDetail = true },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .pointerInput(state.message) {
                            detectTapGestures(onTap = { showErrorDetail = true })
                        },
                ) {
                    Text(
                        text = stringResCompat(R.string.view_error_detail),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }

                if (showErrorDetail) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f))
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { showErrorDetail = false })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            onClick = {},
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .padding(24.dp)
                                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text(
                                    text = stringResCompat(R.string.error_detail_title),
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = state.message,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(top = 12.dp)
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
            is PlaybackUiState.Ready -> Unit
        }
    }
}

// Only used for the Error state (see the useController line above), where
// PlayerView's own control row — and the native "Ganti Channel" button
// embedded in it via view_player_controller.xml — is disabled entirely.
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ChangeChannelButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White,
        ),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 24.dp, bottom = 14.dp)
            .focusRequester(focusRequester)
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            },
    ) {
        Text(
            text = stringResCompat(R.string.change_channel),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun stringResCompat(id: Int): String = androidx.compose.ui.res.stringResource(id = id)
