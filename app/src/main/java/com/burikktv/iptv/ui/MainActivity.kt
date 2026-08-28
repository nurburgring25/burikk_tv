package com.burikktv.iptv.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.burikktv.iptv.BurikkTvApplication
import com.burikktv.iptv.player.PlayerActivity
import com.burikktv.iptv.ui.theme.BurikkTvTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(application as BurikkTvApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BurikkTvTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onPlayChannel = { channel, nowPlaying ->
                        val intent = Intent(this, PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
                            putExtra(PlayerActivity.EXTRA_URL, channel.streamUrl)
                            putExtra(PlayerActivity.EXTRA_USER_AGENT, channel.userAgent)
                            putExtra(PlayerActivity.EXTRA_REFERRER, channel.referrer)
                            putExtra(PlayerActivity.EXTRA_CLEAR_KEY_LICENSE_JSON, channel.clearKeyLicenseJson)
                            putExtra(PlayerActivity.EXTRA_WIDEVINE_LICENSE_URL, channel.widevineLicenseUrl)
                            if (channel.widevineLicenseHeaders.isNotEmpty()) {
                                putExtra(
                                    PlayerActivity.EXTRA_WIDEVINE_LICENSE_HEADERS,
                                    Bundle().apply {
                                        channel.widevineLicenseHeaders.forEach { (key, value) -> putString(key, value) }
                                    },
                                )
                            }
                            putExtra(PlayerActivity.EXTRA_FORCE_DASH, channel.forceDash)
                            putExtra(PlayerActivity.EXTRA_NOW_PLAYING, nowPlaying)
                        }
                        startActivity(intent)
                    },
                )
            }
        }
    }
}
