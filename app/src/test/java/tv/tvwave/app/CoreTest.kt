package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CoreTest {
    private val raw="#EXTM3U\n#EXTINF:-1 group-title=\"Main, family\",Channel One\nhttps://example.invalid/one.m3u8\n#EXTINF:-1,Channel Two\n#EXTGRP:Films\nhttps://example.invalid/two.m3u8\n"
    private val id="56e6a36d-8015-42b4-94f0-d9a08fa01a91"
    private fun sample():AppState {
        val p=Playlist(id,"Семейный","",raw,M3u.parse(raw,id))
        return AppState(listOf(p),setOf(p.channels[1].id),listOf(p.channels[0].id),p.channels[0].id,true,true,"ru",true)
    }
    @Test fun suppliedPlaylistWhenAvailable(){
        val path=System.getenv("TVWAVE_PLAYLIST_FIXTURE")
        org.junit.Assume.assumeTrue(path!=null)
        val channels=M3u.parse(SourceIO.text(SourceIO.read(java.io.File(path!!).inputStream(),SourceIO.MAX_PLAYLIST)),id)
        assertEquals(2499,channels.size);assertEquals(13,channels.map{it.group}.distinct().size)
    }
    @Test fun m3uAndM3u8ContentAndGroups(){val c=M3u.parse("\uFEFF"+raw.replace("\n","\r\n"),id);assertEquals(2,c.size);assertEquals("Main, family",c[0].group);assertEquals("Films",c[1].group)}
    @Test fun tokenRotationKeepsIdentity(){val a=M3u.parse(raw,id);val b=M3u.parse(raw.replace("one.m3u8","one.m3u8?token=changed"),id);assertEquals(a[0].id,b[0].id)}
    @Test fun badSourcesRejected(){for(s in listOf("<html>error</html>","#EXTM3U\n", "#EXTM3U\n#EXTINF:-1,Broken", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n",raw.replace("https://example.invalid/one.m3u8","file:///etc/passwd"))){try{M3u.parse(s);fail("Must reject invalid source")}catch(_:UserError){}}}
    @Test fun relativeHttpURLsResolved(){val c=M3u.parse(raw.replace("https://example.invalid/one.m3u8","one.m3u8"),id,"https://example.invalid/list");assertEquals("https://example.invalid/one.m3u8",c[0].url)}
    @Test fun readLimitEnforced(){try{SourceIO.read(ByteArray(30).inputStream(),20);fail()}catch(_:UserError){}}
    @Test fun completeStateRoundtrip(){assertEquals(sample(),StateCodec.decode(StateCodec.encode(sample())))}
    @Test fun futureSchemaAndBrokenReferencesRejected(){val json=String(StateCodec.encode(sample()));for(s in listOf(json.replace("\"version\":1","\"version\":2"),json.replace("\"last\":\"$id:","\"last\":\"missing:"))){try{StateCodec.decode(s.toByteArray());fail()}catch(_:UserError){}}}
    @Test fun fullEncryptedBackupPortableAndTamperResistant(){
        val source=sample();val password="family-test-123".toCharArray()
        val bytes=BackupCrypto.encrypt(StateCodec.encode(source),password)
        assertFalse(String(bytes,Charsets.ISO_8859_1).contains("example.invalid"))
        assertEquals(source,StateCodec.decode(BackupCrypto.decrypt(bytes,password)))
        try{BackupCrypto.decrypt(bytes,"wrong-password".toCharArray());fail()}catch(_:UserError){}
        val changed=bytes.clone();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte()
        try{BackupCrypto.decrypt(changed,password);fail()}catch(_:UserError){}
        val header=bytes.clone();header[15]=0
        try{BackupCrypto.decrypt(header,password);fail()}catch(_:UserError){}
    }
    @Test fun duplicatesRemainDistinct(){val c=M3u.parse(raw+raw.substringAfter('\n'),id);assertEquals(c.size,c.map{it.id}.distinct().size)}
    @Test fun passwordFreeBackupPortableAndComplete(){
        val original=sample();val bytes=BackupFile.encode(original)
        assertFalse(BackupFile.needsPassword(bytes))
        assertEquals(original,BackupFile.decode(bytes))
        assertTrue(bytes.size<StateCodec.encode(original).size)
        val old=BackupCrypto.encrypt(StateCodec.encode(original),"legacy-test".toCharArray())
        assertTrue(BackupFile.needsPassword(old))
    }
    @Test fun passwordFreeBackupRejectsDamagedOrFutureContainers(){
        val bytes=BackupFile.encode(sample())
        val changed=bytes.clone();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte()
        val future=bytes.clone();future[11]=4
        val length=bytes.clone();length[15]=0
        for(b in listOf(changed,future,length,bytes.copyOf(bytes.size-1),ByteArray(10),bytes+byteArrayOf(1))){
            try{BackupFile.decode(b);fail("Damaged backup must be rejected")}catch(_:UserError){}
        }
    }
}
