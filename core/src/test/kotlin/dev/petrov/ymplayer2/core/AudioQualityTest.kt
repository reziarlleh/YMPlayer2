package dev.petrov.ymplayer2.core

import org.junit.Assert.*
import org.junit.Test

class AudioQualityTest {
    @Test fun oldOrMissingPreferencesUseAutoAndKnownKeysSurviveNormalization() {
        for (value in listOf(null, "", "unknown", "lossless", "320")) assertEquals(AudioQuality.AUTO, AudioQuality.fromKey(value))
        for (quality in AudioQuality.entries) assertEquals(quality, AudioQuality.fromKey(" ${quality.key.uppercase()} "))
    }
    @Test fun choicesPersistIndependentlyAndRestoreWithoutOrdinalCoupling() {
        var disk = AudioQualities()
        val settings = AudioQualityPreferences(save = { disk = it })
        settings.setStream(AudioQuality.ECONOMY)
        assertEquals(AudioQualities(AudioQuality.ECONOMY, AudioQuality.AUTO), disk)
        settings.setCache(AudioQuality.MAX)
        val restored = AudioQualityPreferences(AudioQualities(AudioQuality.fromKey(disk.stream.key), AudioQuality.fromKey(disk.cache.key)))
        assertEquals(AudioQualities(AudioQuality.ECONOMY, AudioQuality.MAX), restored.state.value)
        assertEquals(restored.state.value, settings.state.value)
    }
}
