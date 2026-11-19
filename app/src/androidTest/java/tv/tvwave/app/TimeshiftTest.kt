package tv.tvwave.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class TimeshiftTest {
    @Test fun longFirstSegmentCanPlayBeforeTheSeekableWindowIsPublished() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val ctx=instrumentation.targetContext
        val root=File(ctx.cacheDir,"initial-segment-test")
        val server=ServerSocket(0)
        val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
        val releaseTail=java.util.concurrent.CountDownLatch(1)
        val prefixSent=java.util.concurrent.CountDownLatch(1)
        val bytes=ByteArray(512*1024){(it%251).toByte()}
        val playlist="#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:9.6,\nfirst.ts\n".toByteArray()
        val serving=scope.launch {
            while(isActive) {
                val socket=try {server.accept()} catch(_:java.io.IOException){break}
                launch {
                    socket.use {
                        val reader=it.getInputStream().bufferedReader()
                        val segment=reader.readLine().contains("/first.ts")
                        while(!reader.readLine().isNullOrEmpty()) {}
                        val body=if(segment)bytes else playlist
                        val output=it.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if(segment){
                            output.write(bytes,0,65536);output.flush();prefixSent.countDown()
                            releaseTail.await(12,java.util.concurrent.TimeUnit.SECONDS)
                            output.write(bytes,65536,bytes.size-65536)
                        }else output.write(body)
                    }
                }
            }
        }
        val ring=Timeshift(root,OkHttpClient(),scope)
        val factory=ring.factory(DefaultDataSource.Factory(ctx))
        val initial=factory.createDataSource()
        try {
            ring.start("http://127.0.0.1:${server.localPort}/live.m3u8",256,{}, {})
            assertTrue(prefixSent.await(4,java.util.concurrent.TimeUnit.SECONDS))
            val manifest=factory.createDataSource()
            val doc=runBlocking{withTimeout(3000){runInterruptible(Dispatchers.IO){
                try {manifest.open(DataSpec(ring.uri));val buf=ByteArray(2048);String(buf,0,manifest.read(buf,0,buf.size))} finally{manifest.close()}
            }}}
            assertTrue(doc.contains("0.media"))
            assertFalse("A partial file must not be seekable history",ring.active)
            val segmentUri=ring.uri.buildUpon().path("/0.media").build()
            val offset=32768
            assertEquals((bytes.size-offset).toLong(),initial.open(DataSpec.Builder().setUri(segmentUri).setPosition(offset.toLong()).build()))
            val buffer=ByteArray(4096)
            val count=initial.read(buffer,0,buffer.size)
            assertTrue(count>0);assertArrayEquals(bytes.copyOfRange(offset,offset+count),buffer.copyOf(count))
            releaseTail.countDown()
            await("Complete segment becomes available for seeking",4000){ring.active}
            var pos=offset+count
            while(pos<bytes.size){
                val n=initial.read(buffer,0,buffer.size);assertTrue(n>0)
                assertArrayEquals(bytes.copyOfRange(pos,pos+n),buffer.copyOf(n));pos+=n
            }
            assertEquals(-1,initial.read(buffer,0,1))
        }finally{
            releaseTail.countDown();initial.close();ring.stop();server.close();serving.cancel();scope.cancel();root.deleteRecursively()
        }
    }

    @Test fun replacingARecorderKeepsTheOtherActiveRecording() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(ctx.cacheDir,"handoff-ring-test")
        val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
        val first=Timeshift(root,OkHttpClient(),scope)
        val url="http://10.0.2.2:8778/live.m3u8"
        try {
            first.start(url,256,{}, {})
            await("First recorder ready"){first.active}
            val second=Timeshift(root,OkHttpClient(),scope)
            try {
                second.start(url,256,{}, {})
                await("Replacement recorder ready"){second.active}
                // An overlapping activity can still read its file until it stops.
                for(ring in listOf(first,second)){
                    val source=ring.factory(DefaultDataSource.Factory(ctx)).createDataSource()
                    try {assertTrue(source.open(DataSpec(ring.uri.buildUpon().path("/0.media").build()))>0)}
                    finally {source.close()}
                }
                first.stop()
                val source=second.factory(DefaultDataSource.Factory(ctx)).createDataSource()
                try {
                    assertTrue(source.open(DataSpec(second.uri.buildUpon().path("/0.media").build()))>0)
                    assertEquals(1,source.read(ByteArray(1),0,1))
                }finally{source.close()}
                repeat(8){first.start(url,256,{}, {});first.stop()}
            }finally{second.stop()}
        }finally{first.stop();scope.cancel()}
    }

    @Test fun localPlaylistWaitsForDelayedCaptureWithoutFatalStuckError(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val begin=SystemClock.elapsedRealtime()
        val uri=android.net.Uri.parse("tvwave-timeshift://1/index.m3u8")
        val source=androidx.media3.datasource.DataSource.Factory {
            object:androidx.media3.datasource.BaseDataSource(false){
                lateinit var current:androidx.media3.datasource.ByteArrayDataSource
                override fun open(spec:DataSpec):Long {
                    val count=if(SystemClock.elapsedRealtime()-begin>=6000)2 else 1
                    current=androidx.media3.datasource.ByteArrayDataSource(("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n"+
                        (0 until count).joinToString(""){"#EXTINF:1.0,\n$it.ts\n"}).toByteArray())
                    return current.open(spec)
                }
                override fun read(bytes:ByteArray,offset:Int,length:Int)=current.read(bytes,offset,length)
                override fun getUri()=uri
                override fun close(){if(::current.isInitialized)current.close()}
            }
        }
        lateinit var tracker:androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
        val received=java.util.concurrent.atomic.AtomicInteger()
        instrumentation.runOnMainSync {
            tracker=PlaybackSources.localPlaylistTrackerFactory.createTracker(
                androidx.media3.exoplayer.hls.DefaultHlsDataSourceFactory(source),
                androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(),
                androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory(),null,null)
            tracker.start(uri,androidx.media3.exoplayer.source.MediaSourceEventListener.EventDispatcher(),
                { received.set(it.segments.size) },
                androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(instrumentation.targetContext).build())
        }
        try {
            await("Initial local playlist"){received.get()==1}
            // Six target durations exceed the default 3.5-duration stuck deadline.
            await("Delayed segment published without a fatal playlist error",10000){
                instrumentation.runOnMainSync{tracker.maybeThrowPrimaryPlaylistRefreshError()}
                received.get()==2
            }
        }finally{instrumentation.runOnMainSync{tracker.stop()}}
    }
    private fun await(label:String, timeout:Long=15000, check:()->Boolean){
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){if(check())return;SystemClock.sleep(100)}
        fail(label)
    }
    @Test fun diskLimitEvictionCleanupAndStaleUris(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(ctx.cacheDir,"timeshift-test").apply{mkdirs();resolve("stale").writeText("old")}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val server=ServerSocket(0);val begin=SystemClock.elapsedRealtime()
        val serving=scope.launch(Dispatchers.IO){while(isActive){
            val socket=try{server.accept()}catch(_:Exception){break}
            socket.use{
                val reader=it.getInputStream().bufferedReader();val path=reader.readLine().split(" ")[1]
                while(!reader.readLine().isNullOrEmpty()){}
                val seq=(SystemClock.elapsedRealtime()-begin)/1000
                val body=if(path.endsWith("m3u8")) ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$seq\n"+(seq..seq+4).joinToString(""){n->"#EXTINF:1.0,\n$n.ts\n"}).toByteArray() else ByteArray(8192){7}
                it.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
            }
        }}
        val ring=Timeshift(root,OkHttpClient(),scope);val ready=AtomicBoolean();val failed=AtomicBoolean()
        try{
            assertFalse(root.resolve("stale").exists())
            ring.start("http://127.0.0.1:${server.localPort}/live.m3u8",1,{ready.set(true)},{failed.set(true)})
            await("Initial capture"){ready.get()};val first=ring.window.start;val old=ring.uri
            ring.limitBytes=32768
            await("Old segments evicted",12000){ring.window.start>first&&ring.window.bytes<=32768}
            assertFalse(failed.get())
            assertTrue(root.walkTopDown().filter{it.isFile}.sumOf{it.length()}<=32768)
            val source=ring.factory(DefaultDataSource.Factory(ctx)).createDataSource()
            source.open(DataSpec(ring.uri));val bytes=ByteArray(16384);val n=source.read(bytes,0,bytes.size);source.close()
            assertTrue(String(bytes,0,n).contains("#EXT-X-MEDIA-SEQUENCE:"))
            ring.stop();assertFalse(ring.active);assertEquals(0,root.walkTopDown().count{it.isFile})
            try{source.open(DataSpec(old));fail("Stale URI must fail")}catch(_:java.io.IOException){}finally{source.close()}
        }finally{ring.stop();scope.cancel();server.close();serving.cancel();root.deleteRecursively()}
    }
    @Test fun slowSegmentsAreCapturedConcurrentlyAndPublishedInOrder(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(ctx.cacheDir,"timeshift-slow-test")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val server=ServerSocket(0);val begin=SystemClock.elapsedRealtime()
        val requests=java.util.concurrent.atomic.AtomicInteger()
        val peak=java.util.concurrent.atomic.AtomicInteger()
        val serving=scope.launch(Dispatchers.IO){while(isActive){
            val socket=try{server.accept()}catch(_:Exception){break}
            launch(Dispatchers.IO){socket.use{
                try {
                    val reader=it.getInputStream().bufferedReader();val path=reader.readLine().split(" ")[1]
                    while(!reader.readLine().isNullOrEmpty()){}
                    val seq=(SystemClock.elapsedRealtime()-begin)/1000
                    val isMedia=path.endsWith(".ts")
                    val body=if(!isMedia)("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$seq\n"+(seq..seq+3).joinToString(""){n->"#EXTINF:1.0,\n$n.ts\n"}).toByteArray()else ByteArray(8192){7}
                    if(isMedia){val count=requests.incrementAndGet();peak.updateAndGet{old->maxOf(old,count)}}
                    try {
                        // One segment takes longer than its playback duration. Unequal delays
                        // also prove that out-of-order completions do not reorder the recording.
                        if(isMedia)delay(when(path.removePrefix("/").removeSuffix(".ts").toLong()){3L->8500L;else->4500L})
                        it.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
                    }finally{if(isMedia)requests.decrementAndGet()}
                }catch(_:java.io.IOException){}
            }}
        }}
        val ring=Timeshift(root,OkHttpClient(),scope);val failed=AtomicBoolean()
        try {
            ring.start("http://127.0.0.1:${server.localPort}/live.m3u8",1,{}, {failed.set(true)})
            await("Slow stream must accumulate a continuous history",25000){ring.window.end-ring.window.start>=15000||failed.get()}
            assertFalse("Recorder must not fall behind the source window",failed.get())
            assertTrue(peak.get() in 2..6)
            val source=ring.factory(DefaultDataSource.Factory(ctx)).createDataSource()
            source.open(DataSpec(ring.uri));val bytes=ByteArray(65536);val n=source.read(bytes,0,bytes.size);source.close()
            val indices=Regex("(?m)^(\\d+)\\.media$").findAll(String(bytes,0,n)).map{it.groupValues[1].toLong()}.toList()
            assertTrue(indices.size>=15)
            assertTrue(indices.zipWithNext().all{(a,b)->b==a+1})
            ring.stop();SystemClock.sleep(500)
            assertEquals(0,root.walkTopDown().count{it.isFile})
        }finally{ring.stop();scope.cancel();server.close();serving.cancel();root.deleteRecursively()}
    }
    @Test fun playbackPauseSeekContinueAcrossScreensAndClearOnChange(){
        TvFixture.seed(rich=true,media="http://10.0.2.2:8778/live.m3u8")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        fun call(name:String,vararg args:Any?){scenario.onActivity{a->MainActivity::class.java.declaredMethods.first{it.name==name&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}}
        fun <T> field(name:String):T {var out:T?=null;scenario.onActivity{a->@Suppress("UNCHECKED_CAST") out=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a) as T};@Suppress("UNCHECKED_CAST")return out as T}
        fun <T> prop(name:String):T {var out:T?=null;scenario.onActivity{a->@Suppress("UNCHECKED_CAST") out=MainActivity::class.java.declaredMethods.first{it.name=="get"+name.replaceFirstChar{it.uppercase()}}.apply{isAccessible=true}.invoke(a) as T};@Suppress("UNCHECKED_CAST")return out as T}
        fun position():Long{var out=0L;scenario.onActivity{out=(MainActivity::class.java.getDeclaredField("player").apply{isAccessible=true}.get(it) as ExoPlayer).currentPosition};return out}
        fun isPlaying():Boolean{var out=false;scenario.onActivity{out=(MainActivity::class.java.getDeclaredField("player").apply{isAccessible=true}.get(it) as ExoPlayer).isPlaying};return out}
        try{
            await("Loaded channels"){prop<AppState>("data").channels.isNotEmpty()}
            val channel=prop<AppState>("data").channels.first();call("tune",channel,true)
            await("Local playback",25000){isPlaying()&&field<Timeshift>("timeshift").active}
            val ring=field<Timeshift>("timeshift");val firstEnd=ring.window.end
            call("pausePlayback");val paused=position()
            await("Capture continues while paused",12000){ring.window.end>=firstEnd+6000}
            assertTrue(kotlin.math.abs(position()-paused)<500)
            call("resumePlayback",true);await("Resume"){isPlaying()}
            call("seekLocal",ring.window.start+1000);await("Seek local"){isPlaying()}
            assertTrue(prop<Boolean>("timeshiftDelayed"))
            call("returnToLive",channel);await("Return live"){isPlaying()};assertFalse(prop<Boolean>("timeshiftDelayed"))
            val before=ring.window.end;call("showReminders")
            await("Capture and playback continue in reminders",10000){ring.window.end>before&&isPlaying()}
            call("tune",channel,true);device.pressDPadCenter();SystemClock.sleep(250)
            device.takeScreenshot(File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"timeshift-hud.png"))
            val old=ring.uri;call("tune",prop<AppState>("data").channels[1],true)
            await("New channel new buffer"){ring.uri!=old&&ring.active&&isPlaying()}
            assertTrue(ring.window.end-ring.window.start<20000)
            call("openPanel","timeshift");SystemClock.sleep(600)
            await("Initial size label"){device.hasObject(By.text("1 ГБ"))}
            device.pressDPadDown();device.pressDPadRight()
            await("Slider remote increment"){prop<AppState>("data").timeshiftMb==1280}
            SystemClock.sleep(800)
            device.pressDPadLeft()
            await("Slider remote decrement"){prop<AppState>("data").timeshiftMb==1024}
            SystemClock.sleep(500)
            device.takeScreenshot(File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"timeshift-settings.png"))
            device.pressDPadUp();SystemClock.sleep(300)
            assertEquals("timeshift-enabled", field<MutableMap<String,String>>("panelFocus")["timeshift"])
            device.pressDPadCenter()
            await("Disable capture"){!prop<AppState>("data").timeshiftEnabled&&!ring.active}
            device.pressDPadCenter()
            await("Enable capture",25000){prop<AppState>("data").timeshiftEnabled&&ring.active&&isPlaying()}
        }finally{scenario.close()}
    }
}
