package dev.petrov.ymplayer2
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.AndroidTasteStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class TastePendingStoreTest {
    @Test fun cachedMarksAndOrderedActionsSurviveNewStoreAndStayAccountScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = OfflineOwner("pending-test", "account-A")
        val other = OfflineOwner("pending-test", "account-B")
        val t = TasteTarget(TasteKind.TRACK, "123:7", "Track")
        val snapshot = TasteSnapshot(mapOf(TasteKind.TRACK to TasteList(setOf("123"))),
            listOf(TasteMutation(t, TasteAction.BLOCK), TasteMutation(t, TasteAction.UNBLOCK)))
        val store = AndroidTasteStore(context)
        try {
            store.write(owner, snapshot)
            assertEquals(snapshot, AndroidTasteStore(context).read(owner))
            assertEquals(TasteSnapshot(), store.read(other))
        } finally { store.write(owner, TasteSnapshot()) }
    }
}
