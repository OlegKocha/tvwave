package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Test

class EpgSelectionTest {
    private val channel=Channel("local","One HD","TV","https://example.invalid/live")
    private val playlist=Playlist("p","Test","","",listOf(channel))
    private val a=EpgSource("a","A","https://example.invalid/a")
    private val b=EpgSource("b","B","https://example.invalid/b")
    private val catalog=listOf(EpgChannel("one",listOf("One")),EpgChannel("manual",listOf("Other")),EpgChannel("unused",listOf("Unused")))
    private val state=AppState(playlists=listOf(playlist),epgSources=listOf(a,b))
    @Test fun allPlaylistsAndFallbackSourcesRetainTheirMatches(){
        val second=playlist.copy(id="q",channels=listOf(channel.copy(id="second",name="Unused")))
        for(source in listOf(a,b))assertEquals(setOf("one","unused"),EpgLinks.needed(state.copy(playlists=listOf(playlist,second)),source.id,catalog))
        assertEquals("b",EpgLinks.resolve(state.copy(epgSources=listOf(b,a)),mapOf("a" to catalog,"b" to catalog))[channel.id]?.source)
    }
    @Test fun sourceRestrictionsAndManualOverridesAreBothRespected(){
        val selected=state.copy(playlistEpgSources=mapOf("p" to "a"),epgBindings=mapOf("local" to EpgBinding("b","manual"),"deleted" to EpgBinding("b","unused")))
        assertEquals(setOf("one"),EpgLinks.needed(selected,"a",catalog))
        assertEquals(setOf("manual"),EpgLinks.needed(selected,"b",catalog))
        assertTrue(EpgLinks.needed(selected.copy(playlists=emptyList()),"b",catalog).isEmpty())
    }
    @Test fun filteredGzipKeepsFullCatalogueAndSkipsUnneededDescriptions(){
        val now=Epg.time("20261103120000 +0000")!!
        val xml="<tv>"+catalog.joinToString(""){"<channel id=\"${it.id}\"><display-name>${it.names[0]}</display-name></channel>"}+catalog.joinToString(""){"<programme channel=\"${it.id}\" start=\"20261103110000 +0000\" stop=\"20261103130000 +0000\"><title>${it.id}</title><desc>Description</desc></programme>"}+"</tv>"
        val packed=java.io.ByteArrayOutputStream().apply{java.util.zip.GZIPOutputStream(this).use{it.write(xml.toByteArray())}}.toByteArray()
        for(wanted in listOf(setOf("one","manual"),emptySet())){
            val rows=mutableListOf<Programme>();var seen=listOf<EpgChannel>()
            Epg.parse(packed.inputStream(),listOf(channel),now,{rows.add(it)},onCatalog={seen=it},selectChannels={wanted})
            assertEquals(catalog,seen);assertEquals(wanted,rows.map{it.epgId}.toSet())
        }
        assertTrue(runCatching{Epg.parse(xml.replace("20261103","20251103").byteInputStream(),listOf(channel),now,{},selectChannels={setOf("one")})}.isFailure)
        assertTrue(runCatching{Epg.parse("<tv><channel id=\"x\"/></tv>".byteInputStream(),emptyList(),now,{},selectChannels={emptySet()})}.isFailure)
    }
}
