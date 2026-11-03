package tv.tvwave.app

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Timing/counters only. Never logs stream URLs, headers or provider credentials. */
class PlaybackDiagnostics(private val player:ExoPlayer,scope:CoroutineScope):AnalyticsListener {
    init {
        player.addAnalyticsListener(this)
        scope.launch { while(true){
            delay(10000)
            if(player.playWhenReady){
                val c=player.videoDecoderCounters;c?.ensureUpdated()
                val f=player.videoFormat
                Log.i("TVWavePlayback","sample state=${player.playbackState} playing=${player.isPlaying} bufferMs=${player.totalBufferedDuration} speed=${player.playbackParameters.speed} size=${f?.width}x${f?.height} fps=${f?.frameRate} rendered=${c?.renderedOutputBufferCount} dropped=${c?.droppedBufferCount} skipped=${c?.skippedOutputBufferCount} maxDrop=${c?.maxConsecutiveDroppedBufferCount}")
            }
        }}
    }
    override fun onVideoDecoderInitialized(e:AnalyticsListener.EventTime,name:String,t:Long,duration:Long){Log.i("TVWavePlayback","decoder=$name initMs=$duration")}
    override fun onVideoInputFormatChanged(e:AnalyticsListener.EventTime,f:Format,reuse:DecoderReuseEvaluation?){Log.i("TVWavePlayback","video mime=${f.sampleMimeType} codecs=${f.codecs} size=${f.width}x${f.height} fps=${f.frameRate} reuse=${reuse?.result} discardReasons=${reuse?.discardReasons}")}
    override fun onDroppedVideoFrames(e:AnalyticsListener.EventTime,count:Int,elapsed:Long){Log.i("TVWavePlayback","dropped=$count elapsedMs=$elapsed")}
    override fun onLoadCompleted(e:AnalyticsListener.EventTime,info:LoadEventInfo,data:MediaLoadData){
        if(data.dataType==androidx.media3.common.C.DATA_TYPE_MEDIA)Log.i("TVWavePlayback","segment bytes=${info.bytesLoaded} loadMs=${info.loadDurationMs} mediaMs=${data.mediaEndTimeMs-data.mediaStartTimeMs}")
    }
    override fun onLoadError(e:AnalyticsListener.EventTime,info:LoadEventInfo,data:MediaLoadData,error:java.io.IOException,canceled:Boolean){Log.i("TVWavePlayback","loadError type=${error.javaClass.simpleName} canceled=$canceled")}
}
