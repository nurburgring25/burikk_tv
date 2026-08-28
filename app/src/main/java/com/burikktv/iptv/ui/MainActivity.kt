package com.burikktv.iptv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.burikktv.iptv.BurikkTvApplication
import com.burikktv.iptv.R
import com.burikktv.iptv.data.model.Channel
import com.burikktv.iptv.player.PlayerScreen
import com.burikktv.iptv.ui.theme.BurikkTvTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(application as BurikkTvApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BurikkTvTheme {
                var playingChannel by remember { mutableStateOf<Channel?>(null) }
                var nowPlaying by remember { mutableStateOf<String?>(null) }
                var isMenuOpen by remember { mutableStateOf(false) }
                // Guards the startup auto-play resolution below so it only
                // ever runs once per app session, not every time the playlist
                // state re-emits (e.g. a manual refresh later on).
                var hasResolvedStartupChannel by remember { mutableStateOf(false) }

                val uiState by viewModel.uiState.collectAsState()

                LaunchedEffect(uiState) {
                    val state = uiState
                    if (hasResolvedStartupChannel || state !is HomeUiState.Success) return@LaunchedEffect
                    hasResolvedStartupChannel = true
                    val allChannels = state.allChannels
                    if (allChannels.isEmpty()) {
                        // Nothing to auto-play (e.g. a fresh install whose
                        // playlist genuinely has no channels) — fall back to
                        // the full menu so the user isn't stuck on a spinner.
                        isMenuOpen = true
                        return@LaunchedEffect
                    }
                    val lastWatchedId = viewModel.getLastWatchedChannelId()
                    val resumed = lastWatchedId?.let { id -> allChannels.firstOrNull { it.id == id } }
                    // Falls back to the very first channel in the list both
                    // on a fresh install (nothing saved yet) and if the saved
                    // channel no longer exists in the current playlist.
                    playingChannel = resumed ?: allChannels.first()
                }

                fun openOverlay(channel: Channel) {
                    viewModel.selectCountry(channel.country)
                    isMenuOpen = true
                }

                // Back only ever closes the overlay when it's open. It never
                // opens the overlay — with it closed, back falls through to
                // the default system behavior (exit the app), same as if
                // nothing here had handled it.
                BackHandler(enabled = isMenuOpen) {
                    isMenuOpen = false
                }

                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val channel = playingChannel
                    if (channel != null) {
                        PlayerScreen(
                            title = channel.name,
                            url = channel.streamUrl,
                            userAgent = channel.userAgent,
                            referrer = channel.referrer,
                            clearKeyLicenseJson = channel.clearKeyLicenseJson,
                            widevineLicenseUrl = channel.widevineLicenseUrl,
                            widevineHeaders = channel.widevineLicenseHeaders,
                            forceDash = channel.forceDash,
                            nowPlaying = nowPlaying,
                            onChangeChannel = { openOverlay(channel) },
                            showChangeChannelButton = !isMenuOpen,
                        )
                    } else if (!isMenuOpen) {
                        // Still resolving the playlist/startup channel — the
                        // full menu never flashes on screen first, only this
                        // lightweight loading state (or its error/retry state).
                        StartupGate(uiState = uiState, onRetry = viewModel::refresh)
                    }

                    if (isMenuOpen) {
                        // Docked to a fraction of the screen (not fillMaxSize)
                        // when overlaying live video, so the video stays clearly
                        // visible alongside the channel list instead of being
                        // fully covered by it.
                        val overlayModifier = if (channel != null) {
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .fillMaxWidth(0.6f)
                                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                        } else {
                            Modifier.fillMaxSize()
                        }
                        HomeScreen(
                            viewModel = viewModel,
                            onPlayChannel = { selected, np ->
                                playingChannel = selected
                                nowPlaying = np
                                if (channel == null) {
                                    // First-ever selection, from the full-screen
                                    // menu (nothing was playing behind it) — hand
                                    // off straight into the player.
                                    isMenuOpen = false
                                }
                                // Picking a channel from the overlay (channel !=
                                // null here) deliberately leaves it open, so the
                                // user can keep flipping through channels without
                                // reopening the overlay every time — closing it
                                // is a separate, explicit Back press.
                                viewModel.saveLastWatched(selected.id)
                            },
                            modifier = overlayModifier,
                            isOverlay = channel != null,
                            currentChannelId = channel?.id,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupGate(uiState: HomeUiState, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when (uiState) {
            is HomeUiState.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = stringResource(R.string.error_loading), color = Color.White)
                    Text(
                        text = uiState.message,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                    )
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.pointerInput(onRetry) {
                            detectTapGestures(onTap = { onRetry() })
                        },
                    ) {
                        Text(text = stringResource(R.string.retry))
                    }
                }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.loading_playlist),
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
