package com.burikktv.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun ChannelGrid(
    channels: List<Channel>,
    favoriteIds: Set<String>,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = "",
    epgByChannelId: Map<String, List<EpgProgramme>> = emptyMap(),
) {
    if (channels.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 200.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            ChannelCard(
                channel = channel,
                isFavorite = channel.id in favoriteIds,
                nowPlaying = epgByChannelId.currentProgrammeTitle(channel.tvgId),
                onPlay = { onPlay(channel) },
                onToggleFavorite = { onToggleFavorite(channel) },
            )
        }
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    isFavorite: Boolean,
    nowPlaying: String?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(
        onClick = onPlay,
        onLongClick = onToggleFavorite,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            // androidx.tv.material3's own click handling is tuned for D-pad
            // "focus, then select" and can miss a plain single tap on a
            // touchscreen (tablets, phones). Detect the tap directly so touch
            // always works, independently of the D-pad/key-based path above.
            .pointerInput(onPlay, onToggleFavorite) {
                detectTapGestures(onTap = { onPlay() }, onLongPress = { onToggleFavorite() })
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF0E141B)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    )
                } else {
                    Text(
                        text = channel.name.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (isFavorite) {
                    Text(
                        text = "★",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
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
        }
    }
}
