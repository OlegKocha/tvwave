package tv.tvwave.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Disposable image cache, deliberately outside configuration and portable backups. */
class ChannelLogos(context:Context):AutoCloseable {
    private val closed=AtomicBoolean(false)
    private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private data class Entry(val bitmap:Bitmap?,val until:Long)
    private val memory=object:LruCache<String,Entry>(4*1024*1024){override fun sizeOf(key:String,value:Entry)=value.bitmap?.byteCount?:128}
    private val slots=Semaphore(4)
    private val client=OkHttpClient.Builder().cache(Cache(File(context.cacheDir,"channel-logos"),20L*1024*1024))
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS)
        .addNetworkInterceptor{chain->val r=chain.proceed(chain.request());if(r.isSuccessful&&r.header("Cache-Control")==null)r.newBuilder().header("Cache-Control","max-age=86400").build()else r}.build()
    suspend fun load(raw:String):Bitmap? {
        if(closed.get())return null
        val url=runCatching{SourceIO.url(raw)}.getOrNull()?:return null
        return slots.withPermit {
            if(closed.get())return@withPermit null
            memory.get(url)?.takeIf{it.until>SystemClock.elapsedRealtime()}?.let{return@withPermit it.bitmap}
            val bitmap=download(url)
            if(!closed.get())memory.put(url,Entry(bitmap,SystemClock.elapsedRealtime()+if(bitmap==null)60000 else 900000))
            bitmap
        }
    }
    private suspend fun download(url:String):Bitmap?=suspendCancellableCoroutine{continuation->
        val call=client.newCall(Request.Builder().url(url).header("User-Agent","TVWave/${BuildConfig.VERSION_NAME}").build())
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){continuation.resume(null)}
            override fun onResponse(call:Call,response:Response){
                val bitmap=try{response.use{r->
                    if(!r.isSuccessful)return@use null
                    val body=r.body?:return@use null
                    if(body.contentLength()>MAX_BYTES)return@use null
                    val bytes=SourceIO.read(body.byteStream(),MAX_BYTES)
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    if(bounds.outWidth !in 1..8192||bounds.outHeight !in 1..8192)return@use null
                    var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>128)sample*=2
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
                }}catch(_:Exception){null}
                continuation.resume(bitmap)
            }
        })
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        memory.evictAll()
        // Closing a TLS socket may write a close-notify packet. Keep network and disk
        // cleanup off the Activity thread, even after its lifecycle has ended.
        cleanup.launch {
            try {
                client.dispatcher.cancelAll()
                runCatching{client.cache?.close()}
                runCatching{client.connectionPool.evictAll()}
                memory.evictAll()
            }finally{cleanup.cancel()}
        }
    }
    companion object {const val MAX_BYTES=2*1024*1024}
}
