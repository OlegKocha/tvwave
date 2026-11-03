package tv.tvwave.app

import java.text.NumberFormat
import java.util.Locale

/** File consumption is approximate processing progress, not a programme total or ETA. */
object EpgProgress {
    fun percent(read:Long,total:Long):Int? = if(total<=0)null else
        (read.coerceIn(0,total).toDouble()/total*100).toInt().coerceAtMost(99)
    fun processing(programmes:Int,read:Long,total:Long):String {
        val count=NumberFormat.getIntegerInstance(L10n.locale).format(programmes)
        return tr("processed_programmes", count)+(percent(read,total)?.let{" · ≈${it}%"}?:"")
    }
    fun downloading(read:Long,total:Long):String = tr("downloading_mb", read/1048576)+
        (percent(read,total)?.let{" · ${it}%"}?:"")
}
