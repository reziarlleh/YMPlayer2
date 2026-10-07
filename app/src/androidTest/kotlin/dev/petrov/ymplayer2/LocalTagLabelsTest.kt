@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.*
import dev.petrov.ymplayer2.shell.*
import org.junit.*

class LocalTagLabelsTest {
    @get:Rule val compose = createComposeRule()
    @After fun restore() { compose.runOnIdle { AppLanguages.select("ru") } }
    @Test fun missingTagsTranslateAcrossAllLanguagesAndRealNamesStayVerbatim() {
        val missing = Track("missing", "Track", "", "", Source.USB, 1, true, genre = "")
        val real = missing.copy(id = "real", artist = "Неизвестный исполнитель", album = "Без альбома", genre = "Без жанра")
        compose.runOnIdle { AppLanguages.initialize(InstrumentationRegistry.getInstrumentation().targetContext) }
        compose.setContent { Column {
            Text(missing.artistLabel(), Modifier.testTag("missing_artist"))
            Text(localGroupLabel(missing.album, Category.ALBUMS), Modifier.testTag("missing_album"))
            Text(localGroupLabel(missing.genre, Category.GENRES), Modifier.testTag("missing_genre"))
            Text(real.artistLabel(), Modifier.testTag("real_artist"))
            Text(localGroupLabel(real.album, Category.ALBUMS), Modifier.testTag("real_album"))
            Text(localGroupLabel(real.genre, Category.GENRES), Modifier.testTag("real_genre"))
        } }
        for (language in AppLanguages.available) {
            compose.runOnIdle { AppLanguages.select(language.tag) }
            compose.onNodeWithTag("missing_artist").assertTextEquals(trMessage("Неизвестный исполнитель"))
            compose.onNodeWithTag("missing_album").assertTextEquals(trMessage("Без альбома"))
            compose.onNodeWithTag("missing_genre").assertTextEquals(trMessage("Без жанра"))
            compose.onNodeWithTag("real_artist").assertTextEquals(real.artist)
            compose.onNodeWithTag("real_album").assertTextEquals(real.album)
            compose.onNodeWithTag("real_genre").assertTextEquals(real.genre)
        }
    }
}
