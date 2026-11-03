package tv.tvwave.app

import android.app.Activity
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlin.math.abs

/** Amlogic IPTV frequently omits frameRate in Format. Measure PTS, not network arrival timing.
 * Request only a same-resolution HDMI mode for this playback window; never change OS settings. */
class PlaybackDisplayMode(private val activity:Activity,player:ExoPlayer):VideoFrameMetadataListener {
    private val supported=Build.HARDWARE.contains("amlogic",ignoreCase=true)
    private val main=Handler(Looper.getMainLooper())
    private var foreground=false
    private var requested=false
    private var originalMode=0
    private var rate=0.0
    private var previous=Long.MIN_VALUE
    private val intervals=ArrayList<Long>(60)
    private var candidate=0.0
    private var stable=0
    private var reported=0.0
    private var released=false
    @Volatile private var generation=0
    private var frameGeneration=-1
    init{if(supported)player.setVideoFrameMetadataListener(this)}
    override fun onVideoFrameAboutToBeRendered(pts:Long,releaseTimeNs:Long,format:Format,mediaFormat:MediaFormat?){
        val currentGeneration=generation
        if(frameGeneration!=currentGeneration){frameGeneration=currentGeneration;previous=Long.MIN_VALUE;intervals.clear();stable=0;reported=0.0}
        val delta=pts-previous;previous=pts
        if(delta !in 10000L..100000L){intervals.clear();stable=0;return}
        intervals.add(delta)
        if(intervals.size<50)return
        val sorted=intervals.sorted();intervals.clear()
        val measured=1000000.0/sorted[sorted.size/2]
        val next=listOf(23.976,24.0,25.0,29.97,30.0,50.0,59.94,60.0).minBy{abs(it-measured)}
        if(abs(next-measured)>.15){stable=0;return}
        if(abs(candidate-next)<.01)stable++ else {candidate=next;stable=1}
        if(stable>=2&&abs(reported-next)>.01){reported=next;main.post{if(!released&&generation==currentGeneration){rate=next;apply()}}}
    }
    fun channelChanged(){generation++;rate=0.0}
    fun setForeground(value:Boolean){foreground=value;apply()}
    private fun apply(){
        if(!supported||released)return
        if(!foreground){restore();return}
        if(rate==0.0)return
        @Suppress("DEPRECATION") val display=activity.windowManager.defaultDisplay
        val current=display.mode
        // 25 fps / 50 fields per second should use 50 Hz; 30/60 should use 60 Hz.
        val target=when{rate in 24.8..25.2||rate in 49.8..50.2->50.0;rate>29.0->if(rate==29.97||rate==59.94)59.94 else 60.0;else->rate}
        val mode=display.supportedModes.filter{it.physicalWidth==current.physicalWidth&&it.physicalHeight==current.physicalHeight&&abs(it.refreshRate-target)<.2}.minByOrNull{abs(it.refreshRate-target)}?:return
        if(!requested&&abs(current.refreshRate-target)<.2)return
        val attrs=activity.window.attributes
        if(attrs.preferredDisplayModeId==mode.modeId)return
        if(!requested){originalMode=attrs.preferredDisplayModeId;requested=true}
        attrs.preferredDisplayModeId=mode.modeId;activity.window.attributes=attrs
        Log.i("TVWavePlayback","display requestedHz=${mode.refreshRate} measuredFps=$rate mode=${mode.modeId}")
    }
    private fun restore(){
        if(requested){val attrs=activity.window.attributes;attrs.preferredDisplayModeId=originalMode;activity.window.attributes=attrs;requested=false}
    }
    fun release(){restore();released=true;main.removeCallbacksAndMessages(null)}
}
