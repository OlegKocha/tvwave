package tv.tvwave.app

import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Verified iLook HLS contract. Tokens remain in the user's original URL, never in logs. */
object IlookArchive {
    private val NO_RECORDING get() = tr("this_recording_is_unavailable_the_provider_did_not_return_any_video_tr")
    private val INVALID_RECORDING get() = tr("could_not_open_this_recording_try_again_later")
    private val livePath=Regex("^/iptv/[^/]+/[0-9]+/index\\.m3u8\$")
    fun supports(channel:Channel):Boolean {
        val u=channel.url.toHttpUrlOrNull()?:return false
        return channel.archiveDays>0 && (u.host=="lunexas.top"||u.host.endsWith(".lunexas.top")) && livePath.matches(u.encodedPath)
    }
    fun expired(channel:Channel,programme:Programme,now:Long)=programme.start<now-channel.archiveDays*86_400_000L
    fun url(channel:Channel,startMs:Long,now:Long):String {
        require(supports(channel))
        return channel.url.toHttpUrlOrNull()!!.newBuilder()
            .setQueryParameter("utc",(startMs/1000).toString())
            .setQueryParameter("lutc",(now/1000).toString()).build().toString()
    }
    fun segmentTime(url:String):Long? = runCatching {
        val path=URI(url).path
        if(!path.contains("/arch/"))return@runCatching null
        path.substringAfterLast('/').removeSuffix(".ts").toLongOrNull()?.takeIf{it in 946684800000L..4102444800000L}
    }.getOrNull()
    fun responseError(code:Int):String=when(code){
        401,403->tr("the_source_denied_access_to_the_recording_check_your_subscription")
        404,410->NO_RECORDING
        else->tr("could_not_load_the_recording_check_your_connection_and_try_again")
    }
    /** Check the manifest AND the first media segment before replacing current playback. */
    fun open(client:OkHttpClient,channel:Channel,programme:Programme,position:Long,now:Long):ArchiveSession {
        if(!supports(channel))throw UserError(tr("catch_up_is_unavailable_for_this_channel"))
        if(expired(channel,programme,now))throw UserError(tr("recording_unavailable_this_channel_keeps_recordings_for_days", channel.archiveDays))
        val start=programme.start+position
        // iLook rounds the request up to the next ~10-second segment. Include
        // preceding segments, then seek inside the window, so loading latency cannot
        // discard the opening seconds. Refresh lutc after the availability probe.
        val requestStart=(start-30_000L).coerceAtLeast(now-channel.archiveDays*86_400_000L)
        val address=url(channel,requestStart,now)
        val segment=client.newCall(Request.Builder().url(address).header("User-Agent","TVWave/${BuildConfig.VERSION_NAME}").build()).execute().use { response ->
            if(!response.isSuccessful)throw UserError(responseError(response.code))
            val body=response.body?:throw UserError(NO_RECORDING)
            val text=SourceIO.text(SourceIO.read(body.byteStream(),256*1024))
            if(!text.startsWith("#EXTM3U"))throw UserError(INVALID_RECORDING)
            val first=text.lineSequence().map{it.trim()}.firstOrNull{it.isNotEmpty()&&!it.startsWith("#")}
                ?:throw UserError(NO_RECORDING)
            response.request.url.resolve(first)?.toString()?:throw UserError(INVALID_RECORDING)
        }
        val timestamp=segmentTime(segment)?:throw UserError(INVALID_RECORDING)
        // A live fallback or a different recording must not be presented as the selected programme.
        if(timestamp<requestStart-10_000L||timestamp>start+10_000L)throw UserError(INVALID_RECORDING)
        client.newCall(Request.Builder().url(segment).header("User-Agent","TVWave/${BuildConfig.VERSION_NAME}").build()).execute().use { response ->
            if(!response.isSuccessful)throw UserError(responseError(response.code))
            if(response.body?.byteStream()?.read()!=0x47)throw UserError(INVALID_RECORDING)
        }
        return ArchiveSession(channel,programme,url(channel,requestStart,System.currentTimeMillis()),remote=true,firstSegmentStartMs=timestamp)
    }
}
