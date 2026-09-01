package com.burikktv.iptv.player

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.media3.exoplayer.DefaultLoadControl
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
    url: String,
    userAgent: String?,
    referrer: String?,
    clearKeyLicenseJson: String?,
    widevineLicenseUrl: String?,
    widevineHeaders: Map<String, String>,
    forceDash: Boolean,
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
            .apply {
                // Some IPTV sources put ".m3u8" in the URL *fragment*
                // (…?shk_cid=hdgd04#.m3u8) rather than the path, as a hint
                // for players that sniff the extension client-side. The
                // fragment is never sent to the server and isn't part of the
                // last path segment ExoPlayer's own extension-based sniffing
                // looks at, so a URL like that gets misread as a generic
                // media file (whatever the actual path extension is, e.g.
                // ".php") and handed to the plain container extractors,
                // which can't parse an HLS text playlist and fail with
                // UnrecognizedInputFormatException — even though the stream
                // itself is fine (confirmed by VLC, which sniffs content
                // rather than trusting the URL). Every entry here is from an
                // M3U/IPTV playlist, which in practice is always HLS unless
                // explicitly tagged as DASH, so it's set explicitly instead
                // of leaving it to extension sniffing.
                setMimeType(if (forceDash) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
            }
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
            .setLoadControl(
                // The default LoadControl targets up to 50s of buffered
                // media with no byte-size cap — for a live channel that's
                // pure downside (extra latency, no seek/rewatch benefit),
                // and on a low-RAM Android TV box it's what was actually
                // driving an OutOfMemoryError: a high-bitrate H.265-in-TS
                // stream buffering that many seconds ahead can need tens of
                // MB of encoded samples in memory, which doesn't fit in some
                // OEM TV boxes' ~128MB per-app heap. Both a much shorter
                // buffer window and an explicit byte ceiling are set so
                // memory use stays bounded regardless of the stream's
                // bitrate/codec, not just its duration.
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs= */ 6_000,
                        /* maxBufferMs= */ 12_000,
                        /* bufferForPlaybackMs= */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs= */ 2_000,
                    )
                    .setTargetBufferBytes(10 * 1024 * 1024)
                    .build(),
            )
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
                    // Focusability itself (not just when to call
                    // requestFocus) is set in `update`, below — see the
                    // comment there for why it needs to be conditional.
                    // The controller auto-hides by setting its whole button
                    // row GONE, which silently drops focus off whatever
                    // button held it (e.g. play/pause) — nothing else claims
                    // it afterward. The next OK press then lands on no
                    // focused view and does nothing; only a directional press
                    // (Up) triggers Android's focus-search to find this
                    // PlayerView and land on it, so OK only works on the
                    // press *after* that. Reclaiming focus on this root view
                    // the instant the controller hides means it's already
                    // holding focus for the next press, so a single OK both
                    // re-shows the controller and works every time.
                    // Guarded on `useController` (rather than reclaiming focus
                    // unconditionally): this same visibility callback also
                    // fires when `useController` is explicitly turned off for
                    // the Error state below, and reclaiming focus on the
                    // PlayerView then would pull D-pad focus away from the
                    // Compose "Ganti Channel"/"Lihat Detail Error" buttons
                    // drawn on top of it — the exact focus-stealing bug the
                    // `useController` toggle exists to prevent.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            if (visibility != View.VISIBLE && useController) {
                                requestFocus()
                            }
                        },
                    )
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

                // Whether this native view (and its controller's own buttons
                // — play/pause, settings, "Ganti Channel", etc.) should
                // participate in D-pad focus at all. Toggling isFocusable on
                // the PlayerView root alone isn't enough: PlayerView is a
                // ViewGroup, and its controller's individual buttons are
                // independently focusable regardless of the root's own flag,
                // so blocking descendant focus explicitly is required too.
                // This has to be false — not just "don't proactively focus
                // it" — whenever the channel overlay is showing on top of it
                // (showChangeChannelButton false) or during Error, otherwise
                // Android's own focus-search can still land on one of those
                // buttons on its own. That's exactly what was happening when
                // selecting a channel from the overlay that ends up failing:
                // useController is still true through the brief Buffering
                // phase before the error arrives, so the controller's
                // buttons are live and focusable for that window, stealing
                // focus from the overlay's channel list before Error even
                // hides them.
                val shouldHoldFocus = showChangeChannelButton && uiState !is PlaybackUiState.Error
                if (view.isFocusable != shouldHoldFocus) {
                    view.isFocusable = shouldHoldFocus
                    view.isFocusableInTouchMode = shouldHoldFocus
                    view.descendantFocusability = if (shouldHoldFocus) {
                        ViewGroup.FOCUS_BEFORE_DESCENDANTS
                    } else {
                        ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                    if (!shouldHoldFocus && view.hasFocus()) {
                        view.clearFocus()
                    }
                }
                // Without this, nothing actually holds D-pad focus when the
                // player screen first appears (or right after the channel
                // overlay closes), so the first OK press is spent just
                // moving focus onto this view instead of reaching
                // PlayerView's key handling — the controller only shows up
                // on a *second* press (e.g. after pressing Up to move focus
                // here first). Proactively claiming focus whenever this is
                // the active surface means a single OK press both focuses
                // and shows the controller at once.
                if (shouldHoldFocus && !view.hasFocus()) {
                    view.requestFocus()
                }

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
