package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.designsystem.InterfaceScalePreferences
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterfaceScaleClipsTest {
    @Test fun enlargedNativeControlsKeepOneTapPlaybackScreenPolicyAndVideoAspect() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val preferences = InterfaceScalePreferences(instrument.targetContext)
        val original = preferences.read()
        try {
            instrument.runOnMainSync { preferences.save(130) }
            ClipStartupTest().explicitOpenPlaysPausedCheckpointButAutomaticRestoreKeepsPause()
            ClipStartupTest().firstSurfaceAndReattachedSurfaceKeepVideoProportionsWithoutRotation()
        } finally { instrument.runOnMainSync { preferences.save(original) } }
    }
}
