package tv.tvwave.app

import android.content.Intent
import android.media.MediaCodecList
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in inspection of existing playback. Never seeds or clears data. */
class AudioDiagnosticsTest {
    @Test fun inspectExistingPlayback() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectPlayback")=="true")
        check(instrumentation.targetContext.packageName=="tv.tvwave.app.debug")
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var player:ExoPlayer
        instrumentation.runOnMainSync { player=MainActivity::class.java.getDeclaredField("player").apply{isAccessible=true}.get(activity) as ExoPlayer }
        val state=Repository(instrumentation.targetContext).use{it.load()}
        val channel=state.channels.firstOrNull{it.id==state.last}?:state.channels.firstOrNull()?:error("No existing channel")
        instrumentation.runOnMainSync{
            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredAudioLanguage(state.audioLanguage.ifEmpty{null}).build()
            player.setMediaItem(MediaItem.fromUri(channel.url));player.prepare();player.play()
        }
        val until=SystemClock.uptimeMillis()+20000
        while(SystemClock.uptimeMillis()<until){
            var ready=false
            instrumentation.runOnMainSync{ready=player.currentTracks.groups.isNotEmpty()}
            if(ready){SystemClock.sleep(3000);break}
            SystemClock.sleep(500)
        }
        instrumentation.runOnMainSync{
            println("AUDIO_DIAG state=${player.playbackState} playing=${player.isPlaying} volume=${player.volume} suppression=${player.playbackSuppressionReason} error=${player.playerError?.errorCodeName}")
            val p=player.trackSelectionParameters
            println("AUDIO_DIAG disabled=${p.disabledTrackTypes} maxChannels=${p.maxAudioChannelCount} maxRate=${p.maxAudioBitrate} languages=${p.preferredAudioLanguages}")
            player.currentTracks.groups.forEach { group->
                for(i in 0 until group.length){val f=group.getTrackFormat(i)
                    println("AUDIO_DIAG track type=${group.type} selected=${group.isTrackSelected(i)} support=${group.getTrackSupport(i)} mime=${f.sampleMimeType} codecs=${f.codecs} channels=${f.channelCount} rate=${f.sampleRate} language=${f.language}")
                }
            }
            println("AUDIO_DIAG audioFormat=${player.audioFormat?.sampleMimeType} audioCounters=${player.audioDecoderCounters?.renderedOutputBufferCount}")
        }
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter{!it.isEncoder}.forEach{codec->
            codec.supportedTypes.filter{it.startsWith("audio/")}.forEach{println("AUDIO_DIAG decoder=${codec.name} mime=$it")}
        }
    }
}
