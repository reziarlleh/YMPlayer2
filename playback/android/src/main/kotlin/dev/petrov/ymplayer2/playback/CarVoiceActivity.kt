package dev.petrov.ymplayer2.playback

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/** Route Android's implicit voice intent through the same session as a car's PlayFromSearch. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CarVoiceActivity : Activity() {
    private var browser: MediaBrowser? = null
    private val timeout = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null || intent.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            finish()
            return
        }
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty().take(200)
        val hints = Bundle().apply { putBoolean(CAR_CATALOG_HINT, true) }
        browser = MediaBrowser(this, ComponentName(this, AudioService::class.java), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                browser?.let { MediaController(this@CarVoiceActivity, it.sessionToken).transportControls.playFromSearch(query, null) }
                finish()
            }
            override fun onConnectionFailed() = finish()
            override fun onConnectionSuspended() = finish()
        }, hints)
        timeout.postDelayed({ finish() }, 10_000)
        browser?.connect()
    }
    override fun onDestroy() {
        timeout.removeCallbacksAndMessages(null)
        browser?.disconnect()
        super.onDestroy()
    }
}
