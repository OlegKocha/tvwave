package tv.tvwave.app

import android.content.Context

/** The preview recording is deliberately absent from release builds. */
object ArchivePreview {
    fun source(context:Context,channel:Channel):String? = null
}
