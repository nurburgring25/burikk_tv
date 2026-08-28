package com.burikktv.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.burikktv.iptv.data.model.FAVORITES_KEY
import com.burikktv.iptv.data.model.MANAGE_PLAYLISTS_KEY
import com.burikktv.iptv.data.model.SEARCH_KEY

data class CountryEntry(
    val key: String,
    val label: String,
    val flag: String?,
    val count: Int? = null,
)

@Composable
fun CountryListPane(
    entries: List<CountryEntry>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            items(entries, key = { it.key }) { entry ->
                val isSelected = entry.key == selectedKey
                ListItem(
                    selected = isSelected,
                    onClick = { onSelect(entry.key) },
                    leadingContent = {
                        Text(
                            text = when (entry.key) {
                                SEARCH_KEY -> "🔍"
                                FAVORITES_KEY -> "★"
                                MANAGE_PLAYLISTS_KEY -> "➕"
                                else -> entry.flag ?: "🌐"
                            },
                        )
                    },
                    headlineContent = {
                        Text(text = entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = entry.count?.let { count ->
                        {
                            Text(
                                text = count.toString(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .pointerInput(entry.key) {
                            detectTapGestures(onTap = { onSelect(entry.key) })
                        },
                )
            }
        }
    }
}
