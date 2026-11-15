package tv.tvwave.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Fixtures/assertions for Maestro's real Android permission + portable backup flow. */
class FileAccessWorkflowTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName=="tv.tvwave.app.debug" && android.os.Build.MODEL.contains("sdk"))
    }
    @Test fun emptyInstallation() {
        Repository(ctx).use { it.save(AppState(uiLanguage="ru"),true) }
    }
    @Test fun restoredConfiguration() {
        Repository(ctx).use {
            val state=it.load()
            assertEquals(1,state.playlists.size)
            assertEquals(16,state.channels.size)
            assertEquals(1,state.sources.size)
            assertEquals("ru",state.uiLanguage)
            assertEquals(100,state.fontPercent)
            assertEquals(6,state.history.size)
        }
    }
}
