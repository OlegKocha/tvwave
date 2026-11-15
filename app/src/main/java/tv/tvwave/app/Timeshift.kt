package tv.tvwave.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** A disk ring of complete HLS segments. It never transcodes or changes the source quality. */
class Timeshift(private val root:File, private val http:OkHttpClient, private val scope:CoroutineScope) {
    data class Window(val start:Long=0,val end:Long=0,val bytes:Long=0) { val ready get()=end>start }
    private data class Segment(val index:Long,val start:Long,val duration:Long,val discontinuity:Int,val files:List<File>,val init:String?,val key:String?,val iv:String?)
    private val lock=Any()
    private val segments=ArrayDeque<Segment>()
    private var job:Job?=null
    private val calls=java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private var pendingBytes=0L
    @Volatile private var generation=0L
    private var folder:File?=null
    private var used=0L
    private var targetDuration=1
    @Volatile var limitBytes=1024L*1024*1024
    @Volatile var window=Window();private set
    val active get()=window.ready
    val uri get()=Uri.parse("tvwave-timeshift://$generation/index.m3u8")

    init { root.deleteRecursively();root.mkdirs() }
    fun stop() {
        generation++;job?.cancel();job=null;calls.forEach{it.cancel()};calls.clear()
        synchronized(lock){segments.clear();used=0;pendingBytes=0;targetDuration=1;window=Window();folder?.deleteRecursively();folder=null}
    }
    fun start(url:String,limitMb:Int,onReady:(Uri)->Unit,onUnavailable:()->Unit) {
        stop();limitBytes=limitMb.toLong()*1024*1024
        val token=generation
        val dir=File(root,token.toString()).apply{mkdirs()};synchronized(lock){folder=dir}
        job=scope.launch(Dispatchers.IO) {
            var ready=false;var ordinal=0L;var nextStart=0L
            try {
                var address=url
                var playlist=loadPlaylist(address,token)
                if(playlist is HlsMultivariantPlaylist){
                    // External audio/subtitle renditions must remain available. Until those are
                    // recorded together, retain ordinary playback rather than drop tracks.
                    require(playlist.audios.none{it.url!=null}&&playlist.subtitles.none{it.url!=null})
                    address=playlist.variants.maxByOrNull{it.format.bitrate}?.url?.toString()?:error("No variant")
                    playlist=loadPlaylist(address,token)
                }
                var media=playlist as? HlsMediaPlaylist?:error("Not HLS")
                require(!media.hasEndTag&&media.segments.isNotEmpty())
                coroutineScope {
                    // Keep discovery independent of slow segment transfers. Publish only in
                    // sequence, with a bounded number of requests and pending bytes on disk.
                    val pending=ArrayDeque<Deferred<Segment>>()
                    val transfers=Semaphore(6)
                    var scheduled:Long?=null
                    var lastPoll=0L
                    var lastProgress=System.nanoTime()
                    var failures=0
                    while(isActive&&token==generation){
                        while(pending.isNotEmpty()&&pending.first.isCompleted){
                            val segment=pending.removeFirst().await()
                            synchronized(lock){
                                check(token==generation)
                                val bytes=segment.files.sumOf{it.length()}
                                pendingBytes-=bytes
                                evict(bytes+pendingBytes)
                                require(used+bytes+pendingBytes<=limitBytes)
                                segments.addLast(segment);used+=bytes
                                targetDuration=maxOf(targetDuration,ceil(segment.duration/1000.0).toInt());publish()
                            }
                            lastProgress=System.nanoTime()
                            if(!ready&&active){ready=true;withContext(Dispatchers.Main){if(token==generation)onReady(uri)}}
                        }
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(lastPoll==0L||now-lastPoll>= (media.targetDurationUs/2000).coerceIn(500,3000)){
                            try {
                                if(lastPoll!=0L)media=loadPlaylist(address,token) as? HlsMediaPlaylist?:error("Not HLS")
                                require(!media.hasEndTag&&media.protectionSchemes==null)
                                failures=0;lastPoll=now
                            }catch(e:CancellationException){throw e}
                            catch(e:Exception){if(++failures>=3)throw e;lastPoll=now;delay(500);continue}
                        }
                        val first=media.mediaSequence
                        require(scheduled==null||first<=scheduled!!+1){"HLS window gap"}
                        val initial=if(scheduled==null)(media.segments.size-1).coerceAtLeast(0)else 0
                        for(i in initial until media.segments.size){
                            // Remember discovered URLs even when an older transfer is slow.
                            // Cap backlog at two minutes (and 120 descriptors for short segments).
                            val sequence=first+i
                            if(scheduled!=null&&sequence<=scheduled!!)continue
                            require(pending.size<120 && (!ready || nextStart-window.end<120000)){"Capture backlog"}
                            val source=media.segments[i]
                            require(!source.hasGapTag&&source.drmInitData==null)
                            if(nextStart==0L)nextStart=if(media.hasProgramDateTime)(media.startTimeUs+source.relativeStartTimeUs)/1000 else System.currentTimeMillis()-(media.durationUs-source.relativeStartTimeUs)/1000
                            val index=ordinal++;val start=nextStart;val duration=(source.durationUs/1000).coerceAtLeast(1)
                            val base=media.baseUri;val discontinuity=media.discontinuitySequence+source.relativeDiscontinuitySequence
                            nextStart+=duration;scheduled=sequence
                            pending.addLast(async(Dispatchers.IO){transfers.withPermit {
                                val files=mutableListOf<File>()
                                try {
                                    suspend fun save(suffix:String,url:String,offset:Long=0,length:Long=C.LENGTH_UNSET.toLong()):String {
                                        val name="$index.$suffix";val target=File(dir,name)
                                        var attempt=0
                                        while(true){
                                            try{download(base,url,target,offset,length,token);break}
                                            catch(e:CancellationException){throw e}
                                            catch(e:Exception){ensureActive();check(token==generation);if(++attempt>=3)throw e;delay(attempt*500L)}
                                        }
                                        files+=target;return name
                                    }
                                    val init=source.initializationSegment?.let{require(it.fullSegmentEncryptionKeyUri==null);save("init",it.url,it.byteRangeOffset,it.byteRangeLength)}
                                    val key=source.fullSegmentEncryptionKeyUri?.let{save("key",it).also{require(files.last().length()==16L)}}
                                    save("media",source.url,source.byteRangeOffset,source.byteRangeLength)
                                    Segment(index,start,duration,discontinuity,files,init,key,source.encryptionIV?:"0x${sequence.toString(16)}")
                                }catch(e:Exception){
                                    synchronized(lock){if(token==generation)pendingBytes-=files.sumOf{it.length()};files.forEach{it.delete()}}
                                    throw e
                                }
                            }})
                        }
                        require(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-lastProgress)<90){"Capture stalled"}
                        delay(100)
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                // Do not log URLs or exception messages: provider credentials may be embedded.
                android.util.Log.w("TVWaveTimeshift","Capture unavailable: ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull{it.className.startsWith("tv.tvwave.app.Timeshift")}}")
                withContext(Dispatchers.Main){if(token==generation){stop();onUnavailable()}}
            }
            finally { if(token!=generation)dir.deleteRecursively() }
        }
    }
    private fun publish(){window=if(segments.isEmpty())Window()else Window(segments.first.start,segments.last.start+segments.last.duration,used)}
    private fun evict(incoming:Long){
        while(segments.isNotEmpty()&&(used+incoming>limitBytes||root.usableSpace<64L*1024*1024+incoming)){
            val old=segments.removeFirst();used-=old.files.sumOf{it.length()};old.files.forEach{it.delete()}
        }
        publish()
    }
    private suspend fun download(base:String,relative:String,target:File,offset:Long,length:Long,token:Long){
        val address=java.net.URI(base).resolve(relative).toString()
        val request=Request.Builder().url(address).header("User-Agent","TVWave/${BuildConfig.VERSION_NAME}")
        if(length!=C.LENGTH_UNSET.toLong())request.header("Range","bytes=$offset-${offset+length-1}")
        val c=http.newCall(request.build());calls.add(c)
        val temp=File(target.path+".part")
        var reserved=0L;var complete=false
        try { c.execute().use{response->
            require(response.isSuccessful)
            if(length!=C.LENGTH_UNSET.toLong())require(response.code==206)
            val body=response.body?:error("Empty segment")
            var total=0L;val started=System.nanoTime();var reading=0L;var storing=0L
            body.byteStream().use{input->temp.outputStream().use{out->
                val bytes=ByteArray(64*1024)
                while(true){
                    currentCoroutineContext().ensureActive();check(token==generation)
                    val readStart=System.nanoTime();val n=input.read(bytes);reading+=System.nanoTime()-readStart;if(n<0)break
                    val storeStart=System.nanoTime()
                    total+=n;require(total<=minOf(limitBytes/4,64L*1024*1024))
                    synchronized(lock){check(token==generation);evict(pendingBytes+n);require(used+pendingBytes+n<=limitBytes);require(root.usableSpace>32L*1024*1024+n);pendingBytes+=n;reserved+=n}
                    out.write(bytes,0,n);storing+=System.nanoTime()-storeStart
                }
            }}
            if(BuildConfig.DEBUG)android.util.Log.d("TVWaveTimeshift","Segment bytes=$total totalMs=${(System.nanoTime()-started)/1000000} readMs=${reading/1000000} storeMs=${storing/1000000}")
            require(total>0&&(length==C.LENGTH_UNSET.toLong()||total==length))
            check(token==generation);require(temp.renameTo(target));complete=true
        }}finally{temp.delete();calls.remove(c);if(!complete)synchronized(lock){if(token==generation)pendingBytes-=reserved}}
    }
    private fun loadPlaylist(url:String,token:Long):androidx.media3.exoplayer.hls.playlist.HlsPlaylist {
        check(token==generation)
        val c=http.newCall(Request.Builder().url(url).header("User-Agent","TVWave/${BuildConfig.VERSION_NAME}").build());calls.add(c)
        try{return c.execute().use{r->
            require(r.isSuccessful)
            val body=r.body?:error("Empty playlist")
            val input=body.byteStream().buffered()
            input.mark(16)
            val header=ByteArray(7);DataInputStream(input).readFully(header);input.reset()
            require(String(header,Charsets.UTF_8)=="#EXTM3U")
            val bytes=SourceIO.read(input,2*1024*1024)
            HlsPlaylistParser().parse(Uri.parse(r.request.url.toString()),bytes.inputStream())
        }}finally{calls.remove(c)}
    }
    private fun manifest():ByteArray=synchronized(lock){
        if(segments.isEmpty())throw IOException("Timeshift empty")
        val date=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}
        buildString{
            append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:${targetDuration}\n")
            append("#EXT-X-MEDIA-SEQUENCE:${segments.first.index}\n#EXT-X-DISCONTINUITY-SEQUENCE:${segments.first.discontinuity}\n")
            var discontinuity=segments.first.discontinuity
            segments.forEach{s->
                if(discontinuity!=s.discontinuity){append("#EXT-X-DISCONTINUITY\n");discontinuity=s.discontinuity}
                append("#EXT-X-PROGRAM-DATE-TIME:${date.format(Date(s.start))}\n")
                if(s.key!=null)append("#EXT-X-KEY:METHOD=AES-128,URI=\"${s.key}\",IV=${s.iv}\n")else append("#EXT-X-KEY:METHOD=NONE\n")
                if(s.init!=null)append("#EXT-X-MAP:URI=\"${s.init}\"\n")
                append("#EXTINF:${s.duration/1000.0},\n${s.index}.media\n")
            }
        }.toByteArray()
    }
    fun factory(upstream:DataSource.Factory)=DataSource.Factory{object:BaseDataSource(false){
        private var delegate:DataSource?=null;private var input:InputStream?=null;private var address:Uri?=null;private var remaining=0L;private var started=false
        override fun open(spec:DataSpec):Long {
            address=spec.uri
            if(spec.uri.scheme!="tvwave-timeshift")return upstream.createDataSource().also{delegate=it}.open(spec)
            transferInitializing(spec)
            synchronized(lock){
                if(spec.uri.host!=generation.toString())throw IOException("Timeshift expired")
                val name=spec.uri.lastPathSegment.orEmpty()
                val size:Long
                if(name=="index.m3u8"){val bytes=manifest();input=bytes.inputStream();size=bytes.size.toLong()}
                else{
                    if(!name.matches(Regex("[0-9]+\\.(media|init|key)")))throw IOException("Invalid segment")
                    val file=File(folder?:throw IOException("Timeshift closed"),name)
                    input=file.inputStream();size=file.length()
                }
                if(spec.position>size)throw IOException("Invalid range")
                var skip=spec.position;while(skip>0){val n=input!!.skip(skip);if(n<=0)throw EOFException();skip-=n}
                remaining=if(spec.length==C.LENGTH_UNSET.toLong())size-spec.position else minOf(spec.length,size-spec.position)
            }
            transferStarted(spec);started=true;return remaining
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            delegate?.let{return it.read(buffer,offset,length)}
            if(length==0)return 0
            if(remaining==0L)return C.RESULT_END_OF_INPUT
            val n=input!!.read(buffer,offset,minOf(length.toLong(),remaining).toInt())
            if(n>0){remaining-=n;bytesTransferred(n)}
            return n
        }
        override fun getUri()=delegate?.uri?:address
        override fun close(){delegate?.close();delegate=null;input?.close();if(started)transferEnded();started=false;input=null;address=null}
    }}
}
