package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disk-backed regression: repeated titles and large descriptions must not make search scan payloads. */
@RunWith(AndroidJUnit4::class)
class EpgSearchPerformanceTest {
    @Test fun largeGuideSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "search-performance.db")
        SQLiteDatabase.deleteDatabase(file)
        val now = System.currentTimeMillis()
        val channels = (0 until 2500).map { Channel("c$it", "Канал $it", "Тест", "https://example.invalid/$it") }
        val state = AppState(playlists = listOf(Playlist("p", "Test", "", "", channels)))
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                EpgCache.create(db)
                db.beginTransaction()
                try {
                    db.compileStatement("INSERT INTO guide_link VALUES (?,?,?)").use { statement ->
                        channels.forEach { channel ->
                            statement.bindString(1, channel.id); statement.bindString(2, "source")
                            statement.bindString(3, channel.id); statement.executeInsert()
                        }
                    }
                    db.compileStatement("INSERT INTO guide_programme VALUES (?,?,?,?,?,?)").use { statement ->
                        repeat(700000) { i ->
                            val titleId = i % 20000
                            val start = now - 2 * 86400000L + (i / 2500) * 1200000L
                            statement.bindString(1, "source"); statement.bindString(2, "c${i % 2500}")
                            statement.bindLong(3, start); statement.bindLong(4, start + 1200000L)
                            statement.bindString(5, if (titleId == 17) "Бильярд. Лига чемпионов" else "Передача номер $titleId")
                            statement.bindString(6, "Подробное описание передачи. ".repeat(12))
                            statement.executeInsert()
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                repeat(2) { run ->
                    val start = SystemClock.elapsedRealtime()
                    val result = EpgSearch.find(db, state, "бильярд", now)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    Log.i("EpgSearchBenchmark", "run=$run rows=700000 bytes=${file.length()} elapsedMs=$elapsed matches=${result.programmeCount}")
                    assertEquals(35, result.programmeCount)
                    assertTrue(result.programmes.all { it.programme.description.isEmpty() })
                    assertTrue("Search took ${elapsed}ms", elapsed < 10000)
                }
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
}
