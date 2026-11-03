package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgTest {
    private val now=Epg.time("20261027200000 +0300")!!
    private val channel=Channel("local","Первый канал UHD","ТВ","https://example.invalid/live")
    private val xml="""<?xml version="1.0"?><!DOCTYPE tv SYSTEM "https://example.invalid/no-fetch.dtd"><tv><channel id="one"><display-name>Первый канал HD</display-name></channel><programme channel="one" start="20261027193000 +0300" stop="20261027210000 +0300"><title>Вечерняя программа</title><desc>Описание</desc></programme></tv>"""
    @Test fun offsetsAreAbsoluteAndBadDatesRejected(){
        assertEquals(Epg.time("20261027200000 +0300"),Epg.time("20261027170000 +0000"))
        assertNull(Epg.time("20260230200000 +0300"));assertNull(Epg.time("20261027200000 +9999"))
    }
    @Test fun channelIconsAreResolvedAndProgrammePicturesAreIgnored(){
        val iconsXml=xml.replace("</display-name>","</display-name><icon src=\"file:///private.png\"/><icon src=\"../logo.png\"/>")
            .replace("<title>","<icon src=\"https://example.invalid/poster.png\"/><title>")
        for(gzip in listOf(false,true)){
            val raw=if(gzip)ByteArrayOutputStream().apply{GZIPOutputStream(this).use{it.write(iconsXml.toByteArray())}}.toByteArray()else iconsXml.toByteArray()
            var catalog=listOf<EpgChannel>()
            Epg.parse(raw.inputStream(),listOf(channel),now,{},onCatalog={catalog=it},baseUrl="https://example.invalid/guide/epg.xml.gz")
            assertEquals("https://example.invalid/logo.png",catalog.single().icon)
        }
        var catalog=listOf<EpgChannel>()
        Epg.parse(xml.replace("<title>","<icon src=\"https://example.invalid/poster.png\"/><title>").byteInputStream(),listOf(channel),now,{},onCatalog={catalog=it})
        assertEquals("",catalog.single().icon)
        Epg.parse(xml.replace("</display-name>","</display-name><icon src=\"javascript:alert(1)\"/>").byteInputStream(),listOf(channel),now,{},onCatalog={catalog=it})
        assertEquals("",catalog.single().icon)
    }
    @Test fun matchQualityButNeverSilentlyDropTimeShift(){
        val guide=listOf(EpgChannel("one",listOf("Первый канал HD")))
        assertEquals(mapOf("local" to "one"),Epg.match(listOf(channel),guide))
        assertTrue(Epg.match(listOf(channel.copy(name="Первый канал +2")),guide).isEmpty())
        assertTrue(Epg.match(listOf(channel),guide+EpgChannel("ambiguous",listOf("Первый канал HD"))).isEmpty())
    }
    @Test fun parsePlainAndGzipWithoutFetchingDtd(){
        for(gzip in listOf(false,true)){
            val raw=if(gzip)ByteArrayOutputStream().apply{GZIPOutputStream(this).use{it.write(xml.toByteArray())}}.toByteArray()else xml.toByteArray()
            val programs=mutableListOf<Programme>();val result=Epg.parse(ByteArrayInputStream(raw),listOf(channel),now,{programs.add(it)})
            assertEquals("one",result.mapping["local"]);assertEquals(1,programs.size)
            assertTrue(programs.single().start<=now&&now<programs.single().end)
        }
    }
    @Test fun staleAndMalformedSourcesAreRejected(){
        for(text in listOf(xml.replace("20261027","20251027"),xml.dropLast(20),"<html>Not XMLTV</html>")){
            try{Epg.parse(ByteArrayInputStream(text.toByteArray()),listOf(channel),now,{});fail()}catch(_:UserError){}
        }
    }
    @Test fun epgArchiveRoundTripAndInvalidChannelRef(){
        val p=Programme("one",now-1000,now+1000,"Программа","Описание")
        val packed=EpgArchive.encode("https://example.invalid/epg",now,mapOf("local" to "one"),1){emit->emit(p)}
        val rows=mutableListOf<Programme>();EpgArchive.read(packed,"https://example.invalid/epg",setOf("local"),{t,m->assertEquals(now,t);assertEquals("one",m["local"])},{rows.add(it)})
        assertEquals(listOf(p),rows)
        try{EpgArchive.read(packed,"https://example.invalid/epg",emptySet());fail()}catch(_:UserError){}
    }
    @Test fun internalEntitiesAreRejected(){
        val attack=xml.replace("<!DOCTYPE tv SYSTEM \"https://example.invalid/no-fetch.dtd\">","<!DOCTYPE tv [<!ENTITY sample 'EXPANDED'>]>").replace("Вечерняя программа","&sample;")
        try{Epg.parse(ByteArrayInputStream(attack.toByteArray()),listOf(channel),now,{});fail()}catch(_:UserError){}
    }
}
