package dev.petrov.ymplayer2.core

import org.junit.Assert.*
import org.junit.Test

class ExportFileNameTest {
    private val track = Track("yandex:123:7", "Песня {artist}", "Имя/Группа", "Альбом", Source.YANDEX, 180, false)
    @Test fun substitutionsAreSinglePassAndNeverPaths() {
        assertEquals("01. Имя_Группа - Песня {artist}.mp3", ExportFileName.render(ExportFileName.DEFAULT, track, 0, 20))
        assertEquals("Альбом_123_7.mp3", ExportFileName.render("{album}_{id}.MP3", track, 0, 1))
        assertEquals("1000.mp3", ExportFileName.render("{number}", track, 999, 1000))
    }
    @Test fun rejectsUnknownOrBrokenTokensButAllowsCustomText() {
        listOf("", " ", "{path}", "{title", "{{title}}", "title}", "x".repeat(301)).forEach { assertFalse(it, ExportFileName.valid(it)) }
        listOf("{title}", "My music - {artist} ({album})", "custom").forEach { assertTrue(it, ExportFileName.valid(it)) }
    }
    @Test fun safeNamesHandleReservedNamesControlsAndUnicodeByteLimits() {
        assertEquals("_CON", ExportFileName.safe("CON"))
        assertEquals("_Lpt9.txt", ExportFileName.safe("Lpt9.txt"))
        assertEquals("track", ExportFileName.safe(".. "))
        assertEquals("a_b_c", ExportFileName.safe("a\nb\u0000c"))
        val long = ExportFileName.safe("🎵".repeat(100))
        assertEquals(180, long.toByteArray().size)
        assertFalse(long.contains('\uFFFD'))
    }
}
