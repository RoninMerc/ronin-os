package au.com.roningroup.evieassistant

import android.app.Activity
import android.os.Bundle

class VoiceLaunchActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LISTEN_ONCE = "listen_once"
        const val MODE_START_WAKE = "start_wake"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_START_WAKE -> AssistantService.startWake(this)
            else -> AssistantService.listenOnce(this)
        }

        window.decorView.postDelayed({
            finish()
            overridePendingTransition(0, 0)
        }, 180)
    }
}
