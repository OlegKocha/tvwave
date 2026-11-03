package tv.tvwave.app

import java.util.Calendar
import java.util.TimeZone

object EpgRetention {
    const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    fun cutoff(now:Long, zone:TimeZone=TimeZone.getDefault()):Long = Calendar.getInstance(zone).apply {
        timeInMillis=now
        set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
        add(Calendar.DAY_OF_YEAR,-5)
    }.timeInMillis
    // Stored XMLTV times precede displayed times when a channel has a positive
    // manual correction. Keep that small margin so cleanup cannot cut its history.
    fun storageCutoff(now:Long,offsets:Collection<Int>)=cutoff(now)-(offsets.maxOrNull()?:0).coerceIn(0,720)*60000L
    fun due(last:Long, now:Long)=last==0L || now<last || now-last>=WEEK_MS
}
