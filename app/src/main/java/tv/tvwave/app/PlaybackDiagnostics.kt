package tv.tvwave.app

import android.util.Log
import android.os.SystemClock
import android.os.Build
import android.media.MediaCodecList
import androidx.media3.exoplayer.DefaultLoadControl
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
    private var decoderName:String?=null
    private var hardware:Boolean?=null
    private val dropped=java.util.ArrayDeque<Pair<Long,Int>>()
    private fun pruneDrops(now:Long){while(dropped.peekFirst()?.let{now-it.first>=60000}==true)dropped.removeFirst()}
    fun snapshot(refreshRate:Float):PlaybackHealth {
        val now=SystemClock.elapsedRealtime();pruneDrops(now)
        val speed=player.playbackParameters.speed.coerceAtLeast(.1f)
        val buffered=(player.totalBufferedDuration.coerceAtLeast(0)/speed).toLong()
        val target=DefaultLoadControl.DEFAULT_MAX_BUFFER_MS.toLong()
        val f=player.videoFormat
        return PlaybackHealth(buffered,target,dropped.sumOf{it.second},f?.frameRate?.takeIf{it>0},refreshRate.takeIf{it>0},f?.sampleMimeType,decoderName,hardware)
    }
    override fun onMediaItemTransition(e:AnalyticsListener.EventTime,item:androidx.media3.common.MediaItem?,reason:Int){dropped.clear();decoderName=null;hardware=null}
    override fun onVideoDisabled(e:AnalyticsListener.EventTime,counters:androidx.media3.exoplayer.DecoderCounters){decoderName=null;hardware=null;dropped.clear()}
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
    override fun onVideoDecoderInitialized(e:AnalyticsListener.EventTime,name:String,t:Long,duration:Long){
        decoderName=name
        hardware=if(Build.VERSION.SDK_INT>=29)runCatching{MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull{it.name==name}?.isHardwareAccelerated}.getOrNull()else null
        Log.i("TVWavePlayback","decoder=$name initMs=$duration")}
    override fun onVideoInputFormatChanged(e:AnalyticsListener.EventTime,f:Format,reuse:DecoderReuseEvaluation?){Log.i("TVWavePlayback","video mime=${f.sampleMimeType} codecs=${f.codecs} size=${f.width}x${f.height} fps=${f.frameRate} reuse=${reuse?.result} discardReasons=${reuse?.discardReasons}")}
    override fun onDroppedVideoFrames(e:AnalyticsListener.EventTime,count:Int,elapsed:Long){val now=SystemClock.elapsedRealtime();pruneDrops(now);dropped.addLast(now to count);Log.i("TVWavePlayback","dropped=$count elapsedMs=$elapsed")}
    override fun onLoadCompleted(e:AnalyticsListener.EventTime,info:LoadEventInfo,data:MediaLoadData){
        if(data.dataType==androidx.media3.common.C.DATA_TYPE_MEDIA)Log.i("TVWavePlayback","segment bytes=${info.bytesLoaded} loadMs=${info.loadDurationMs} mediaMs=${data.mediaEndTimeMs-data.mediaStartTimeMs}")
    }
    override fun onLoadError(e:AnalyticsListener.EventTime,info:LoadEventInfo,data:MediaLoadData,error:java.io.IOException,canceled:Boolean){Log.i("TVWavePlayback","loadError type=${error.javaClass.simpleName} canceled=$canceled")}
}

/** Percentage is relative to the time target, not programme completion or RAM usage. */
data class PlaybackHealth(
    val bufferedMs:Long=0,val targetMs:Long=DefaultLoadControl.DEFAULT_MAX_BUFFER_MS.toLong(),val droppedLastMinute:Int=0,
    val fps:Float?=null,val refreshHz:Float?=null,val mime:String?=null,val decoder:String?=null,val hardware:Boolean?=null
){
    val percent:Int get()=if(targetMs>0)((bufferedMs*100/targetMs).coerceIn(0,100)).toInt()else 0
    val seconds:String get()=String.format(java.util.Locale.ROOT,"%.1f",bufferedMs/1000.0)
}
