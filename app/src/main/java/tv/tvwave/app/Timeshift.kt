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
import java.util.concurrent.CountDownLatch
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
    @Volatile private var firstSegment=CountDownLatch(0)
    // Only the initial clear segment may be read while it is downloading. The seekable
    // window and its byte accounting continue to contain complete files exclusively.
    private var initialTransfer:InitialTransfer?=null
    private class InitialTransfer(val segment:Segment, val temp:File, val size:Long) {
        private val progress=java.util.concurrent.locks.ReentrantLock()
        private val changed=progress.newCondition()
        private var written=0L
        private var aborted=false

        fun advance(bytes:Long) {
            progress.lock()
            try { written=bytes;changed.signalAll() } finally { progress.unlock() }
        }
        fun abort() {
            progress.lock()
            try { aborted=true;changed.signalAll() } finally { progress.unlock() }
        }
        fun open(position:Long):InputStream {
            val file=try { FileInputStream(temp) } catch(_:FileNotFoundException) { FileInputStream(segment.files.single()) }
            file.channel.position(position)
            return object:InputStream(){
                var offset=position
                override fun read():Int { val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt() and 255 }
                override fun read(b:ByteArray,off:Int,len:Int):Int {
                    if(len==0)return 0
                    if(offset>=size)return -1
                    progress.lock()
                    val available=try {
                        while(!aborted && written<=offset)changed.await()
                        if(aborted)throw IOException("Initial segment cancelled")
                        written-offset
                    } catch(e:InterruptedException) {
                        Thread.currentThread().interrupt();throw InterruptedIOException("Initial segment interrupted")
                    } finally { progress.unlock() }
                    val n=file.read(b,off,minOf(len.toLong(),available).toInt())
                    if(n<0)throw EOFException("Incomplete initial segment")
                    offset+=n;return n
                }
                override fun close()=file.close()
            }
        }
    }
    private var cachedManifest:ByteArray?=null
    private var used=0L
    private var targetDuration=1
    @Volatile var limitBytes=1024L*1024*1024
    @Volatile var window=Window();private set
    val active get()=window.ready
    val uri get()=Uri.parse("tvwave-timeshift://$generation/index.m3u8")

    private companion object {
        // Activity recreation can briefly leave two recorders in the same cache root.
        private val activeFolders=mutableSetOf<File>()
        private fun deleteFiles(dir:File) {
            // Segment, key and staging files are direct children; a cancelled download
            // may already have unlinked its staging file.
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }
    init {
        synchronized(activeFolders) {
            root.mkdirs()
            root.listFiles()?.filterNot { it in activeFolders }?.forEach(::deleteFiles)
        }
    }
    fun stop() {
        generation++;firstSegment.countDown();job?.cancel();job=null;calls.forEach{it.cancel()};calls.clear()
        synchronized(lock){initialTransfer?.abort();initialTransfer=null;segments.clear();cachedManifest=null;used=0;pendingBytes=0;targetDuration=1;window=Window();folder?.let{dir->synchronized(activeFolders){deleteFiles(dir)}};folder=null}
    }
    fun start(url:String,limitMb:Int,onReady:(Uri)->Unit,onUnavailable:()->Unit) {
        stop()
        val gate=CountDownLatch(1)
        firstSegment=gate
        limitBytes=limitMb.toLong()*1024*1024
        val token=generation
        val dir=synchronized(activeFolders) {
            File(root,UUID.randomUUID().toString()).also { it.mkdirs();activeFolders.add(it) }
        }
        synchronized(lock){folder=dir}
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
                                if(segment.index==0L)initialTransfer=null
                                targetDuration=maxOf(targetDuration,ceil(segment.duration/1000.0).toInt());publish()
                            }
                            lastProgress=System.nanoTime()
                            if(!ready&&active){ready=true;gate.countDown();withContext(Dispatchers.Main){if(token==generation)onReady(uri)}}
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
                        val last=media.segments.lastIndex
                        // Start one segment behind the newest on short feeds and on clear TS
                        // feeds that support progressive reads. The next segment can then be
                        // recorded while the decoder plays the first one, even on a slow link.
                        val previous=media.segments.getOrNull(last-1)
                        val maxHeadroom=if(previous?.initializationSegment==null && previous?.fullSegmentEncryptionKeyUri==null &&
                            previous?.byteRangeLength==C.LENGTH_UNSET.toLong())10_000_000L else 4_000_000L
                        val initial=when {
                            scheduled!=null -> 0
                            previous!=null && previous.durationUs<=maxHeadroom && !previous.hasGapTag && previous.drmInitData==null &&
                                previous.relativeDiscontinuitySequence==media.segments[last].relativeDiscontinuitySequence -> last-1
                            else -> last
                        }
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
                                            try{
                                                val initial=if(index==0L && suffix=="media" && duration>4000 &&
                                                    source.initializationSegment==null && source.fullSegmentEncryptionKeyUri==null &&
                                                    length==C.LENGTH_UNSET.toLong() && attempt==0)
                                                    Segment(index,start,duration,discontinuity,listOf(target),null,null,null) else null
                                                download(base,url,target,offset,length,token,initial,gate);break
                                            }
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
                        // Wake on the first completed segment instead of waiting for the next poll.
                        if(!ready && pending.isNotEmpty())withTimeoutOrNull(100){pending.first().join()} else delay(100)
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                // Do not log URLs or exception messages: provider credentials may be embedded.
                android.util.Log.w("TVWaveTimeshift","Capture unavailable: ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull{it.className.startsWith("tv.tvwave.app.Timeshift")}}")
                withContext(Dispatchers.Main){if(token==generation){stop();onUnavailable()}}
            }
        }
        job?.invokeOnCompletion {
            // Child transfers have finished. Remove any staging file created during
            // cancellation before allowing another recorder to sweep this directory.
            synchronized(activeFolders) { deleteFiles(dir);activeFolders.remove(dir) }
        }
    }
    private fun publish(){cachedManifest=null;window=if(segments.isEmpty())Window()else Window(segments.first.start,segments.last.start+segments.last.duration,used)}
    private fun evict(incoming:Long){
        val previousSize=segments.size
        while(segments.isNotEmpty()&&(used+incoming>limitBytes||root.usableSpace<64L*1024*1024+incoming)){
            val old=segments.removeFirst();used-=old.files.sumOf{it.length()};old.files.forEach{it.delete()}
        }
        if(previousSize!=segments.size)publish()
    }
    private suspend fun download(base:String,relative:String,target:File,offset:Long,length:Long,token:Long,initial:Segment?,gate:CountDownLatch){
        val address=java.net.URI(base).resolve(relative).toString()
        val request=Request.Builder().url(address).header("User-Agent","TVWave/${BuildConfig.VERSION_NAME}")
        if(length!=C.LENGTH_UNSET.toLong())request.header("Range","bytes=$offset-${offset+length-1}")
        val c=http.newCall(request.build());calls.add(c)
        val temp=File(target.path+".part")
        var reserved=0L;var complete=false;var transfer:InitialTransfer?=null
        try { c.execute().use{response->
            require(response.isSuccessful)
            if(length!=C.LENGTH_UNSET.toLong())require(response.code==206)
            val body=response.body?:error("Empty segment")
            val expected=body.contentLength()
            if(initial!=null && expected in (256L*1024)..minOf(limitBytes/4,64L*1024*1024))
                transfer=InitialTransfer(initial,temp,expected)
            var total=0L;val started=System.nanoTime();var reading=0L;var storing=0L
            body.byteStream().use{input->temp.outputStream().use{out->
                val bytes=ByteArray(64*1024)
                while(true){
                    currentCoroutineContext().ensureActive();check(token==generation)
                    val readStart=System.nanoTime();val n=input.read(bytes);reading+=System.nanoTime()-readStart;if(n<0)break
                    val storeStart=System.nanoTime()
                    total+=n;require(total<=minOf(limitBytes/4,64L*1024*1024))
                    synchronized(lock){check(token==generation);evict(pendingBytes+n);require(used+pendingBytes+n<=limitBytes);require(root.usableSpace>32L*1024*1024+n);pendingBytes+=n;reserved+=n}
                    out.write(bytes,0,n)
                    transfer?.let { t ->
                        t.advance(total)
                        if(total==n.toLong())synchronized(lock) {
                            check(token==generation);initialTransfer=t;cachedManifest=null;gate.countDown()
                        }
                    }
                    storing+=System.nanoTime()-storeStart
                }
            }}
            if(BuildConfig.DEBUG)android.util.Log.d("TVWaveTimeshift","Segment bytes=$total totalMs=${(System.nanoTime()-started)/1000000} readMs=${reading/1000000} storeMs=${storing/1000000}")
            require(total>0&&(length==C.LENGTH_UNSET.toLong()||total==length)&&(transfer==null||total==expected))
            check(token==generation);require(temp.renameTo(target));complete=true
        }}finally{temp.delete();calls.remove(c);if(!complete)synchronized(lock){
            transfer?.abort()
            if(transfer!=null && initialTransfer===transfer){initialTransfer=null;cachedManifest=null}
            if(token==generation)pendingBytes-=reserved
        }}
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
        cachedManifest?.let{return@synchronized it}
        val entries:Collection<Segment> = if(segments.isNotEmpty())segments else listOf(initialTransfer?.segment?:throw IOException("Timeshift empty"))
        val first=entries.first()
        val duration=maxOf(targetDuration,ceil(first.duration/1000.0).toInt())
        val date=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}
        buildString{
            append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:${duration}\n")
            append("#EXT-X-MEDIA-SEQUENCE:${first.index}\n#EXT-X-DISCONTINUITY-SEQUENCE:${first.discontinuity}\n")
            var discontinuity=first.discontinuity
            entries.forEach{s->
                if(discontinuity!=s.discontinuity){append("#EXT-X-DISCONTINUITY\n");discontinuity=s.discontinuity}
                append("#EXT-X-PROGRAM-DATE-TIME:${date.format(Date(s.start))}\n")
                if(s.key!=null)append("#EXT-X-KEY:METHOD=AES-128,URI=\"${s.key}\",IV=${s.iv}\n")else append("#EXT-X-KEY:METHOD=NONE\n")
                if(s.init!=null)append("#EXT-X-MAP:URI=\"${s.init}\"\n")
                append("#EXTINF:${s.duration/1000.0},\n${s.index}.media\n")
            }
        }.toByteArray().also{cachedManifest=it}
    }
    fun factory(upstream:DataSource.Factory)=DataSource.Factory{object:BaseDataSource(false){
        private var delegate:DataSource?=null;private var input:InputStream?=null;private var address:Uri?=null;private var remaining=0L;private var started=false
        override fun open(spec:DataSpec):Long {
            address=spec.uri
            if(spec.uri.scheme!="tvwave-timeshift")return upstream.createDataSource().also{delegate=it}.open(spec)
            transferInitializing(spec)
            // Media3 can prepare as soon as tuning starts. Only its manifest loader waits
            // for the recorder; stop() also wakes it when the channel changes or fails.
            val gate=firstSegment
            if(spec.uri.lastPathSegment=="index.m3u8" && spec.uri.host==generation.toString()){
                try { gate.await() } catch(e:InterruptedException){
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Timeshift load cancelled")
                }
            }
            synchronized(lock){
                if(spec.uri.host!=generation.toString())throw IOException("Timeshift expired")
                val name=spec.uri.lastPathSegment.orEmpty()
                val size:Long
                var offset=spec.position
                if(name=="index.m3u8"){val bytes=manifest();input=bytes.inputStream();size=bytes.size.toLong()}
                else{
                    if(!name.matches(Regex("[0-9]+\\.(media|init|key)")))throw IOException("Invalid segment")
                    val file=File(folder?:throw IOException("Timeshift closed"),name)
                    val transfer=initialTransfer?.takeIf{it.segment.files.single()==file}
                    if(transfer!=null){
                        size=transfer.size
                        if(offset>size)throw IOException("Invalid range")
                        input=transfer.open(offset);offset=0
                    } else { input=file.inputStream();size=file.length() }
                }
                if(spec.position>size)throw IOException("Invalid range")
                var skip=offset;while(skip>0){val n=input!!.skip(skip);if(n<=0)throw EOFException();skip-=n}
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
