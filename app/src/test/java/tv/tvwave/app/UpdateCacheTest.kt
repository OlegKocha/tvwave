package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val pkg = "tv.tvwave.app"

    @Test fun removesInstalledAndOlderApksButPreservesPendingUpdateAndOtherFiles() {
        val updates = temp.newFolder("updates")
        val versions = mapOf("old.apk" to 40L, "installed.apk" to 41L, "pending.apk" to 42L)
        for (name in versions.keys + listOf("download.part", "broken.apk", "foreign.apk"))
            File(updates, name).writeText("fixture")
        val backup = temp.newFile("backup.json").apply { writeText("keep") }
        val nested = File(updates, "nested").apply { mkdir() }
        File(nested, "installed.apk").writeText("keep")
        val removed = UpdateCache.clean(updates, pkg, 41) { file ->
            when (file.name) {
                "foreign.apk" -> UpdateCache.Apk("another.app", 1)
                "broken.apk" -> null
                else -> UpdateCache.Apk(pkg, versions.getValue(file.name))
            }
        }
        assertEquals(2, removed)
        assertEquals(setOf("pending.apk", "download.part", "broken.apk", "foreign.apk", "nested"),
            updates.list()!!.toSet())
        assertEquals("keep", backup.readText())
        assertTrue(File(nested, "installed.apk").exists())
    }

    @Test fun cancelledInstallKeepsApkUntilThatVersionActuallyRunsAndCleanupIsIdempotent() {
        val updates = temp.newFolder("updates")
        val apk = File(updates, "ready.apk").apply { writeText("fixture") }
        val inspect: (File) -> UpdateCache.Apk? = { UpdateCache.Apk(pkg, 42) }
        assertEquals(0, UpdateCache.clean(updates, pkg, 41, inspect))
        assertTrue(apk.exists())
        assertEquals(1, UpdateCache.clean(updates, pkg, 42, inspect))
        assertFalse(apk.exists())
        assertEquals(0, UpdateCache.clean(updates, pkg, 42, inspect))
    }

    @Test fun unreadableApkDoesNotPreventOtherCleanup() {
        val updates = temp.newFolder("updates")
        val bad = File(updates, "bad.apk").apply { writeText("fixture") }
        File(updates, "installed.apk").writeText("fixture")
        assertEquals(1, UpdateCache.clean(updates, pkg, 41) {
            if (it == bad) error("Cannot read APK") else UpdateCache.Apk(pkg, 41)
        })
        assertTrue(bad.exists())
    }
}
