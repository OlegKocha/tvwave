package tv.tvwave.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

class UserError(message: String) : Exception(message)
data class Channel(val id: String, val name: String, val group: String, val url: String, val epgId:String="", val epgName:String="", val archiveDays:Int=0)
data class Playlist(val id: String, val name: String, val source: String, val raw: String, val channels: List<Channel>)
data class EpgSource(val id:String,val name:String,val url:String)
data class EpgBinding(val source:String,val channel:String)
fun legacyEpgSource(url:String)=EpgSource(UUID.nameUUIDFromBytes(url.toByteArray()).toString(),URI(url).host?:tr("programme_guide"),url)
data class AppState(
    val playlists: List<Playlist> = emptyList(), val favourites: Set<String> = emptySet(),
    val history: List<String> = emptyList(), val last: String = "", val large: Boolean = false,
    val resume: Boolean = false, val audioLanguage: String = "ru", val subtitles: Boolean = false, val epgUrl:String="", val epgAutoUpdate:Boolean=true, val epgBackup:String="", val epgSources:List<EpgSource> = emptyList(), val epgBindings:Map<String,EpgBinding> = emptyMap(), val epgIntervalHours:Int=24, val fontPercent:Int=if(large)120 else 100,
    val playlistEpgSources:Map<String,String> = emptyMap(), val epgOffsets:Map<String,Int> = emptyMap(), val uiLanguage:String="", val reminderMinutes:Set<Int> = setOf(5)
) {
    val channels get() = playlists.flatMap { it.channels }
    val sources get() = epgSources.ifEmpty{if(epgUrl.isNotEmpty())listOf(legacyEpgSource(epgUrl))else emptyList()}
}
object SourceIO {
    const val MAX_PLAYLIST = 20 * 1024 * 1024
    const val MAX_STATE = 48 * 1024 * 1024
    fun read(input: InputStream, limit: Int): ByteArray = input.use {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw UserError(tr("operation_cancelled"))
            val n = it.read(buffer); if (n < 0) break
            if (output.size().toLong() + n > limit) throw UserError(tr("the_file_is_too_large"))
            output.write(buffer, 0, n)
        }
        output.toByteArray()
    }
    fun text(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: Exception) { throw UserError(tr("could_not_read_the_file_utf_8_encoding_is_required")) }
    fun url(value: String, base: String = ""): String {
        try {
            val trimmed = value.trim()
            require(trimmed.isNotBlank() && trimmed.length <= 8192 && trimmed.none { it.isWhitespace() || it.code < 32 })
            val uri = if (base.isEmpty()) URI(trimmed) else URI(base).resolve(trimmed)
            require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null)
            return uri.toASCIIString()
        } catch (_: Exception) { throw UserError(tr("check_the_url_use_a_full_http_or_https_address_without_spaces")) }
    }
}
object M3u {
    fun parse(text: String, playlistId: String = UUID.randomUUID().toString(), base: String = ""): List<Channel> {
        if (text.toByteArray().size > SourceIO.MAX_PLAYLIST) throw UserError(tr("the_playlist_exceeds_20_mb"))
        val lines = text.removePrefix("\uFEFF").lineSequence().iterator()
        if (!lines.hasNext() || !lines.next().trim().matches(Regex("#EXTM3U(?:\\s.*)?"))) throw UserError(tr("not_m3u_m3u8_the_extm3u_header_is_missing"))
        var name: String? = null; var group = BuiltinGroups.UNGROUPED; var tvgId = ""; var tvgName = ""; var archiveDays=0; var row = 1
        val channels = mutableListOf<Channel>(); val occurrences = mutableMapOf<String, Int>()
        val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")
        for (raw in lines) {
            row++; val line = raw.trim(); if (line.isEmpty()) continue
            if (line.length > 65536) throw UserError(tr("line_is_too_long", row))
            if (line.startsWith("#EXT-X-")) throw UserError(tr("this_is_a_single_channel_hls_stream_choose_an_iptv_m3u_m3u8_playlist"))
            when {
                line.startsWith("#EXTINF:") -> {
                    if (name != null) throw UserError(tr("the_previous_channel_url_is_missing_at_line", row))
                    var quoted = false
                    val comma = line.indices.firstOrNull { index ->
                        if (line[index] == '"') quoted = !quoted
                        line[index] == ',' && !quoted
                    } ?: throw UserError(tr("invalid_channel_description_at_line", row))
                    if (!Regex("^#EXTINF:-?\\d+(?:\\.\\d+)?(?:\\s|,)").containsMatchIn(line)) throw UserError(tr("invalid_extinf_at_line", row))
                    val attrs = attribute.findAll(line.substring(0, comma)).associate { it.groupValues[1] to it.groupValues[2] }
                    name = line.substring(comma + 1).trim().takeIf { it.isNotBlank() && it.length <= 512 }
                        ?: throw UserError(tr("invalid_channel_name_at_line", row))
                    group = attrs["group-title"]?.take(256)?.ifBlank { BuiltinGroups.UNGROUPED } ?: BuiltinGroups.UNGROUPED
                    tvgId = attrs["tvg-id"] ?: ""; tvgName = attrs["tvg-name"] ?: ""
                    archiveDays=(attrs["catchup-days"] ?: attrs["tvg-rec"])?.toIntOrNull()?.coerceIn(0,365)?:0
                }
                line.startsWith("#EXTGRP:") -> group = line.substring(8).trim().take(256).ifBlank { BuiltinGroups.UNGROUPED }
                line.startsWith("#") -> Unit
                else -> {
                    val title = name ?: throw UserError(tr("missing_channel_description_before_line", row))
                    val address = try { SourceIO.url(line, base) } catch (_: Exception) { throw UserError(tr("invalid_channel_url_at_line", row)) }
                    val identity = if (tvgId.isNotBlank()) "id:${tvgId}" else "${group}\u0000${title}"
                    val occurrence = (occurrences[identity] ?: 0); occurrences[identity] = occurrence + 1
                    val hash = MessageDigest.getInstance("SHA-256").digest("${identity}\u0000${occurrence}".toByteArray()).joinToString("") { "%02x".format(it) }
                    channels += Channel("${playlistId}:${hash}", title, group, address,tvgId,tvgName,archiveDays); name = null
                    if (channels.size > 50000) throw UserError(tr("the_playlist_has_more_than_50_000_channels"))
                }
            }
        }
        if (name != null || channels.isEmpty()) throw UserError(tr("the_playlist_is_empty_or_the_last_channel_has_no_url"))
        return channels
    }
}
object StateCodec {
    fun encode(s: AppState): ByteArray {
        val root = JSONObject().put("format", "tvwave-state").put("version", 1).put("createdAt", System.currentTimeMillis()).put("minimumReaderVersion",if(s.playlistEpgSources.isNotEmpty()||s.epgOffsets.isNotEmpty())4 else if(s.sources.isEmpty()&&s.epgBindings.isEmpty())1 else 3).put("appVersion",BuildConfig.VERSION_NAME).put("featureSet",JSONArray(listOf("m3u","settings","favourites","history","epg")))
        root.put("playlists", JSONArray(s.playlists.map { p -> JSONObject().put("id",p.id).put("name",p.name).put("source",p.source).put("raw",p.raw) }))
        root.put("favourites", JSONArray(s.favourites.toList())).put("history", JSONArray(s.history)).put("last",s.last)
        root.put("reminderMinutes",JSONArray(s.reminderMinutes.sorted()))
        root.put("fontPercent",s.fontPercent).put("uiLanguage",s.uiLanguage)
        root.put("large",s.large).put("resume",s.resume).put("audioLanguage",s.audioLanguage).put("subtitles",s.subtitles)
        root.put("epgSources",JSONArray(s.sources.map{JSONObject().put("id",it.id).put("name",it.name).put("url",it.url)}))
        root.put("epgAutoUpdate",s.epgAutoUpdate).put("epgIntervalHours",s.epgIntervalHours)
        root.put("epgBindings",JSONObject().apply{s.epgBindings.forEach{(id,b)->put(id,JSONObject().put("source",b.source).put("channel",b.channel))}})
        root.put("playlistEpgSources",JSONObject(s.playlistEpgSources)).put("epgOffsets",JSONObject(s.epgOffsets))
        return root.toString().toByteArray().also { if (it.size > SourceIO.MAX_STATE) throw UserError(tr("total_data_exceeds_48_mb")) }
    }
    fun decode(bytes: ByteArray): AppState {
        try {
            require(bytes.size <= SourceIO.MAX_STATE)
            val j = JSONObject(SourceIO.text(bytes)); require(j.getString("format") in setOf("tvwave-state",LegacyBackup.STATE_FORMAT) && j.getInt("version") == 1)
            require(j.getInt("minimumReaderVersion") <= 4 && j.getLong("createdAt") > 0)
            val array = j.getJSONArray("playlists"); require(array.length() <= 15)
            val ids = mutableSetOf<String>()
            val playlists = (0 until array.length()).map { i ->
                val p = array.getJSONObject(i); val id = p.getString("id")
                require(UUID.fromString(id).toString() == id && ids.add(id))
                val name = p.getString("name"); require(name.isNotBlank() && name.length <= 120)
                val source = p.getString("source"); if (source.isNotEmpty()) SourceIO.url(source)
                val raw = p.getString("raw")
                Playlist(id, name, source, raw, M3u.parse(raw, id, source))
            }
            val all = playlists.flatMap { it.channels }.map { it.id }.toSet(); require(all.size <= 50000)
            fun refs(key: String, max: Int): List<String> {
                val a = j.getJSONArray(key); require(a.length() <= max)
                return (0 until a.length()).map { a.getString(it).also { id -> require(id in all) } }.also { require(it.distinct().size == it.size) }
            }
            val last = j.getString("last"); require(last.isEmpty() || last in all)
            val uiLanguage=j.optString("uiLanguage",""); require(uiLanguage.isEmpty()||uiLanguage.matches(Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")))
            val language = j.getString("audioLanguage"); require(language in listOf("ru","en",""))
            for (key in listOf("large","resume","subtitles")) require(j.get(key) is Boolean)
            val oldUrl=j.optString("epgUrl","");if(oldUrl.isNotEmpty())SourceIO.url(oldUrl)
            val sourceArray=j.optJSONArray("epgSources")
            val sources=if(sourceArray!=null){
                require(sourceArray.length()<=10)
                (0 until sourceArray.length()).map{i->val e=sourceArray.getJSONObject(i)
                    val id=e.getString("id");require(UUID.fromString(id).toString()==id)
                    val name=e.getString("name");require(name.isNotBlank()&&name.length<=120)
                    EpgSource(id,name,SourceIO.url(e.getString("url")))
                }.also{require(it.map{e->e.id}.distinct().size==it.size);require(it.map{e->e.url}.distinct().size==it.size)}
            }else if(oldUrl.isNotEmpty())listOf(legacyEpgSource(oldUrl))else emptyList()
            val sourceIds=sources.map{it.id}.toSet();val bindings=mutableMapOf<String,EpgBinding>()
            j.optJSONObject("epgBindings")?.let{obj->require(obj.length()<=50000);obj.keys().forEach{id->
                require(id in all);val b=obj.getJSONObject(id);val source=b.getString("source");val channel=b.getString("channel")
                require(source in sourceIds&&channel.isNotBlank()&&channel.length<=256);bindings[id]=EpgBinding(source,channel)
            }}
            val playlistSources=mutableMapOf<String,String>()
            j.optJSONObject("playlistEpgSources")?.let{obj->require(obj.length()<=15);obj.keys().forEach{id->
                val source=obj.getString(id);require(id in ids&&source in sourceIds);playlistSources[id]=source
            }}
            val offsets=mutableMapOf<String,Int>()
            j.optJSONObject("epgOffsets")?.let{obj->require(obj.length()<=50000);obj.keys().forEach{id->
                require(id in all&&obj.get(id) is Number);val minutes=obj.getInt(id)
                require(obj.getDouble(id)==minutes.toDouble()&&minutes in -720..720&&minutes%30==0);offsets[id]=minutes
            }}
            val reminderMinutes=if(j.has("reminderMinutes")){
                val a=j.getJSONArray("reminderMinutes");require(a.length()<=reminderMinuteOptions.size)
                (0 until a.length()).map{index->require(a.get(index) is Number);a.getInt(index).also{require(a.getDouble(index)==it.toDouble()&&it in reminderMinuteOptions)}}.toSet()
            }else setOf(5)
            val interval=j.optInt("epgIntervalHours",24);require(interval in listOf(6,12,24))
            val fontPercent=if(j.has("fontPercent")){require(j.get("fontPercent") is Number);j.getInt("fontPercent").also{require(j.getDouble("fontPercent")==it.toDouble()&&it in 60..130&&it%5==0)}}else if(j.getBoolean("large"))120 else 100
            // Old backups may contain programme caches. Read their configuration only.
            return AppState(playlists, refs("favourites",50000).toSet(), refs("history",30), last, j.getBoolean("large"),j.getBoolean("resume"),language,j.getBoolean("subtitles"),epgAutoUpdate=j.optBoolean("epgAutoUpdate",true),epgSources=sources,epgBindings=bindings,epgIntervalHours=interval,fontPercent=fontPercent,playlistEpgSources=playlistSources,epgOffsets=offsets,uiLanguage=uiLanguage,reminderMinutes=reminderMinutes)
        } catch (e: UserError) { throw e } catch (_: Exception) { throw UserError(tr("the_backup_data_is_damaged_or_its_version_is_not_supported")) }
    }
}
