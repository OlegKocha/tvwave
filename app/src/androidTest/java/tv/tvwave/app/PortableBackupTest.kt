package tv.tvwave.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.security.KeyStore

/** Uses only the separate debug package and synthetic channels; never touches the family install. */
@RunWith(AndroidJUnit4::class)
class PortableBackupTest {
    @Test fun passwordFreeRestoreWithFreshDeviceKeyAndRejectDamage() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".debug"))
        context.deleteDatabase("tvwave.db")
        val id="56e6a36d-8015-42b4-94f0-d9a08fa01a91"
        val raw=buildString{append("#EXTM3U\n");repeat(2500){i->append("#EXTINF:-1 group-title=\"Family\",Channel $i\nhttps://example.invalid/channel/$i.m3u8?token=synthetic\n")}}
        val p=Playlist(id,"Test family","",raw,M3u.parse(raw,id))
        val original=AppState(listOf(p),setOf(p.channels.last().id),listOf(p.channels.first().id),p.channels.first().id,true,true,"ru",true)
        Repository(context).use{it.save(original);assertEquals(original,it.load())}
        val backup=BackupFile.encode(original)
        val file=java.io.File(context.cacheDir,"portable-test.tvwavebackup");file.writeBytes(backup)
        assertArrayEquals(backup,file.readBytes())
        // Like reinstall: old DB and old installation-specific key are both absent.
        context.deleteDatabase("tvwave.db")
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry("tvwave-state-v1")}
        Repository(context).use { repo ->
            assertEquals(AppState(),repo.load())
            val broken=backup.clone();broken[broken.lastIndex]=(broken.last().toInt() xor 1).toByte()
            try{BackupFile.decode(broken);fail()}catch(_:UserError){}
            assertEquals(AppState(),repo.load())
            val restored=BackupFile.decode(file.readBytes())
            repo.save(restored);assertEquals(original,repo.load())
        }
        file.delete()
    }
}
