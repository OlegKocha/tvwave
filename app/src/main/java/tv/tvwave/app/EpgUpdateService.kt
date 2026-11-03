package tv.tvwave.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class EpgUpdateState(val running:Boolean=false,val sourceId:String="",val status:String="",val failures:Map<String,String> = emptyMap(),val revision:Long=0)

/** Finite user-visible import; independent of Activity lifetime. No playback service. */
class EpgUpdateService:Service(){
    companion object {
        val stateLock=Mutex()
        private val mutable=MutableStateFlow(EpgUpdateState())
        val state=mutable.asStateFlow()
        private var instance:EpgUpdateService?=null
        private var cancelling=false
        private const val NOTIFICATION=42
        private const val CHANNEL="epg-updates"
        private fun prefs(c:Context)=c.getSharedPreferences("epg-work",Context.MODE_PRIVATE)
        fun pending(c:Context):List<String> = runCatching { val a=JSONArray(prefs(c).getString("queue","[]"));(0 until a.length()).map{a.getString(it)}.distinct().take(10) }.getOrDefault(emptyList())
        fun enqueue(c:Context,ids:List<String>){
            if(ids.isEmpty()||cancelling)return
            val queue=(pending(c)+ids).distinct().take(10)
            prefs(c).edit().putString("queue",JSONArray(queue).toString()).commit()
            ContextCompat.startForegroundService(c,Intent(c,EpgUpdateService::class.java))
        }
        fun resume(c:Context){
            if(pending(c).isNotEmpty())runCatching{enqueue(c,pending(c))}.onFailure{
                mutable.value=mutable.value.copy(status=tr("could_not_resume_the_update_try_again_in_settings"))
            }
        }
        suspend fun cancelAndWait(c:Context)=withContext(NonCancellable+Dispatchers.Main.immediate){
            cancelling=true
            try {
                prefs(c).edit().putString("queue","[]").commit()
                val service=instance
                service?.activeCall?.cancel();service?.job?.cancelAndJoin()
                service?.stopSelf()
                withContext(Dispatchers.IO){File(c.filesDir,"epg-work").deleteRecursively()}
                mutable.value=mutable.value.copy(running=false,sourceId="",status=tr("update_cancelled"),failures=emptyMap())
            } finally { cancelling=false }
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val worker=Executors.newSingleThreadExecutor { task->Thread({Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run()},"TVWave-EPG") }.asCoroutineDispatcher()
    private var job:Job?=null
    @Volatile private var activeCall:okhttp3.Call?=null
    private var wake:PowerManager.WakeLock?=null
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).callTimeout(12,TimeUnit.MINUTES).build()
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate();instance=this
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,tr("programme_guide_update"),NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel=PendingIntent.getService(this,1,Intent(this,EpgUpdateService::class.java).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("programme_guide_update")).setContentText(text.replace('\n',' ')).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0,tr("cancel"),cancel).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val n=notification(tr("update_continues_in_the_background"))
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(NOTIFICATION,n)
        if(intent?.action=="cancel") {scope.launch{cancelAndWait(this@EpgUpdateService)};return START_NOT_STICKY}
        if(job?.isActive!=true){
            wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"TVWave:EPG").apply{acquire(60*60*1000L)}
            job=scope.launch {
                try{withTimeout(50*60*1000L){runQueue()}}
                catch(_:TimeoutCancellationException){mutable.value=mutable.value.copy(status=tr("update_paused_it_will_resume_the_next_time_you_open_the_app"))}
                catch(_:CancellationException){}
                catch(_:Exception){mutable.value=mutable.value.copy(status=tr("could_not_resume_the_update_open_the_app_and_try_again"))}
                finally{
                    activeCall?.cancel();activeCall=null
                    wake?.let{if(it.isHeld)it.release()};wake=null
                    mutable.value=mutable.value.copy(running=false,sourceId="")
                    if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE)else @Suppress("DEPRECATION") stopForeground(true)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }
    private var notified=0L
    private fun progress(source:EpgSource,text:String){
        mutable.value=mutable.value.copy(running=true,sourceId=source.id,status=text)
        if(SystemClock.elapsedRealtime()-notified>1000){notified=SystemClock.elapsedRealtime();getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(text))}
    }
    private suspend fun runQueue(){
        mutable.value=mutable.value.copy(running=true)
        val began=SystemClock.elapsedRealtime()
        var pruned=false
        var completed=0
        while(pending(this).isNotEmpty()){
            if(SystemClock.elapsedRealtime()-began>50*60*1000L){mutable.value=mutable.value.copy(status=tr("update_paused_it_will_resume_the_next_time_you_open_the_app"));break}
            val id=pending(this).first()
            val snapshot=withContext(worker){stateLock.withLock{Repository(this@EpgUpdateService).use{it.load()}}}
            val source=snapshot.sources.firstOrNull{it.id==id}
            if(source==null){removePending(id);continue}
            mutable.value=mutable.value.copy(failures=mutable.value.failures-source.id)
            val label="${source.name.take(24)} · ${completed+1}/${completed+pending(this).size}"
            val key=MessageDigest.getInstance("SHA-256").digest((source.id+"\n"+source.url).toByteArray()).joinToString(""){"%02x".format(it)}
            val dir=File(filesDir,"epg-work").apply{mkdirs()}
            val downloaded=File(dir,"${key}.complete");val partial=File(dir,"${key}.partial");val staged=File(dir,"${key}.db")
            try{
                val context=currentCoroutineContext()
                val result=withContext(worker){
                    if(!pruned){
                        val valid=snapshot.sources.map{entry->MessageDigest.getInstance("SHA-256").digest((entry.id+"\n"+entry.url).toByteArray()).joinToString(""){"%02x".format(it)}+".complete"}.toSet()
                        dir.listFiles()?.filter{it.name !in valid||System.currentTimeMillis()-it.lastModified()>24*3600000L}?.forEach{it.delete()}
                        pruned=true
                    }
                    if(downloaded.exists()&&System.currentTimeMillis()-downloaded.lastModified()>24*3600000L)downloaded.delete()
                    if(!downloaded.exists()){
                        progress(source,tr("connecting", label))
                        val call=client.newCall(Request.Builder().url(source.url).build());activeCall=call
                        try{call.execute().use{response->
                            if(!response.isSuccessful)throw UserError(tr("the_epg_source_returned_http", response.code))
                            val body=response.body?:throw UserError(tr("empty_epg_response"))
                            if(body.contentLength()>Epg.MAX_COMPRESSED)throw UserError(tr("the_epg_source_exceeds_128_mb"))
                            var total=0L;var report=0L
                            body.byteStream().use{input->partial.outputStream().use{out->val buffer=ByteArray(65536)
                                while(true){context.ensureActive();val count=input.read(buffer);if(count<0)break;total+=count
                                    if(total>Epg.MAX_COMPRESSED)throw UserError(tr("the_epg_source_exceeds_128_mb"))
                                    out.write(buffer,0,count)
                                    if(SystemClock.elapsedRealtime()-report>1000){report=SystemClock.elapsedRealtime();progress(source,"${label}\n${EpgProgress.downloading(total,body.contentLength())}")}
                                };out.fd.sync()
                            }}
                            check(partial.renameTo(downloaded)){"Cannot preserve EPG download"}
                        }}finally{activeCall=null;partial.delete()}
                    }else progress(source,tr("resuming_processing_of_the_downloaded_file", label))
                    context.ensureActive()
                    Epg.Bounded(downloaded.inputStream(),Epg.MAX_COMPRESSED).use{input->
                        EpgCache.stage(staged,input,snapshot.channels,source,System.currentTimeMillis(),progress={count->
                            context.ensureActive();progress(source,"${label}\n${EpgProgress.processing(count,input.count,downloaded.length())}")
                        },oldest=EpgRetention.storageCutoff(System.currentTimeMillis(),snapshot.epgOffsets.values))
                    }
                }
                currentCoroutineContext().ensureActive()
                progress(source,tr("saving", label))
                withContext(worker){stateLock.withLock{
                    Repository(this@EpgUpdateService).use{repo->
                        val latest=repo.load()
                        if(latest.sources.none{it.id==source.id&&it.url==source.url})throw UserError(tr("the_source_has_changed_update_it_again"))
                        repo.installEpg(staged,source,latest)
                    }
                }}
                mutable.value=mutable.value.copy(revision=mutable.value.revision+1,status=tr("100_updated_programmes", label, result.programmes))
                android.util.Log.i("TVWaveEPG","source installed programmes=${result.programmes}")
            }catch(e:Exception){
                if(!currentCoroutineContext().isActive)throw CancellationException()
                val error=if(e is UserError)e.message.orEmpty()else tr("could_not_update_the_guide_check_your_connection_and_available_storage")
                mutable.value=mutable.value.copy(failures=mutable.value.failures+(source.id to error),status="${label} · ${error}")
            }finally{
                withContext(NonCancellable+worker){android.database.sqlite.SQLiteDatabase.deleteDatabase(staged);partial.delete()}
            }
            // Retain a complete download only while work is pending (process death / interruption).
            withContext(worker){downloaded.delete()};removePending(id);completed++
        }
        if(pending(this).isEmpty())File(filesDir,"epg-work").delete()
    }
    private fun removePending(id:String){prefs(this).edit().putString("queue",JSONArray(pending(this).filter{it!=id}).toString()).commit()}
    override fun onTimeout(startId:Int,fgsType:Int){activeCall?.cancel();job?.cancel();stopSelf()}
    override fun onDestroy(){
        if(instance===this)instance=null
        activeCall?.cancel();scope.cancel();wake?.let{if(it.isHeld)it.release()};wake=null
        val current=job
        if(current==null)worker.close()else current.invokeOnCompletion{worker.close()}
        super.onDestroy()
    }
}
