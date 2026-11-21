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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class EpgUpdateState(val running:Boolean=false,val sourceId:String="",val status:String="",val failures:Map<String,String> = emptyMap(),val revision:Long=0,val playlistRevision:Long=0,val playlistFailures:Map<String,String> = emptyMap())

/** Finite user-visible import; independent of Activity lifetime. No playback service. */
class EpgUpdateService:Service(){
    companion object {
        val stateLock=Mutex()
        private val mutable=MutableStateFlow(EpgUpdateState())
        val state=mutable.asStateFlow()
        fun clearPlaylistFailure(id:String){mutable.value=mutable.value.copy(playlistFailures=mutable.value.playlistFailures-id)}
        private var instance:EpgUpdateService?=null
        private var cancelling=false
        private const val NOTIFICATION=42
        private const val CHANNEL="epg-updates"
        private fun prefs(c:Context)=c.getSharedPreferences("epg-work",Context.MODE_PRIVATE)
        // Exception messages can contain personal EPG URLs. Keep only types and app frames.
        private fun logFailure(stage:String,error:Throwable){
            val details=generateSequence(error){it.cause}.take(6).joinToString(" <- "){cause->
                cause.javaClass.simpleName+" "+cause.stackTrace.filter{it.className.startsWith("tv.tvwave.")}.take(4).joinToString(" | ")
            }
            android.util.Log.e("TVWaveEPG","$stage: $details")
        }
        fun pending(c:Context):List<String> = runCatching { val a=JSONArray(prefs(c).getString("queue","[]"));(0 until a.length()).map{a.getString(it)}.distinct().take(10) }.getOrDefault(emptyList())
        fun enqueue(c:Context,ids:List<String>){
            if(ids.isEmpty()||cancelling)return
            val queue=(pending(c)+ids).distinct().take(10)
            prefs(c).edit().putString("queue",JSONArray(queue).toString()).commit()
            ContextCompat.startForegroundService(c,Intent(c,EpgUpdateService::class.java))
        }
        fun resume(c:Context){
            if(pending(c).isNotEmpty())runCatching{enqueue(c,pending(c))}.onFailure{
                logFailure("resume",it)
                mutable.value=mutable.value.copy(status=tr("could_not_resume_the_update_try_again_in_settings"))
            }
        }
        suspend fun cancelAndWait(c:Context)=withContext(NonCancellable+Dispatchers.Main.immediate){
            cancelling=true
            try {
                prefs(c).edit().putString("queue","[]").commit()
                val service=instance
                service?.job?.cancel();service?.downloader?.cancel();service?.job?.join()
                service?.stopSelf()
                withContext(Dispatchers.IO){File(c.filesDir,"epg-work").deleteRecursively()}
                mutable.value=mutable.value.copy(running=false,sourceId="",status=tr("update_cancelled"),failures=emptyMap())
            } finally { cancelling=false }
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val worker=Executors.newFixedThreadPool(2) { task->Thread({Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run()},"TVWave-EPG") }.asCoroutineDispatcher()
    private var job:Job?=null
    private var wake:PowerManager.WakeLock?=null
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).callTimeout(12,TimeUnit.MINUTES).build()
    private val downloader=EpgDownload(client)
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
                catch(e:Exception){logFailure("queue",e);mutable.value=mutable.value.copy(status=tr("could_not_resume_the_update_open_the_app_and_try_again"))}
                finally{
                    downloader.cancel()
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
        mutable.update { it.copy(running=true,sourceId=source.id,status=text) }
        if(SystemClock.elapsedRealtime()-notified>1000){notified=SystemClock.elapsedRealtime();getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(text))}
    }
    private class SelectionChanged:Exception()
    private suspend fun refreshPlaylists(){
        val playlists=withContext(worker){stateLock.withLock{Repository(this@EpgUpdateService).use{it.load().playlists.filter{p->p.source.isNotBlank()}}}}
        mutable.value=mutable.value.copy(playlistFailures=emptyMap())
        for(playlist in playlists){
            currentCoroutineContext().ensureActive()
            progress(EpgSource("",playlist.name,""),tr("refreshing_playlist",playlist.name))
            try {
                val downloaded=PlaylistRefresh.download(playlist)
                withContext(worker){stateLock.withLock{
                    Repository(this@EpgUpdateService).use{repo->
                        val latest=repo.load()
                        val next=PlaylistRefresh.merge(latest,playlist,downloaded)
                        if(next!=latest){
                            repo.save(next)
                            mutable.value=mutable.value.copy(playlistRevision=mutable.value.playlistRevision+1)
                        }
                    }
                }}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                logFailure("playlist",e)
                val error=if(e is UserError)e.message.orEmpty()else tr("playlist_refresh_failed")
                mutable.value=mutable.value.copy(playlistFailures=mutable.value.playlistFailures+(playlist.id to error))
            }
        }
    }
    /** Once per update cycle. Existing usable schedules survive a failed download. */
    private suspend fun maintainBeforeUpdate(){
        progress(EpgSource("","",""),tr("cleaning_old_programme_data"))
        try {
            withContext(worker){
                CacheMaintenance.cleanAbandonedFiles(cacheDir,System.currentTimeMillis())
                stateLock.withLock {
                    Repository(this@EpgUpdateService).use { repo->
                        if(repo.maintain(repo.load()))
                            mutable.value=mutable.value.copy(revision=mutable.value.revision+1)
                    }
                }
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){logFailure("maintenance",e)}
    }
    private suspend fun runQueue(){
        mutable.value=mutable.value.copy(running=true)
        val began=SystemClock.elapsedRealtime()
        refreshPlaylists()
        if(pending(this).isNotEmpty())maintainBeforeUpdate()
        val dir=File(filesDir,"epg-work").apply{mkdirs()}
        var completed=0
        var cleanWork=true
        while(pending(this).isNotEmpty()){
            if(SystemClock.elapsedRealtime()-began>50*60*1000L){mutable.value=mutable.value.copy(status=tr("update_paused_it_will_resume_the_next_time_you_open_the_app"));break}
            val snapshot=withContext(worker){stateLock.withLock{Repository(this@EpgUpdateService).use{it.load()}}}
            if(cleanWork){
                withContext(worker){
                    val valid=snapshot.sources.map{workKey(it)+".complete"}.toSet()
                    dir.listFiles()?.filter{it.name !in valid || expired(it)}?.forEach{it.delete()}
                }
                cleanWork=false
            }
            val queue=pending(this)
            val total=completed+queue.size
            val batch=queue.take(if(parallelImportAvailable(dir))2 else 1)
            // Each parser writes a private staging DB. Only commits and UI progress follow queue order.
            coroutineScope {
                var previous:Deferred<Boolean>?=null
                val tasks=batch.mapIndexed { index,id ->
                    val turn=previous
                    async {
                        val source=snapshot.sources.firstOrNull{it.id==id}
                        if(source==null){removePending(id);true}
                        else runSource(source,snapshot,"${source.name.take(24)} · ${completed+index+1}/$total",dir,turn)
                    }.also { previous=it }
                }
                completed+=tasks.awaitAll().count{it}
            }
        }
        if(pending(this).isEmpty())dir.delete()
    }

    private fun parallelImportAvailable(dir:File):Boolean {
        val manager=getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return !manager.isLowRamDevice && manager.memoryClass>=192 && Runtime.getRuntime().availableProcessors()>=2 &&
            dir.usableSpace>2*Epg.MAX_XML &&
            !ActivityManager.MemoryInfo().also{manager.getMemoryInfo(it)}.lowMemory
    }
    private fun workKey(source:EpgSource)=MessageDigest.getInstance("SHA-256")
        .digest((source.id+"\n"+source.url).toByteArray()).toHex()
    private fun expired(file:File)=System.currentTimeMillis()-file.lastModified()>24*3600000L

    private suspend fun runSource(source:EpgSource,snapshot:AppState,label:String,dir:File,previous:Deferred<Boolean>?):Boolean {
        val key=workKey(source)
        val downloaded=File(dir,"$key.complete")
        val partial=File(dir,"$key.partial")
        val staged=File(dir,"$key.db")
        mutable.update { it.copy(failures=it.failures-source.id) }
        try {
            val result=withContext(worker){
                if(downloaded.exists()&&expired(downloaded))downloaded.delete()
                updateSource(source,snapshot,label,downloaded,partial,staged,previous)
            }
            val status=if(result==null)tr("guide_already_current",label) else tr("100_updated_programmes",label,result)
            mutable.update { it.copy(revision=it.revision+1,status=status) }
        }catch(_:SelectionChanged){
            return false // Retry with the completed download and the new selection.
        }catch(e:Exception){
            currentCoroutineContext().ensureActive()
            previous?.await()
            logFailure("source",e)
            val error=if(e is UserError)e.message.orEmpty()else tr("could_not_update_the_guide_check_your_connection_and_available_storage")
            mutable.update { it.copy(failures=it.failures+(source.id to error),status="$label · $error") }
        }finally{
            withContext(NonCancellable+worker){android.database.sqlite.SQLiteDatabase.deleteDatabase(staged);partial.delete()}
        }
        withContext(worker){downloaded.delete()}
        removePending(source.id)
        return true
    }

    private suspend fun updateSource(source:EpgSource,snapshot:AppState,label:String,downloaded:File,partial:File,staged:File,
                                     previous:Deferred<Boolean>?):Int? {
        fun report(text:String){if(previous==null || previous.isCompleted)progress(source,text)}
        suspend fun awaitTurn(){if(previous?.await()==false)throw SelectionChanged()}
        val context=currentCoroutineContext()
        val now=System.currentTimeMillis()
        val oldest=EpgRetention.storageCutoff(now,snapshot.epgOffsets.values)
        val selection=EpgHttpCache.selection(snapshot,source.id)
        val cached=stateLock.withLock { Repository(this).use {
            EpgHttpCache.read(it.readableDatabase,source,selection,oldest)
        } }
        val started=SystemClock.elapsedRealtime()
        val version=if(downloaded.exists()) {
            report(tr("resuming_processing_of_the_downloaded_file",label))
            null
        } else {
            report(tr("connecting",label))
            var reported=0L
            downloader.fetch(source,cached?.version,partial,downloaded,context) { bytes,total ->
                val time=SystemClock.elapsedRealtime()
                if(time-reported>1000) {
                    reported=time
                    report("${label}\n${EpgProgress.downloading(bytes,total)}")
                }
            }
        }
        val fetched=SystemClock.elapsedRealtime()
        if(version!=null && cached!=null && version.digest==cached.version.digest) {
            awaitTurn()
            stateLock.withLock { Repository(this).use { repo ->
                val db=repo.writableDatabase
                db.beginTransaction()
                try {
                    val latest=repo.load()
                    if(sourceMissing(latest,source))throw UserError(tr("the_source_has_changed_update_it_again"))
                    val current=EpgHttpCache.read(db,source,EpgHttpCache.selection(latest,source.id),
                        EpgRetention.storageCutoff(System.currentTimeMillis(),latest.epgOffsets.values))
                    if(current!=cached)throw SelectionChanged()
                    db.execSQL("UPDATE guide_meta SET updated=? WHERE source=?",arrayOf<Any>(now,source.id))
                    EpgHttpCache.record(db,source.id,cached.copy(version=version))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } }
            android.util.Log.i("TVWaveEPG","unchanged body=${downloaded.exists()} fetch_ms=${fetched-started}")
            return null
        }

        context.ensureActive()
        val result=downloaded.inputStream().use { file ->
            Epg.Bounded(file,Epg.MAX_COMPRESSED).use { input ->
                EpgCache.stage(staged,input,snapshot.channels,source,now,progress={ count ->
                    context.ensureActive()
                    report("${label}\n${EpgProgress.processing(count,input.count,downloaded.length())}")
                },oldest=oldest,state=snapshot)
            }
        }
        val parsed=SystemClock.elapsedRealtime()
        context.ensureActive()
        awaitTurn()
        val turnStarted=SystemClock.elapsedRealtime()
        report(tr("saving",label))
        stateLock.withLock { Repository(this).use { repo ->
            val latest=repo.load()
            if(sourceMissing(latest,source))throw UserError(tr("the_source_has_changed_update_it_again"))
            if(selection!=EpgHttpCache.selection(latest,source.id) ||
                oldest>EpgRetention.storageCutoff(System.currentTimeMillis(),latest.epgOffsets.values))throw SelectionChanged()
            // An interrupted import resumes from disk without attributing stale HTTP headers to it.
            val entry=version?.let { ImportedEpg(it,selection,oldest,result.programmes) }
            repo.installEpg(staged,source,latest,entry)
        } }
        android.util.Log.i("TVWaveEPG","source installed programmes=${result.programmes} fetch_ms=${fetched-started} parse_ms=${parsed-fetched} wait_ms=${turnStarted-parsed} save_ms=${SystemClock.elapsedRealtime()-turnStarted}")
        return result.programmes
    }
    private fun sourceMissing(state:AppState,source:EpgSource)=state.sources.none{it.id==source.id && it.url==source.url}

    private fun removePending(id:String){prefs(this).edit().putString("queue",JSONArray(pending(this).filter{it!=id}).toString()).commit()}
    override fun onTimeout(startId:Int,fgsType:Int){job?.cancel();downloader.cancel();stopSelf()}
    override fun onDestroy(){
        if(instance===this)instance=null
        scope.cancel();downloader.cancel();wake?.let{if(it.isHeld)it.release()};wake=null
        val current=job
        if(current==null)worker.close()else current.invokeOnCompletion{worker.close()}
        super.onDestroy()
    }
}
