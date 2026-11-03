package tv.tvwave.app

import android.content.Context
import android.os.Build
import java.io.File

/** Opt-in synthetic fixture. No provider guessing, and never active on physical TVs. */
object ArchivePreview {
    fun source(context:Context,channel:Channel):String? {
        if(!(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk")))return null
        if(channel.url!="http://10.0.2.2:8765/video.mp4?archive-demo=1")return null
        return File(context.filesDir,"archive-preview.mp4").takeIf{it.isFile}?.toURI()?.toString()
    }
}
