package tv.tvwave.app

/** Playback time is relative to the selected programme, not the current live EPG. */
data class ArchiveSession(val channel:Channel,val programme:Programme,val uri:String, val remote:Boolean=false, val firstSegmentStartMs:Long=programme.start) {
    val duration:Long get()=(programme.end-programme.start).coerceAtLeast(1)
    fun available(now:Long)=(now-programme.start).coerceIn(0,duration)
    fun clamp(position:Long,now:Long)=position.coerceIn(0,available(now))
    fun position(windowStartMs:Long,playerPosition:Long,now:Long)=clamp(windowStartMs+playerPosition-programme.start,now)
}

fun playbackClock(ms:Long):String {
    val seconds=ms.coerceAtLeast(0)/1000
    return if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)
        else "%02d:%02d".format(seconds/60,seconds%60)
}
