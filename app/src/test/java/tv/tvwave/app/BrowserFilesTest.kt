package tv.tvwave.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BrowserFilesTest {
    @Test fun backupPickerKeepsNewAndLegacyCopiesButNotUnrelatedFiles(){
        val root=kotlin.io.path.createTempDirectory("browser-").toFile()
        try{
            listOf("new.tvwb","old.tvwavebackup","upper.TVWB","wrong.txt","show.m3u8").forEach{File(root,it).writeText("")}
            File(root,"folder").mkdir()
            assertEquals(setOf("folder","new.tvwb","old.tvwavebackup","upper.TVWB"),BrowserFiles.list(root,root,false)!!.map{it.name}.toSet())
            assertEquals(setOf("folder","show.m3u8"),BrowserFiles.list(root,root,true)!!.map{it.name}.toSet())
            assertFalse(BrowserFiles.inside(root,File(root,"../outside")))
            assertFalse(BrowserFiles.inside(root,File(root.path+"-other")))
            assertEquals("/",BrowserFiles.path(root,root))
            assertEquals("/folder",BrowserFiles.path(root,File(root,"folder")))
        }finally{root.deleteRecursively()}
    }
    @Test fun misleadingExtensionCannotBypassBackupValidation(){
        assertTrue(BackupFile.accepts("renamed.tvwb"))
        assertThrows(UserError::class.java){BackupFile.decode("This is not a backup".repeat(5).toByteArray())}
    }
}
