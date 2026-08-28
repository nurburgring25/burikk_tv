package com.burikktv.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.burikktv.iptv.data.model.Channel
import com.burikktv.iptv.data.model.EpgProgramme
import com.burikktv.iptv.data.model.currentProgrammeTitle

/**
 * Row-based channel listing (channel + now-playing per line) used instead of
 * [ChannelGrid]'s bigger thumbnail cards when the menu is shown as an overlay
 * over the video — a dense list reads better than a thumbnail grid in the
 * narrower space a docked overlay panel leaves for content.
 */
@Composable
fun CompactChannelList(
    channels: List<Channel>,
    favoriteIds: Set<String>,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = "",
    epgByChannelId: Map<String, List<EpgProgramme>> = emptyMap(),
    currentChannelId: String? = null,
) {
    if (channels.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }

    // Read once per fresh composition of this list (i.e. each time the
    // overlay opens, since it's fully torn down and rebuilt when hidden) so
    // it jumps straight to the currently playing channel instead of always
    // starting at the top.
    val initialIndex = remember(channels, currentChannelId) {
        channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    // Scrolling to the current channel only moves it into view — it doesn't
    // move D-pad/remote focus there, which is a separate Compose focus system.
    // Without this, the first focusable row (or nothing) keeps focus and the
    // remote appears to still be "on" whatever was focused before the overlay
    // opened. Requesting focus is only meaningful once the target row has
    // actually been composed, which for the initially-visible index happens
    // by the time this effect runs.
    val currentRowFocusRequester = remember(channels, currentChannelId) { FocusRequester() }
    LaunchedEffect(channels, currentChannelId) {
        if (initialIndex >= 0 && channels.getOrNull(initialIndex)?.id == currentChannelId) {
            runCatching { currentRowFocusRequester.requestFocus() }
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize(), state = listState) {
        items(channels, key = { it.id }) { channel ->
            val isCurrent = channel.id == currentChannelId
            CompactChannelRow(
                channel = channel,
                isFavorite = channel.id in favoriteIds,
                isCurrent = isCurrent,
                nowPlaying = epgByChannelId.currentProgrammeTitle(channel.tvgId),
                onPlay = { onPlay(channel) },
                onToggleFavorite = { onToggleFavorite(channel) },
                modifier = if (isCurrent) Modifier.focusRequester(currentRowFocusRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun CompactChannelRow(
    channel: Channel,
    isFavorite: Boolean,
    isCurrent: Boolean,
    nowPlaying: String?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onPlay,
        onLongClick = onToggleFavorite,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier
            .fillMaxWidth()
            // Same reasoning as ChannelCard: androidx.tv.material3's own click
            // handling is D-pad-first and can miss a plain touch tap.
            .pointerInput(onPlay, onToggleFavorite) {
                detectTapGestures(onTap = { onPlay() }, onLongPress = { onToggleFavorite() })
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF0E141B), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    Text(
                        text = channel.name.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = channel.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                if (nowPlaying != null) {
                    Text(
                        text = nowPlaying,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (isCurrent) {
                Text(
                    text = "▶",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = if (isFavorite) 8.dp else 0.dp),
                )
            }
            if (isFavorite) {
                Text(text = "★", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
