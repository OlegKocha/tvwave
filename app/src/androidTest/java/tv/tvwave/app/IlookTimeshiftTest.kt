package tv.tvwave.app

import android.os.SystemClock
import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in emulator integration check. Credentials are supplied outside the repository. */
class IlookTimeshiftTest {
    @Test fun realHlsPauseRewindAndLive() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName=="tv.tvwave.app.debug")
        check(android.os.Build.MODEL.contains("sdk")||android.os.Build.FINGERPRINT.contains("generic"))
        val fixture=File(ctx.getExternalFilesDir(null),"provider-test.m3u")
        assumeTrue("Private provider fixture required",fixture.exists())
        val direct=InstrumentationRegistry.getArguments().getString("direct")=="true"
        val raw=fixture.readText();val id=java.util.UUID.randomUUID().toString();val channels=M3u.parse(raw,id)
        Repository(ctx).use{it.save(AppState(uiLanguage="ru",playlists=listOf(Playlist(id,"iLook test","",raw,channels)),timeshiftEnabled=!direct),resetCache=true);check(it.load().channels.size==channels.size)}
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        fun call(name:String,vararg args:Any?):Any? {var out:Any?=null;scenario.onActivity{a->out=MainActivity::class.java.declaredMethods.first{it.name==name&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)};return out}
        fun <T> field(name:String):T {var out:T?=null;scenario.onActivity{a->@Suppress("UNCHECKED_CAST") out=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a) as T};@Suppress("UNCHECKED_CAST")return out as T}
        fun player(check:(ExoPlayer)->Boolean):Boolean {var result=false;scenario.onActivity{result=check(field("player"))};return result}
        fun await(label:String,timeout:Long=30000,check:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(check())return;SystemClock.sleep(200)};fail(label)}
        val report=File(ctx.getExternalFilesDir(null),"provider-test-results.txt");report.writeText("")
        val videoFailures=mutableListOf<String>()
        fun verifyFrames(label:String,baseline:Int) {
            val deadline=SystemClock.elapsedRealtime()+15000
            var advanced=false
            while(SystemClock.elapsedRealtime()<deadline){
                if(player{(it.videoDecoderCounters?.renderedOutputBufferCount?:0)>=baseline+15}){advanced=true;break}
                SystemClock.sleep(200)
            }
            player{report.appendText("$label: baseline=$baseline rendered=${it.videoDecoderCounters?.renderedOutputBufferCount} dropped=${it.videoDecoderCounters?.droppedBufferCount} state=${it.playbackState} playing=${it.isPlaying} advanced=$advanced\n");true}
            if(!advanced)videoFailures.add(label)
        }
        try {
            await("Loaded fixture"){(call("getData") as AppState).channels.size==channels.size}
            for((index,channel) in channels.withIndex()) {
                val only=InstrumentationRegistry.getArguments().getString("channel")
                if(only!=null&&channel.name.substringAfterLast(" ")!=only)continue
                call("tune",channel,true)
                if(direct){
                    await("Direct HLS playback",120000){player{it.isPlaying}}
                    repeat(3){ sample ->
                        var baseline=0
                        player{baseline=it.videoDecoderCounters?.renderedOutputBufferCount?:0;true}
                        SystemClock.sleep(10000)
                        player{report.appendText("${channel.name}: direct sample=$sample rendered=${(it.videoDecoderCounters?.renderedOutputBufferCount?:0)-baseline} state=${it.playbackState} playing=${it.isPlaying} buffer=${it.totalBufferedDuration} error=${it.playerError?.errorCode}\n");true}
                        verifyFrames("Direct video sample $sample",baseline)
                    }
                    continue
                }
                var lastLog=0L
                await("${channel.name}: HLS local playback",120000){
                    if(SystemClock.elapsedRealtime()-lastLog>5000){
                        lastLog=SystemClock.elapsedRealtime()
                        val w=field<Timeshift>("timeshift").window
                        player{report.appendText("loading: window=${w.end-w.start}ms bytes=${w.bytes} state=${it.playbackState} playWhenReady=${it.playWhenReady} error=${it.playerError?.errorCode}\n");true}
                    }
                    player{it.isPlaying&&it.currentMediaItem?.localConfiguration?.uri?.scheme=="tvwave-timeshift"}
                }
                var framesBefore=0
                player { framesBefore=it.videoDecoderCounters?.renderedOutputBufferCount?:0; true }
                verifyFrames("Video frames advance",framesBefore)
                val ring=field<Timeshift>("timeshift")
                report.appendText("${channel.name}: actual HLS local playback confirmed\n")
                val end=ring.window.end
                call("pausePlayback");val paused=call("localTime") as Long
                await("Capture while paused",90000){ring.window.end>end+5000}
                assertTrue(kotlin.math.abs((call("localTime") as Long)-paused)<500)
                assertTrue(player{!it.isPlaying})
                call("resumePlayback",true);await("Resume"){player{it.isPlaying}}
                report.appendText("pause and resume confirmed; capture continues while paused\n")
                await("Recorded history available",120000){ring.window.end-ring.window.start>=45000}
                call("pausePlayback")
                // Arrange a recorded position away from either edge, then exercise the real HUD.
                call("seekLocal",ring.window.start+15000L)
                call("setScreen","player")
                call("setControls",true)
                call("setHudFocus","player-pause")
                SystemClock.sleep(400)
                val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val before=call("localTime") as Long
                assertEquals("player",call("getScreen"))
                await("Pause button has focus"){call("getHudFocus")=="player-pause"}
                report.appendText("before Left: screen=${call("getScreen")} focus=${call("getHudFocus")} controls=${call("getControls")} keys=${call("hudKeys")} local=${call("localTime")} start=${ring.window.start} end=${ring.window.end}\n")
                device.pressDPadLeft()
                SystemClock.sleep(300)
                report.appendText("after Left: screen=${call("getScreen")} focus=${call("getHudFocus")} controls=${call("getControls")} keys=${call("hudKeys")} local=${call("localTime")}\n")
                device.takeScreenshot(File(ctx.getExternalFilesDir(null),"provider-focus.png"))
                device.dumpWindowHierarchy(File(ctx.getExternalFilesDir(null),"provider-focus.xml"))
                await("Rewind button has focus"){call("getHudFocus")=="player-rewind"}
                SystemClock.sleep(300)
                device.pressDPadCenter()
                await("HUD rewind moves exactly five seconds") { kotlin.math.abs((call("localTime") as Long)-(before-5000))<250 }
                assertEquals("player-rewind",call("getHudFocus"))
                assertTrue(player{!it.playWhenReady})
                val afterBack=call("localTime") as Long
                device.pressDPadRight()
                await("Pause button focused after rewind"){call("getHudFocus")=="player-pause"}
                device.pressDPadRight()
                await("Forward button has focus"){call("getHudFocus")=="player-forward"}
                SystemClock.sleep(300)
                device.pressDPadCenter()
                await("HUD forward moves exactly five seconds") { kotlin.math.abs((call("localTime") as Long)-(afterBack+5000))<250 }
                assertEquals("player-forward",call("getHudFocus"))
                assertTrue(player{!it.playWhenReady})
                report.appendText("HUD remote: rewind ${before-afterBack}ms; forward ${(call("localTime") as Long)-afterBack}ms; pause and focus preserved\n")
                val shot=File(ctx.getExternalFilesDir(null),"provider-timeshift-blue.png")
                device.takeScreenshot(shot)
                val bitmap=BitmapFactory.decodeFile(shot.absolutePath)
                var longestBlueRow=0
                for(y in bitmap.height/2 until bitmap.height){
                    var blue=0
                    for(x in 0 until bitmap.width){val c=bitmap.getPixel(x,y);if(kotlin.math.abs(android.graphics.Color.red(c)-43)<5&&kotlin.math.abs(android.graphics.Color.green(c)-130)<5&&kotlin.math.abs(android.graphics.Color.blue(c)-255)<5)blue++}
                    longestBlueRow=maxOf(longestBlueRow,blue)
                }
                bitmap.recycle()
                assertTrue("Blue saved range visible in HUD: $longestBlueRow pixels",longestBlueRow>100)
                report.appendText("Blue timeline visible: $longestBlueRow pixels in one row\n")
                call("resumePlayback",true)
                var rewindLog=0L
                await("Rewind plays",60000){player{
                    if(SystemClock.elapsedRealtime()-rewindLog>3000){rewindLog=SystemClock.elapsedRealtime();report.appendText("rewind state=${it.playbackState} ready=${it.playWhenReady} pos=${it.currentPosition} buffer=${it.totalBufferedDuration} error=${it.playerError?.errorCode} window=${ring.window.end-ring.window.start} before=$before target=$before local=${call("localTime")}\n")}
                    it.isPlaying
                }}
                val after=call("localTime") as Long
                assertTrue("Playback returns to the paused position after opposite five-second steps",kotlin.math.abs(before-after)<3000)
                assertNull(call("getArchiveSession"))
                player { framesBefore=it.videoDecoderCounters?.renderedOutputBufferCount?:0; true }
                verifyFrames("Video frames advance after rewind",framesBefore)
                report.appendText("pause stable, capture continues; rewind ${(before-after)/1000}s; bytes=${ring.window.bytes}\n")
                call("returnToLive",channel)
                await("Return to HLS default live position") { player {
                    if (!it.isPlaying || it.currentTimeline.isEmpty || it.currentMediaItem?.localConfiguration?.uri?.scheme!="tvwave-timeshift") false
                    else {
                        val live=it.currentTimeline.getWindow(0,androidx.media3.common.Timeline.Window()).defaultPositionMs
                        kotlin.math.abs(it.currentPosition-live)<5000
                    }
                } }
                assertEquals(false,call("getTimeshiftDelayed"))
                player { framesBefore=it.videoDecoderCounters?.renderedOutputBufferCount?:0; true }
                verifyFrames("Video frames advance after return",framesBefore)
                report.appendText("return live confirmed; delay=${ring.window.end-(call("localTime") as Long)}ms\n")
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(ctx.getExternalFilesDir(null),"provider-timeshift-$index.png"))
            }
            assertTrue("Video checks failed: $videoFailures",videoFailures.isEmpty())
        } finally {
            scenario.close();fixture.delete()
        }
    }
}
