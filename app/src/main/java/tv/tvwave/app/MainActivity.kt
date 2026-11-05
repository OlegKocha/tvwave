package tv.tvwave.app

import android.graphics.Bitmap
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.datasource.HttpDataSource
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent as AndroidKey
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private val LocalPanelPage = staticCompositionLocalOf<String?> { null }

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : ComponentActivity() {
    private val appUpdates by viewModels<AppUpdateViewModel>()
    private var updateInstallBusy by mutableStateOf(false)
    private var updateWindowFocusRevision by mutableIntStateOf(0)
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        appUpdates.installerMessage(if(result.resultCode == RESULT_CANCELED) "update_cancelled" else null)
    }
    private val updatePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) installAppUpdate()
        else appUpdates.installerMessage("update_permission_denied")
    }
    private lateinit var repository: Repository
    private lateinit var player: ExoPlayer
    private lateinit var playbackDisplayMode:PlaybackDisplayMode
    private val stateLock=EpgUpdateService.stateLock
    private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    private var data by mutableStateOf(AppState())
    private var applying by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var optimizingGuide by mutableStateOf(false)
    private var loadFailed by mutableStateOf(false)
    private val exitConfirmation=ExitConfirmation()
    private var exitHint by mutableStateOf(false)
    private var exitHintRevision by mutableIntStateOf(0)
    private var epgDraft by mutableStateOf("")
    private var epgUpdating by mutableStateOf(false)
    private var epgStatus by mutableStateOf("")
    private var epgSourceId by mutableStateOf("")
    private var epgName by mutableStateOf("")
    private var epgOrder by mutableStateOf<List<EpgSource>>(emptyList())
    private var movingEpg by mutableStateOf<String?>(null)
    private var epgPlaylistId by mutableStateOf("")
    private var epgFailures by mutableStateOf<Map<String,String>>(emptyMap())
    private var updatingEpgId by mutableStateOf("")
    private var epgStatuses by mutableStateOf<Map<String,EpgStatus>>(emptyMap())
    private var epgLinks by mutableStateOf<Map<String,EpgBinding>>(emptyMap())
    private var epgLogos by mutableStateOf<Map<String,String>>(emptyMap())
    private lateinit var logoLoader:ChannelLogos
    private var matchQuery by mutableStateOf("")
    private var catalogQuery by mutableStateOf("")
    private var matchTarget by mutableStateOf("")
    private var matchSource by mutableStateOf("")
    private var matchCatalog by mutableStateOf<List<EpgChannel>>(emptyList())
    private var unmatchedOnly by mutableStateOf(true)
    private var editingPlaylist by mutableStateOf<String?>(null)
    private var selectedPlaylist by mutableStateOf("")
    private val recentOrder=RecentOrder()
    private var epgRevision by mutableIntStateOf(0)
    private var epgRows by mutableStateOf<Map<String,List<Programme>>>(emptyMap())
    private var nowTime by mutableLongStateOf(System.currentTimeMillis())
    private var guideTime by mutableLongStateOf(0L)
    private var searchEditing by mutableStateOf(false)
    private var screen by mutableStateOf("guide")
    private var backgroundScreen by mutableStateOf("guide")
    private val panelStack=mutableListOf<String>()
    private val panelFocus=mutableMapOf<String,String>()
    private val hudRequests=mutableMapOf<String,FocusRequester>()
    private var epgNavigate:((AndroidKey)->Boolean)?=null
    private var selectedChannel by mutableStateOf("")
    private var guideZone by mutableIntStateOf(0)
    private var browseZone by mutableIntStateOf(0)
    private var detailsReturn="guide"
    private var clockText by mutableStateOf("")
    private var guideDate by mutableStateOf("")
    private var videoHeight by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private var busySeconds by mutableIntStateOf(0)
    private var message by mutableStateOf("")
    private var error by mutableStateOf("")
    private var playing by mutableStateOf<Channel?>(null)
    private var playback by mutableStateOf("stopped")
    private var playbackError by mutableStateOf("")
    private var archiveSession by mutableStateOf<ArchiveSession?>(null)
    private var archivePosition by mutableLongStateOf(0L)
    private var seekDraft by mutableStateOf<Long?>(null)
    private var pendingProgramme by mutableStateOf<Pair<Channel,Programme>?>(null)
    private var archiveEnded by mutableStateOf(false)
    private var archiveLoadJob:Job?=null
    private var archiveInitialPosition:Long?=null
    private var hudFocus by mutableStateOf("player-pause")
    private lateinit var playbackDiagnostics:PlaybackDiagnostics
    private var diagnosticsOpen by mutableStateOf(false)
    private var hudUtilityFocus="player-subtitles"
    private val recovery=PlaybackRecovery()
    private var recoveryJob:Job?=null
    private var stableJob:Job?=null
    private var bufferingJob:Job?=null
    private var recoveryStatus by mutableStateOf("")
    private var phoneSession by mutableStateOf<PhoneLink?>(null)
    private var phoneTarget="source"
    private var phoneSeconds by mutableLongStateOf(0L)
    private var controls by mutableStateOf(true)
    private var interaction by mutableLongStateOf(0)
    private var group by mutableStateOf(BuiltinGroups.ALL)
    private var query by mutableStateOf("")
    private var sourceName by mutableStateOf("")
    private var sourceUrl by mutableStateOf("")
    private var sourceMode by mutableStateOf("")
    private var candidate by mutableStateOf<Playlist?>(null)
    private var chosenName by mutableStateOf("")
    private var password by mutableStateOf("")
    private var restoreBytes: ByteArray?=null
    private var restoreState by mutableStateOf<AppState?>(null)
    private var exportBytes: ByteArray?=null
    private var ioJob: Job?=null
    private var files by mutableStateOf<List<File>>(emptyList())
    private var folder by mutableStateOf<File?>(null)
    private var filePurpose="playlist"
    private var previousScreen="source"
    private val imports get()=File(getExternalFilesDir(null),"Import").apply { mkdirs() }
    private val exportPicker=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if(uri==null) { exportBytes?.fill(0);exportBytes=null;message=tr("save_cancelled") }
        else saveExport(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        repository=Repository(this)
        logoLoader=ChannelLogos(applicationContext)
        // Keep platform decoders first; fall back to FFmpeg when a TV lacks an audio codec (e.g. MP2).
        val renderers=DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player=ExoPlayer.Builder(this,renderers).setMediaSourceFactory(PlaybackSources(androidx.media3.datasource.DefaultDataSource.Factory(this,OkHttpDataSource.Factory(http).setUserAgent("TVWave/${BuildConfig.VERSION_NAME}"))).setLoadErrorHandlingPolicy(object:DefaultLoadErrorHandlingPolicy(0){override fun getRetryDelayMsFor(info:androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo)=C.TIME_UNSET})).build()
        playbackDiagnostics=PlaybackDiagnostics(player,lifecycleScope)
        playbackDisplayMode=PlaybackDisplayMode(this,player)
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        player.addListener(object: Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if(state==Player.STATE_READY){
                    val a=archiveSession;val target=archiveInitialPosition
                    if(a?.remote==true&&target!=null){
                        archiveInitialPosition=null
                        val first=(player.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest)?.mediaPlaylist?.segments?.firstOrNull()?.url
                        val window=first?.let{IlookArchive.segmentTime(it)}?:a.firstSegmentStartMs
                        val seek=(a.programme.start+target-window).coerceAtLeast(0)
                        android.util.Log.i("TVWaveArchive","prepared requestedMs=${a.programme.start+target} windowMs=${window} playerMs=${player.currentPosition} seekMs=${seek}")
                        player.seekTo(seek)
                    }
                }
                updatePlayback()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayback() }
            override fun onVideoSizeChanged(videoSize: VideoSize) { videoHeight=videoSize.height }
            override fun onPlayerError(e: PlaybackException) { recoverPlayback(e) }
        })
        lifecycleScope.launch {
            try { data=withContext(Dispatchers.IO){
                    val saved=stateLock.withLock{repository.load()}
                    runCatching{CacheMaintenance.cleanAbandonedFiles(cacheDir,System.currentTimeMillis());stateLock.withLock{if(!EpgUpdateService.state.value.running)repository.maintain(saved,compact=true,onFilter={runOnUiThread{if(saved.uiLanguage.isNotEmpty())L10n.select(saved.uiLanguage);optimizingGuide=true}})}}
                        .onFailure{android.util.Log.w("TVWave","Cache maintenance deferred",it)}
                    saved
                };if(data.uiLanguage.isNotEmpty())L10n.select(data.uiLanguage);selectedChannel=data.last.ifEmpty{data.channels.firstOrNull()?.id.orEmpty()};loaded=true;applyTracks()
                if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))EpgUpdateService.resume(this@MainActivity)
                if(data.resume&&data.uiLanguage.isNotEmpty()) data.channels.find{it.id==data.last}?.let{tune(it,false)}
            } catch(e:Exception){loadFailed=true;loaded=true;error=safeError(e)}
        }
        lifecycleScope.launch {
            var revision=EpgUpdateService.state.value.revision
            EpgUpdateService.state.collect { update->
                epgUpdating=update.running;epgStatus=update.status
                updatingEpgId=update.sourceId;epgFailures=update.failures
                if(revision!=update.revision){
                    revision=update.revision
                    withContext(Dispatchers.IO){repository.invalidateSchedules()}
                    epgRevision++
                }
            }
        }
        setContent { CompositionLocalProvider(LocalContentColor provides T.text) { App() } }
    }
    private fun updatePlayback() {
        playback=when { player.playerError!=null -> "error"; player.playbackState==Player.STATE_BUFFERING -> "buffering"; player.isPlaying -> "playing"; player.playbackState==Player.STATE_READY -> "paused"; else -> "stopped" }
        if(playback!="playing")controls=true
        if(playback=="playing"){
            recoveryStatus="";playbackError="";bufferingJob?.cancel();bufferingJob=null
            if(stableJob==null)stableJob=lifecycleScope.launch{delay(30000);recovery.reset();stableJob=null}
        }else{stableJob?.cancel();stableJob=null}
        if(playback=="buffering"&&player.playWhenReady&&bufferingJob==null){
            bufferingJob=lifecycleScope.launch{delay(20000);bufferingJob=null;if(player.playbackState==Player.STATE_BUFFERING&&player.playWhenReady)recoverPlayback(null)}
        }else if(playback!="buffering"){bufferingJob?.cancel();bufferingJob=null}
        if(player.playbackState==Player.STATE_ENDED&&archiveSession!=null){archiveEnded=true;controls=true}
        updateKeepScreen()
    }
    private fun updateKeepScreen() {
        if(playback in listOf("playing","buffering"))window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus)updateWindowFocusRevision++
    }
    override fun onStart(){super.onStart();playbackDisplayMode.setForeground(true);if(loaded&&!loadFailed)EpgUpdateService.resume(this)}
    override fun onStop(){ playbackDisplayMode.setForeground(false);cancelArchiveLoad();pausePlayback();exitConfirmation.reset();exitHint=false;super.onStop(); closePhone();cancelRecovery(); player.pause(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    override fun onDestroy(){ playbackDisplayMode.release();closePhone();cancelRecovery();player.release();logoLoader.close();repository.close();restoreBytes?.fill(0);exportBytes?.fill(0);super.onDestroy() }
    private fun cancelRecovery(){recoveryJob?.cancel();recoveryJob=null;stableJob?.cancel();stableJob=null;bufferingJob?.cancel();bufferingJob=null;recoveryStatus=""}
    private fun recoverPlayback(error:PlaybackException?){
        if(recoveryJob?.isActive==true)return
        bufferingJob?.cancel();bufferingJob=null;stableJob?.cancel();stableJob=null
        val code=generateSequence<Throwable>(error){it.cause}.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val terminal=code in listOf(401,403,404,410)||error?.errorCode in listOf(PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
        playbackError=when(code){401,403->tr("the_source_denied_access_check_your_subscription_or_url");404,410->tr("the_source_can_no_longer_find_this_channel_update_your_playlist");else->if(terminal)tr("this_video_format_is_not_supported_by_your_device")else tr("connection_to_the_channel_was_interrupted_check_your_network_or_select")}
        if(archiveSession!=null)playbackError=when(code){
            404,410->tr("this_programme_s_recording_is_unavailable")
            401,403->tr("the_source_denied_access_to_catch_up_check_your_subscription")
            else->if(terminal)tr("could_not_play_this_recording")else tr("could_not_load_catch_up_retry")
        }
        val wait=if(terminal||!player.playWhenReady)null else recovery.nextDelay()
        playback="error";controls=true;updateKeepScreen()
        if(wait==null){recoveryStatus="";return}
        val channel=playing?.id
        recoveryStatus=tr("attempt_of_3", if(archiveSession!=null)tr("reconnecting_to_recording")else tr("reconnecting_to_live_tv"), recovery.attempts)
        recoveryJob=lifecycleScope.launch{
            delay(wait)
            if(playing?.id==channel){recoveryJob=null;if(archiveSession?.remote==true)resumePlayback(resetRecovery=false)else{if(archiveSession==null)player.seekToDefaultPosition();player.prepare();player.play();updatePlayback()}}
        }
    }
    private fun retryPlayback(){
        cancelRecovery();recovery.reset();playbackError=""
        if(archiveSession?.remote==true){resumePlayback();return}
        if(archiveSession==null)player.seekToDefaultPosition();player.prepare();player.play();interaction++
    }
    private fun togglePlayback(){
        if(archiveLoadJob?.isActive==true){cancelArchiveLoad();message=""}
        if(archiveSession!=null&&(player.playbackState==Player.STATE_ENDED||archivePosition>=archiveSession!!.duration)){archiveEnded=true;controls=true;return}
        if(recoveryJob?.isActive==true){cancelRecovery();player.stop();playbackError=tr("reconnection_stopped_select_retry_when_you_are_ready");playback="error"}
        else if(player.isPlaying){cancelRecovery();pausePlayback()}
        else if(playback in listOf("error","stopped"))retryPlayback()else resumePlayback()
    }
    private fun closePhone(){phoneSession?.close();phoneSession=null}
    private fun openPhone(target:String){work{
        closePhone();phoneTarget=target
        lateinit var session:PhoneLink
        session=withContext(Dispatchers.IO){PhoneLink(PhoneLink.lanAddress(this@MainActivity)){value->runOnUiThread{
            if(phoneSession===session&&screen=="phone"){
                if(phoneTarget=="source"){sourceUrl=value;sourceMode="url";candidate=null}else epgDraft=value
                back();panelFocus[phoneTarget]=if(phoneTarget=="source")"validate"else"epg-save"
                message=tr("url_received_from_your_phone_check_it_before_saving")
            }
        }}}
        phoneSession=session;phoneSeconds=session.remainingSeconds;openPanel("phone");session.start()
    }}
    @Composable private fun PhoneScreen(){
        val session=phoneSession
        LaunchedEffect(session){while(session!=null&&phoneSession===session){phoneSeconds=session.remainingSeconds;if(phoneSeconds==0L){session.close();break};delay(1000)}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(T.pad)){
            if(session!=null&&phoneSeconds>0)Column(verticalArrangement=Arrangement.spacedBy(T.small)){
                val bitmap=remember(session){val matrix=QRCodeWriter().encode(session.url,BarcodeFormat.QR_CODE,T.qrPixels,T.qrPixels);Bitmap.createBitmap(T.qrPixels,T.qrPixels,Bitmap.Config.ARGB_8888).apply{for(y in 0 until height)for(x in 0 until width)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}
                Image(bitmap.asImageBitmap(),tr("qr_code_for_sending_a_url"),Modifier.size(T.qrSize).testTag("phone-qr"))
                Text(session.url.substringBeforeLast('/'),Modifier.width(T.qrSize),color=T.muted,fontSize=T.micro)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(T.small)){
                Caption(tr("scan_the_qr_code_with_your_phone_camera_your_phone_and_tv_must_be_on_t"))
                if(session!=null&&phoneSeconds>0)Caption(tr("remaining_one_submission", phoneSeconds/60, (phoneSeconds%60).toString().padStart(2,'0')))else Caption(tr("session_expired_open_a_new_qr_code"))
                Action(tr("close"),key="phone-close"){back()}
                Action(tr("new_qr_code"),key="phone-renew",enabled=!busy){closePhone();screen=phoneTarget;panelStack.removeAll{it==phoneTarget};openPhone(phoneTarget)}
            }
        }
    }
    private fun safeError(e:Exception)=if(e is UserError)e.message.orEmpty() else tr("could_not_complete_the_action_check_your_connection_file_access_and_av")
    private fun work(action:suspend ()->Unit) {
        if(busy)return
        error="";message="";busy=true
        ioJob=lifecycleScope.launch { try{action()}catch(e:CancellationException){throw e}catch(e:Exception){error=safeError(e)}finally{busy=false} }
    }
    private suspend fun change(resetCache:Boolean=false,transform:(AppState)->AppState) { stateLock.withLock {
        val next=transform(data); applying=true
        try{withContext(NonCancellable){withContext(Dispatchers.IO){repository.save(next,resetCache)};data=next.copy(epgBackup="");if(next.uiLanguage.isNotEmpty()&&next.uiLanguage!=L10n.language)L10n.select(next.uiLanguage)}}finally{applying=false}
    } }
    private var fontPreview by mutableStateOf<Int?>(null)
    private var fontSaveJob:Job?=null
    private val fontPercent get()=fontPreview?:data.fontPercent
    private fun resizeText(value:Int,immediate:Boolean=false){
        val percent=(value.coerceIn(60,130)/5f).roundToInt()*5
        fontPreview=percent
        fontSaveJob?.cancel()
        fontSaveJob=lifecycleScope.launch{
            try{
                if(!immediate)delay(200)
                change{it.copy(fontPercent=percent,large=percent>=120)}
                if(fontPreview==percent)fontPreview=null
            }catch(e:CancellationException){throw e}catch(e:Exception){error=safeError(e)}
        }
    }
    private fun persist(transform:(AppState)->AppState) { work{change(transform=transform)} }
    private fun applyTracks(){ player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredAudioLanguage(data.audioLanguage.ifEmpty{null}).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,!data.subtitles).build() }
    private fun tune(channel:Channel,full:Boolean=true) {
        cancelArchiveLoad()
        val wasArchive=archiveSession!=null
        if(wasArchive){archiveSession=null;seekDraft=null;archiveEnded=false;player.stop()}
        hudFocus="player-pause"
        if(!wasArchive&&playing?.id==channel.id&&full&&screen!="player"){
            panelStack.clear();screen="player";controls=true;interaction++;return
        }
        cancelRecovery();recovery.reset();playbackError=""
        if(playing?.id==channel.id && player.playbackState!=Player.STATE_IDLE && player.playerError==null) {
            if(full){panelStack.clear();screen="player"};controls=true;interaction++;player.play();return
        }
        playbackDisplayMode.channelChanged()
        // Amlogic retains interlace state; the emulator's goldfish decoder can retain
        // the old output dimensions after HD -> SD adaptive reuse (nested stale frames).
        // Reset decoding only on affected devices; keep the preview surface and focus.
        val hardware=android.os.Build.HARDWARE.lowercase(java.util.Locale.ROOT)
        if(hardware.contains("amlogic")||hardware=="ranchu"||hardware=="goldfish")player.stop()
        playing=channel;selectedChannel=channel.id;videoHeight=0;playbackError="";playback="buffering";controls=true;interaction++
        player.repeatMode=if(ArchivePreview.source(this,channel)!=null)Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setMediaItem(MediaItem.fromUri(channel.url));player.prepare();player.play();applyTracks()
        lifecycleScope.launch { try{stateLock.withLock {
            if(data.channels.none{it.id==channel.id})return@withLock
            val next=data.copy(last=channel.id,history=(listOf(channel.id)+data.history.filter{it!=channel.id}).take(30))
            withContext(NonCancellable){withContext(Dispatchers.IO){repository.saveViewing(next.last,next.history)};data=next}
        }}catch(e:Exception){error=safeError(e)} }
    }
    private fun requestProgrammePlayback() {
        val p=selectedProgramme()?:return
        val c=data.channels.firstOrNull{it.id==selectedChannel}?:return
        val now=System.currentTimeMillis()
        if(p.start>now){message=tr("this_programme_has_not_started_yet");return}
        if(p.end>now){pendingProgramme=c to p;return}
        startArchive(c,p)
    }
    private fun cancelArchiveLoad(){archiveLoadJob?.cancel();archiveLoadJob=null}
    private fun archiveSupported(channel:Channel)=ArchivePreview.source(this,channel)!=null||IlookArchive.supports(channel)
    private fun channelArchiveDays(channel:Channel?):Int = when {
        channel==null -> 0
        ArchivePreview.source(this,channel)!=null -> 4
        IlookArchive.supports(channel) -> channel.archiveDays
        else -> 0
    }
    private fun programmeHasArchive(channel:Channel?,p:Programme):Boolean {
        val days=channelArchiveDays(channel)
        return days>0 && p.start<nowTime && p.start>=nowTime-days*86_400_000L
    }
    private fun currentArchivePosition():Long {
        val a=archiveSession?:return 0L
        if(!a.remote)return player.currentPosition.coerceAtLeast(0)
        if(!player.isPlaying)return archivePosition
        val manifest=player.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest
        val first=manifest?.mediaPlaylist?.segments?.firstOrNull()?.url
        val windowStart=first?.let{IlookArchive.segmentTime(it)}?:a.firstSegmentStartMs
        return a.position(windowStart,player.currentPosition,System.currentTimeMillis())
    }
    private fun pausePlayback(){if(archiveSession!=null)archivePosition=currentArchivePosition();player.pause()}
    private fun resumePlayback(resetRecovery:Boolean=true){
        val a=archiveSession
        if(a?.remote==true)startArchive(a.channel,a.programme,archivePosition,hudFocus,true,resetRecovery)
        else player.play()
    }
    private fun applyArchive(session:ArchiveSession,position:Long,focus:String,playWhenReady:Boolean,resetRecovery:Boolean){
        cancelRecovery();if(resetRecovery)recovery.reset();playbackError="";message="";archiveEnded=false;seekDraft=null;pendingProgramme=null
        player.stop();archiveSession=session;archivePosition=session.clamp(position,System.currentTimeMillis())
        archiveInitialPosition=if(session.remote)archivePosition else null
        playing=session.channel;selectedChannel=session.channel.id;videoHeight=0;hudFocus=focus
        player.repeatMode=Player.REPEAT_MODE_OFF
        val item=MediaItem.Builder().setUri(session.uri)
        val start=if(session.remote){
            item.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
            (session.programme.start+archivePosition-session.firstSegmentStartMs).coerceAtLeast(0)
        }else{
            item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(session.duration).build())
            archivePosition
        }
        player.setMediaItem(item.build(),start);player.prepare();player.playWhenReady=playWhenReady
        applyTracks();panelStack.clear();screen="player";controls=true;interaction++
    }
    private fun startArchive(channel:Channel,p:Programme,position:Long=0L,focus:String="player-pause",playWhenReady:Boolean=true,resetRecovery:Boolean=true) {
        cancelArchiveLoad()
        val now=System.currentTimeMillis()
        if(p.start>=now){message=tr("this_programme_has_not_started_yet");return}
        val target=position.coerceIn(0,(minOf(p.end,now)-p.start).coerceAtLeast(0))
        val preview=ArchivePreview.source(this,channel)
        if(preview!=null){
            if(p.start<now-4*86400000L){message=tr("recording_unavailable_the_demo_archive_keeps_4_days");return}
            applyArchive(ArchiveSession(channel,p,preview),target,focus,playWhenReady,resetRecovery);return
        }
        if(!IlookArchive.supports(channel)){message=tr("catch_up_is_unavailable_for_this_channel");return}
        if(IlookArchive.expired(channel,p,now)){message=tr("recording_unavailable_this_channel_keeps_recordings_for_days", channel.archiveDays);return}
        pendingProgramme=null;message=tr("loading_recording")
        archiveLoadJob=lifecycleScope.launch {
            try{
                val session=withContext(Dispatchers.IO){IlookArchive.open(http,channel,p,target,now)}
                applyArchive(session,target,focus,playWhenReady,resetRecovery)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){message=if(e is UserError)e.message.orEmpty()else tr("could_not_load_the_recording_check_your_connection_and_try_again")}
        }
    }
    private fun returnToLive(channel:Channel?=playing) {
        if(channel==null)return
        cancelArchiveLoad();archiveInitialPosition=null;pendingProgramme=null;archiveEnded=false;seekDraft=null;archiveSession=null
        player.stop();tune(channel,false);screen="player";controls=true;hudFocus="player-pause";interaction++
    }
    private fun seekArchive(position:Long) {
        val a=archiveSession
        val now=System.currentTimeMillis()
        if(a==null){
            val c=playing?:return;val p=programme(c.id)?:return
            startArchive(c,p,position.coerceAtMost((now-p.start-1000).coerceAtLeast(0)),focus=hudFocus)
        }else{
            val target=a.clamp(position,now)
            if(a.programme.end>now&&target>=a.available(now)-1000){returnToLive();return}
            if(a.remote){
                if(target>=a.duration){pausePlayback();archivePosition=a.duration;archiveEnded=true;controls=true;return}
                startArchive(a.channel,a.programme,target,hudFocus,player.playWhenReady)
            }else{archiveEnded=false;player.seekTo(target);archivePosition=target}
        }
        interaction++
    }
    private fun seekBy(delta:Long) {
        val p=archiveSession?.programme?:programme(playing?.id)?:return
        val position=if(archiveSession!=null)currentArchivePosition() else System.currentTimeMillis()-p.start
        seekArchive(position+delta)
    }
    private fun archiveSourceAvailable()=playing?.let{archiveSupported(it)}==true
    private fun hudKeys():List<String> = buildList {
        val p=archiveSession?.programme?:programme(playing?.id)
        val position=if(archiveSession!=null)currentArchivePosition() else if(p!=null)System.currentTimeMillis()-p.start else 0
        if(archiveSourceAvailable()&&p!=null&&position>0)add("player-rewind")
        add("player-pause")
        if(archiveSession!=null&&position<archiveSession!!.available(System.currentTimeMillis())-1000)add("player-forward")
        add("player-subtitles");add("player-favourite")
        add("player-buffer");add("player-live")
    }
    private fun hudAction(key:String) {
        interaction++
        when(key){
            "player-pause"->togglePlayback()
            "player-rewind"->seekBy(-60000)
            "player-forward"->seekBy(60000)
            "player-live"->if(archiveSession!=null||!player.isPlaying)returnToLive()
            "player-buffer"->{diagnosticsOpen=!diagnosticsOpen;controls=true}
            "player-timeline"->{
                if(seekDraft!=null){val target=seekDraft!!;seekDraft=null;seekArchive(target)}
                else{val p=archiveSession?.programme?:programme(playing?.id)?:return;seekDraft=if(archiveSession!=null)currentArchivePosition() else (System.currentTimeMillis()-p.start).coerceAtLeast(0)}
            }
            "player-subtitles"->work{change{it.copy(subtitles=!it.subtitles)};applyTracks()}
            "player-favourite"->playing?.id?.let{id->persist{it.copy(favourites=if(id in it.favourites)it.favourites-id else it.favourites+id)}}
        }
    }
    @Composable private fun ArchiveDialogs(){
        val pending=pendingProgramme
        if(pending!=null){
            val first=remember{FocusRequester()};val supported=archiveSupported(pending.first)
            LaunchedEffect(Unit){first.requestFocus()}
            AlertDialog(modifier=Modifier.semantics{testTagsAsResourceId=true},containerColor=T.surface,onDismissRequest={pendingProgramme=null;interaction++},title={Text(tr("this_programme_is_live_now"))},
                text={Text(tr("watch_from_the_beginning")+if(!supported)tr("catch_up_is_not_available_for_this_channel_yet")else "")},
                confirmButton={TextButton(onClick={startArchive(pending.first,pending.second)},enabled=supported,modifier=Modifier.testTag("archive-start").then(if(supported)Modifier.focusRequester(first)else Modifier)){Text(tr("watch_from_start"))}},
                dismissButton={TextButton(onClick={returnToLive(pending.first)},modifier=Modifier.testTag("archive-watch-live").then(if(!supported)Modifier.focusRequester(first)else Modifier)){Text(tr("watch_live"))}})
        }
        val a=archiveSession
        if(archiveEnded&&a!=null){
            val next=epgRows[a.channel.id].orEmpty().firstOrNull{it.start>=a.programme.end&&it.start<System.currentTimeMillis()}
            val first=remember{FocusRequester()};LaunchedEffect(Unit){first.requestFocus()}
            AlertDialog(modifier=Modifier.semantics{testTagsAsResourceId=true},containerColor=T.surface,onDismissRequest={archiveEnded=false;interaction++},title={Text(tr("programme_finished"))},
                text={Text(next?.let{tr("next", it.title)}?:tr("the_next_programme_is_not_available_yet"))},
                confirmButton={TextButton(onClick={next?.let{startArchive(a.channel,it)}},enabled=next!=null,modifier=Modifier.testTag("archive-next").then(if(next!=null)Modifier.focusRequester(first)else Modifier)){Text(tr("next_programme"))}},
                dismissButton={TextButton(onClick={returnToLive()},modifier=Modifier.testTag("archive-end-live").then(if(next==null)Modifier.focusRequester(first)else Modifier)){Text(tr("go_live"))}})
        }
    }
    private fun openPanel(page:String) {
        if(page=="updates"&&screen!=page)appUpdates.beginVisit()
        if(screen in listOf("guide","player")){backgroundScreen=screen;panelStack.clear()}
        else if(screen!=page)panelStack.add(screen)
        screen=page;error="";message=""
    }
    private fun closePanels(){panelStack.clear();screen=backgroundScreen;error="";message="";interaction++}
    private fun selectGroup(value:String){recentOrder.enter(value==BuiltinGroups.RECENT,data.history);guideTime=0;group=value;query="";selectedChannel=filtered().firstOrNull()?.id.orEmpty();guideZone=0;backgroundScreen="guide";panelStack.clear();screen="guide"}
    private fun showMainMenu(){
        panelStack.clear();screen="main";error="";message=""
    }
    private fun back() {
        if(applying)return
        if(archiveLoadJob?.isActive==true){cancelArchiveLoad();message="";return}
        if(pendingProgramme!=null){pendingProgramme=null;interaction++;return}
        if(archiveEnded){archiveEnded=false;interaction++;return}
        if(seekDraft!=null){seekDraft=null;interaction++;return}
        if(busy){message=tr("wait_for_the_operation_to_finish");return}
        when(screen){
            "epgOrder" -> work{
                change{it.copy(epgUrl="",epgSources=epgOrder)};epgRevision++;movingEpg=null
                screen=if(panelStack.isNotEmpty())panelStack.removeAt(panelStack.lastIndex)else "epg"
            }
            "player" -> if(controls){controls=false}else{screen="guide"}
            "guide" -> if(guideZone==1||guideTime!=0L){guideZone=0;guideTime=0L}else openPanel("groups")
            "browse" -> {screen="player";controls=true;interaction++}
            "details" -> {guideTime=0L;guideZone=0;browseZone=0;screen=detailsReturn;interaction++}
            "groups" -> showMainMenu()
            "main" -> {
                if(exitConfirmation.press(SystemClock.elapsedRealtime()))finish()
                else {exitHint=true;exitHintRevision++}
            }
            else -> {
                if(screen=="restorePassword"){password="";restoreBytes?.fill(0);restoreBytes=null}
                if(screen=="restoreConfirm")restoreState=null
                if(screen=="source")candidate=null
                if(screen=="phone")closePhone()
                screen=if(panelStack.isNotEmpty())panelStack.removeAt(panelStack.lastIndex)else backgroundScreen
                error="";message="";interaction++
            }
        }
    }
    // Physical TV remote bridge only. Gesture/system Back remains registered by
    // BackHandler in App(); forward key-up to that same AndroidX dispatcher.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event:AndroidKey):Boolean {
        // A lazy row can leave the focus tree during scroll. Receive EPG arrows
        // at the Activity so those frames do not swallow remote input.
        if(screen=="epg"&&epgNavigate?.invoke(event)==true)return true
        return super.dispatchKeyEvent(event)
    }
    private fun handleRemote(event:AndroidKey):Boolean {
        // Compose focus navigation consumes remote Back as an "exit focus group"
        // before Activity BackHandler. Own both edges so it navigates exactly once.
        // An open IME still gets its pre-IME Back first and closes normally.
        if(event.keyCode==AndroidKey.KEYCODE_BACK){
            if(event.action==AndroidKey.ACTION_UP&&!event.isCanceled)onBackPressedDispatcher.onBackPressed()
            return true
        }
        if(event.action==AndroidKey.ACTION_DOWN && event.keyCode!=AndroidKey.KEYCODE_BACK){exitConfirmation.reset();exitHint=false}
        if(screen in listOf("groups","main","search") && !(screen=="search"&&searchEditing) && event.keyCode in listOf(AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT)){
            if(event.action==AndroidKey.ACTION_DOWN && event.repeatCount==0){
                if(screen=="groups"){
                    if(event.keyCode==AndroidKey.KEYCODE_DPAD_LEFT)showMainMenu()else closePanels()
                } else if(event.keyCode==AndroidKey.KEYCODE_DPAD_RIGHT){screen="groups"}
            }
            return true
        }
        if(pendingProgramme!=null||archiveEnded)return false
        if(screen=="player"&&event.keyCode in listOf(AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT,AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN)){
            if(event.action==AndroidKey.ACTION_DOWN){
                val horizontal=event.keyCode in listOf(AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT)
                val delta=if(event.keyCode==AndroidKey.KEYCODE_DPAD_LEFT||event.keyCode==AndroidKey.KEYCODE_DPAD_UP)-1 else 1
                if(!controls){
                    if(archiveSession==null&&!horizontal){val all=filtered();val i=all.indexOfFirst{it.id==playing?.id};if(all.isNotEmpty())tune(all[Math.floorMod(i+delta,all.size)],false)}
                    controls=true;hudFocus="player-pause"
                }else if(seekDraft!=null){
                    if(horizontal){val p=archiveSession?.programme?:programme(playing?.id);if(p!=null)seekDraft=(seekDraft!!+delta*300000L).coerceIn(0,(minOf(p.end,System.currentTimeMillis())-p.start).coerceAtLeast(0))}
                }else if(horizontal){
                    if(hudFocus!="player-timeline"){
                        val keys=hudKeys();val index=keys.indexOf(hudFocus).coerceAtLeast(0)
                        if(delta<0&&index==0){selectedChannel=playing?.id?:filtered().firstOrNull()?.id.orEmpty();guideTime=0L;browseZone=0;panelStack.clear();screen="browse";controls=false}
                        else hudFocus=keys[(index+delta).coerceIn(0,keys.lastIndex)]
                    }
                }else if(delta<0&&hudFocus in listOf("player-live","player-buffer"))hudFocus=hudUtilityFocus
                else if(delta>0&&hudFocus in listOf("player-subtitles","player-favourite")){
                    hudUtilityFocus=hudFocus
                    hudFocus=if(hudFocus=="player-subtitles")"player-buffer"else"player-live"
                }else if(delta<0&&archiveSourceAvailable()&&(archiveSession?.programme?:programme(playing?.id))!=null)hudFocus="player-timeline"
                else if(delta>0&&hudFocus !in listOf("player-live","player-buffer"))hudFocus="player-pause"
                interaction++
            }
            return true
        }
        if(event.action==AndroidKey.ACTION_DOWN && ::player.isInitialized) {
            when(event.keyCode){
                AndroidKey.KEYCODE_MEDIA_STOP -> {cancelArchiveLoad();cancelRecovery();player.stop();playback="stopped";screen="guide";controls=true;return true}
                AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE -> {togglePlayback();controls=true;interaction++;return true}
                AndroidKey.KEYCODE_MEDIA_PLAY -> {if(player.playerError!=null)retryPlayback()else resumePlayback();controls=true;interaction++;return true}
                AndroidKey.KEYCODE_MEDIA_PAUSE -> {cancelArchiveLoad();cancelRecovery();pausePlayback();controls=true;return true}
            }
            if(screen=="player" && event.keyCode in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN,AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT,AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER)) {
                interaction++
                if(!controls) {
                    if(event.keyCode in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN)) {
                        val all=filtered();val i=all.indexOfFirst{it.id==playing?.id};if(all.isNotEmpty())tune(all[(i+(if(event.keyCode==AndroidKey.KEYCODE_DPAD_DOWN)1 else -1)+all.size)%all.size],false)
                    }
                    controls=true;return true
                }
            }
        }
        return false
    }
    private fun newPhonePlaylist(){
        newSource();sourceMode="url";panelFocus["source"]="source-phone";openPhone("source")
    }
    private fun newSource(p:Playlist?=null){editingPlaylist=p?.id;sourceName=p?.name.orEmpty();sourceUrl=p?.source.orEmpty();sourceMode=if(p==null)""else if(p.source.isEmpty())"file"else"url";candidate=p;chosenName=if(p?.source=="")tr("saved_file")else"";panelFocus.remove("source");openPanel("source")}
    private fun parseSource(bytes:ByteArray,base:String="",id:String=editingPlaylist?:UUID.randomUUID().toString(),name:String=sourceName):Playlist {
        val text=SourceIO.text(bytes);val channels=M3u.parse(text,id,base)
        return Playlist(id,name.trim().ifEmpty{tr("my_playlist")}.take(120),base,text,channels)
    }
    private suspend fun loadPlaylistUrl():Playlist {
        val url=SourceIO.url(sourceUrl)
        val id=editingPlaylist?:UUID.randomUUID().toString();val name=sourceName
        return withContext(Dispatchers.IO){http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if(!response.isSuccessful)throw UserError(tr("the_source_returned_http_check_the_url", response.code))
            val bytes=SourceIO.read(response.body?.byteStream()?:throw UserError(tr("empty_source_response")),SourceIO.MAX_PLAYLIST)
            parseSource(bytes,url,id,name)
        }}
    }
    private fun validateUrl(){work {candidate=loadPlaylistUrl()}}
    private fun replacePlaylists(old:AppState,lists:List<Playlist>):AppState {
        val ids=lists.flatMap{it.channels}.map{it.id}.toSet()
        return old.copy(playlists=lists,favourites=old.favourites.intersect(ids),history=old.history.filter{it in ids},last=old.last.takeIf{it in ids}.orEmpty(),epgBindings=old.epgBindings.filterKeys{it in ids},epgOffsets=old.epgOffsets.filterKeys{it in ids},playlistEpgSources=old.playlistEpgSources.filterKeys{id->lists.any{it.id==id}})
    }
    private fun addSource(){work {
        val p=candidate?:when(sourceMode){
            "url"->loadPlaylistUrl()
            "file"->throw UserError(tr("choose_an_m3u_or_m3u8_file_first"))
            else->throw UserError(tr("choose_a_playlist_url_or_file"))
        }
        val saved=p.copy(name=sourceName.trim().ifEmpty{p.name}.take(120))
        val lists=if(editingPlaylist==null)data.playlists+saved else data.playlists.map{if(it.id==editingPlaylist)saved else it}
        if(lists.size>15 || lists.sumOf{it.channels.size}>50000)throw UserError(tr("limit_reached_15_playlists_or_50_000_channels"))
        change{replacePlaylists(it,lists)};epgRevision++
        if(playing?.id !in data.channels.map{it.id}){cancelRecovery();player.stop();playing=null}
        candidate=null;recentOrder.enter(false,data.history);group=BuiltinGroups.ALL;query="";selectedChannel=p.channels.firstOrNull()?.id.orEmpty();panelStack.clear();screen="guide";message=tr("playlist_saved_channels", p.channels.size)
    }}
    private fun deletePlaylist(){work{
        change{replacePlaylists(it,it.playlists.filter{p->p.id!=selectedPlaylist})};epgRevision++
        if(playing?.id !in data.channels.map{it.id}){cancelRecovery();player.stop();playing=null}
        selectedChannel=data.channels.firstOrNull()?.id.orEmpty();group=BuiltinGroups.ALL;query="";recentOrder.enter(false,data.history)
        returnTo("playlists")
    }}
    private fun chooseDocument(purpose:String) {
        filePurpose=purpose;previousScreen=screen;error=""
        openPanel("files");listFiles(if(purpose=="backup" && Build.VERSION.SDK_INT>=30 && Environment.isExternalStorageManager())Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)else imports)
    }
    private fun listFiles(dir:File){folder=dir;files=dir.listFiles()?.filter{it.isDirectory || if(filePurpose=="playlist")it.extension.lowercase() in listOf("m3u","m3u8")else it.extension.lowercase() in setOf("tvwavebackup",LegacyBackup.EXTENSION)}?.sortedWith(compareBy<File>{!it.isDirectory}.thenBy{it.name.lowercase()})?:emptyList()}
    private fun readDocument(uri:Uri){if(panelStack.lastOrNull()==previousScreen)panelStack.removeAt(panelStack.lastIndex);screen=previousScreen;work {
        val bytes=withContext(Dispatchers.IO){SourceIO.read(contentResolver.openInputStream(uri)?:throw UserError(tr("could_not_open_the_file")),if(filePurpose=="playlist")SourceIO.MAX_PLAYLIST else SourceIO.MAX_STATE+60)}
        if(filePurpose=="playlist"){candidate=withContext(Dispatchers.Default){parseSource(bytes)};chosenName=uri.lastPathSegment?.substringAfterLast('/')?:tr("file")}
        else {
            password="";restoreState=null
            if(BackupFile.needsPassword(bytes)){restoreBytes=bytes;openPanel("restorePassword")}
            else {restoreState=withContext(Dispatchers.Default){BackupFile.decode(bytes)};openPanel("restoreConfirm")}
        }
    }}
    private fun readFile(file:File){readDocument(Uri.fromFile(file));chosenName=file.name}
    private fun paste(){
        val manager=getSystemService(CLIPBOARD_SERVICE)as ClipboardManager
        val text=manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if(text.isBlank())error=tr("no_url_in_the_clipboard")else if(text.length>8192)error=tr("the_url_is_too_long")else{sourceUrl=text.trim();candidate=null;error=""}
    }
    private fun refresh(p:Playlist){work {
        if(p.source.isBlank())throw UserError(tr("this_local_playlist_is_stored_in_the_app_choose_a_new_file_to_update_i"))
        val updated=withContext(Dispatchers.IO){http.newCall(Request.Builder().url(p.source).build()).execute().use { r ->
            if(!r.isSuccessful)throw UserError(tr("the_source_returned_http", r.code))
            val raw=SourceIO.text(SourceIO.read(r.body!!.byteStream(),SourceIO.MAX_PLAYLIST));p.copy(raw=raw,channels=M3u.parse(raw,p.id,p.source))
        }}
        change{old -> val lists=old.playlists.map{if(it.id==p.id)updated else it};val ids=lists.flatMap{it.channels}.map{it.id}.toSet();replacePlaylists(old,lists)}
        epgRevision++
        message=tr("playlist_updated_channels", updated.channels.size)
    }}
    private fun startExport(){work {
        exportBytes=stateLock.withLock{val snapshot=data;withContext(Dispatchers.IO){BackupFile.encode(snapshot)}}
        val name="TVWave-${SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(Date())}.tvwavebackup"
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
        if(Build.VERSION.SDK_INT<29) {
            if(intent.resolveActivity(packageManager)==null)throw UserError(tr("this_device_needs_a_system_document_picker_to_save_backups"))
            exportPicker.launch(name)
        } else {
            val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/TVWave");put(MediaStore.MediaColumns.IS_PENDING,1)
            })?:throw UserError(tr("could_not_create_a_file_in_downloads"))
            writeAndVerify(uri)
            contentResolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            message=tr("backup_checked_and_saved_download_tvwave_copy_it_to_a_usb_drive_or_com", name)
        }
    }}
    private suspend fun writeAndVerify(uri:Uri) {
        val bytes=exportBytes?:throw UserError(tr("nothing_to_save"))
        try{withContext(Dispatchers.IO){
            contentResolver.openOutputStream(uri,"wt")?.use{it.write(bytes);it.flush()}?:throw UserError(tr("cannot_write_to_this_file"))
            val check=SourceIO.read(contentResolver.openInputStream(uri)?:throw UserError(tr("could_not_read_back_the_backup")),SourceIO.MAX_STATE+60)
            if(!check.contentEquals(bytes))throw UserError(tr("the_saved_file_did_not_pass_verification"))
        }}catch(e:Exception){runCatching{contentResolver.delete(uri,null,null)};throw e}
        finally{bytes.fill(0);exportBytes=null}
    }
    private fun saveExport(uri:Uri){work{writeAndVerify(uri);message=tr("backup_saved_and_verified_no_password_needed_to_restore_it")}}
    private fun decryptBackup(){work {
        val bytes=restoreBytes?:throw UserError(tr("choose_a_backup_file"));val pass=password.toCharArray()
        restoreState=try{withContext(Dispatchers.Default){val plain=BackupCrypto.decrypt(bytes,pass);try{StateCodec.decode(plain)}finally{plain.fill(0)}}}finally{pass.fill('\u0000')}
        password="";restoreBytes?.fill(0);restoreBytes=null;screen="restoreConfirm"
    }}
    private fun applyRestore(){val restored=restoreState?:return;fontSaveJob?.cancel();fontPreview=null;work {
        EpgUpdateService.cancelAndWait(this@MainActivity)
        epgFailures=emptyMap();epgStatus=""
        cancelRecovery();player.stop();playing=null
        change(resetCache=true){restored.copy(uiLanguage=restored.uiLanguage.ifEmpty{data.uiLanguage})};epgRevision++;loadFailed=false;restoreState=null;group=BuiltinGroups.ALL;query="";selectedChannel=restored.last.ifEmpty{restored.channels.firstOrNull()?.id.orEmpty()};panelStack.clear();screen="guide";message=tr("settings_and_playlists_restored");applyTracks()
    }}
    private fun finishSearch(){searchEditing=false;panelStack.clear();backgroundScreen="guide";screen="guide";guideZone=0;guideTime=0}
    private fun programme(id:String?,time:Long=nowTime)=epgRows[id].orEmpty().lastOrNull{it.start<=time&&time<it.end}
    private fun missingEpg(id:String?=selectedChannel):String {
        if(data.sources.isEmpty())return tr("no_programme_guide_added")
        val link=epgLinks[id]
        if(link==null)return if(epgStatuses.values.none{it.updated>0})tr("epg_has_not_been_downloaded_yet")else tr("no_matching_channel_in_the_epg")
        if(epgFailures.containsKey(link.source)&&epgRows[id].isNullOrEmpty())return tr("epg_update_failed_check_the_source")
        return tr("no_programme_for_this_time_in_the_source")
    }
    private fun timeLabel(time:Long)=SimpleDateFormat("HH:mm",L10n.locale).format(Date(time))
    private fun timeRange(p:Programme):String {
        val day=SimpleDateFormat("yyyyMMdd",Locale.ROOT)
        val prefix=if(day.format(Date(p.start))==day.format(Date(nowTime)))""else SimpleDateFormat("dd.MM ",Locale.ROOT).format(Date(p.start))
        return "${prefix}${timeLabel(p.start)} — ${timeLabel(p.end)}"
    }
    private fun timelineStart()=(guideTime.takeIf{it>0}?:nowTime)/1800000L*1800000L
    @Composable private fun TimelineHeader(modifier:Modifier){
        Row(modifier.clipToBounds()){
            repeat(6){i->Text(timeLabel(timelineStart()+i*1800000L),Modifier.weight(1f),color=T.muted,fontSize=T.micro,maxLines=1)}
        }
    }
    @Composable private fun ProgrammeCells(c:Channel,focused:Boolean,active:Boolean,modifier:Modifier){
        val from=timelineStart();val until=from+10800000L
        val entries=epgRows[c.id].orEmpty().filter{it.end>from&&it.start<until}
        val selected=programme(c.id,guideTime.takeIf{it>0}?:nowTime)
        BoxWithConstraints(modifier.clipToBounds().background(T.surface)){
            val width=maxWidth
            if(entries.isEmpty())Box(Modifier.fillMaxSize().then(focusSurface(focused)).padding(horizontal=T.small),contentAlignment=Alignment.CenterStart){Text(missingEpg(c.id),color=if(focused)T.onAccent else T.muted,fontSize=T.channel,maxLines=1,overflow=TextOverflow.Ellipsis)}
            for(p in entries){
                val x=width*((maxOf(p.start,from)-from).toFloat()/10800000L)
                val w=width*((minOf(p.end,until)-maxOf(p.start,from)).toFloat()/10800000L)
                val chosen=focused&&p.start==selected?.start
                Box(Modifier.offset(x=x).width(w).fillMaxHeight().then(focusSurface(chosen)).border(T.line,T.bg)
                    .archiveOutline(programmeHasArchive(c,p),if(chosen)T.onAccent else T.muted)
                    .focusProperties{canFocus=false}.clickable(enabled=active){selectedChannel=c.id;guideZone=1;guideTime=p.start;openProgrammeDetails()}.padding(horizontal=T.small,vertical=T.tiny),contentAlignment=Alignment.CenterStart){
                    Text("${timeLabel(p.start)}  ${p.title}",color=if(chosen)T.onAccent else T.text,fontSize=T.channelName,lineHeight=T.channelLine,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
            if(nowTime in from until until)Box(Modifier.offset(x=width*((nowTime-from).toFloat()/10800000L)).width(T.focusLine).fillMaxHeight().background(T.danger))
        }
    }
    private fun returnTo(page:String){while(panelStack.isNotEmpty()){if(panelStack.removeAt(panelStack.lastIndex)==page)break};screen=page;panelFocus.remove(page)}
    private fun updateEpg(requested:List<EpgSource> = data.sources){
        if(requested.isEmpty())return
        try {
            EpgUpdateService.enqueue(this,requested.map{it.id})
            epgUpdating=true;epgStatus=tr("downloading_programme_guide")
        } catch(e:Exception){epgUpdating=false;epgStatus=tr("could_not_start_the_update_try_again_in_settings")}
    }
    private fun editEpg(source:EpgSource?=null){epgSourceId=source?.id.orEmpty();epgName=source?.name.orEmpty();epgDraft=source?.url.orEmpty();openPanel("epgEdit")}
    private fun saveEpg(){work{
        val url=SourceIO.url(epgDraft)
        if(data.sources.any{it.url==url&&it.id!=epgSourceId})throw UserError(tr("this_source_has_already_been_added"))
        if(epgSourceId.isEmpty()&&data.sources.size>=10)throw UserError(tr("you_can_add_up_to_10_epg_sources"))
        val reload=data.sources.find{it.id==epgSourceId}?.url!=url
        val source=EpgSource(epgSourceId.ifEmpty{UUID.randomUUID().toString()},epgName.trim().ifEmpty{java.net.URI(url).host}.take(120),url)
        change{it.copy(epgUrl="",epgSources=if(epgSourceId.isEmpty())it.sources+source else it.sources.map{old->if(old.id==epgSourceId)source else old})}
        epgRevision++;returnTo("epg");if(reload)updateEpg(listOf(source))
    }}
    private fun deleteEpg(){work{
        change{it.copy(epgUrl="",epgSources=it.sources.filter{e->e.id!=epgSourceId},epgBindings=it.epgBindings.filterValues{b->b.source!=epgSourceId},playlistEpgSources=it.playlistEpgSources.filterValues{source->source!=epgSourceId})}
        epgRevision++;returnTo("epg")
    }}
    @Composable private fun ColumnScope.EpgSettings(){
        val list=rememberLazyListState()
        val keys=data.sources.map{"epg-source:${it.id}"}+
            (if(data.sources.size<10)listOf("epg-add")else emptyList())+listOf("epg-matches")+
            (if(data.sources.isNotEmpty())listOf("epg-update")else emptyList())+listOf("epg-interval")
        val requests=remember(keys){keys.associateWith{FocusRequester()}}
        var target by remember(keys){mutableStateOf<Int?>(null)}
        val lastIndex=keys.lastIndex-1
        val lastAction=requests.getValue(keys[lastIndex])
        val intervalAction=requests.getValue("epg-interval")
        val toInterval=Modifier.focusRequester(lastAction).focusProperties{down=intervalAction}
        // Count arrows against the logical destination, even while LazyColumn is
        // composing a row. Spatial focus search can drop keys at a viewport edge.
        val navigate: (AndroidKey)->Boolean=remember(keys,busy){ { event->
            if(event.keyCode !in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN))false
            else {
                if(event.action==AndroidKey.ACTION_DOWN&&!busy){
                    val current=target?:keys.indexOf(panelFocus["epg"].let{if(it=="epg-cancel")"epg-update"else it}).coerceAtLeast(0)
                    target=(current+if(event.keyCode==AndroidKey.KEYCODE_DPAD_DOWN)1 else -1).coerceIn(keys.indices)
                }
                true
            }
        } }
        DisposableEffect(navigate){
            epgNavigate=navigate
            onDispose{if(epgNavigate===navigate)epgNavigate=null}
        }
        LaunchedEffect(target,keys,screen){
            if(screen!="epg")return@LaunchedEffect
            val destination=target?:return@LaunchedEffect
            if(destination<keys.lastIndex&&list.layoutInfo.visibleItemsInfo.none{it.index==destination})list.scrollToItem(destination)
            repeat(5){
                withFrameNanos{}
                // A newer key may arrive before recomposition cancels this effect.
                if(target!=destination)return@LaunchedEffect
                if(runCatching{requests.getValue(keys[destination]).requestFocus()}.getOrDefault(false)){
                    target=null
                    return@LaunchedEffect
                }
            }
        }
        LaunchedEffect(epgUpdating,epgStatus.isNotEmpty()){
            // A newly visible status reduces the viewport. Materialize the focused action
            // before replacing Update with Cancel (and back), including at 130% text.
            if(screen=="epg"&&panelFocus["epg"] in listOf("epg-update","epg-cancel")){
                list.scrollToItem(lastIndex)
                withFrameNanos{}
                if(screen=="epg"&&panelFocus["epg"] in listOf("epg-update","epg-cancel"))runCatching{lastAction.requestFocus()}
            }
        }
        if(epgStatus.isNotEmpty())Text(epgStatus,color=T.muted,fontSize=T.micro,maxLines=2,overflow=TextOverflow.Ellipsis)
        LazyColumn(Modifier.weight(1f).padding(T.focusGap),state=list,verticalArrangement=Arrangement.spacedBy(T.tiny)){
            items(data.sources,key={it.id}){source->
                val status=epgStatuses[source.id]
                val summary=if(status==null||status.updated==0L)tr("not_downloaded_yet")else tr("updated", SimpleDateFormat("dd.MM HH:mm",L10n.locale).format(Date(status.updated)))
                Setting(source.name,"epg-source:${source.id}",modifier=Modifier.focusRequester(requests.getValue("epg-source:${source.id}")),subtitle=summary+if(updatingEpgId==source.id)tr("updating")else if(source.id in epgFailures)tr("update_failed")else if(status?.tomorrow==true)tr("programmes_for_tomorrow_available")else tr("no_data_for_tomorrow")){epgSourceId=source.id;openPanel("epgMenu")}
            }
            if(data.sources.size<10)item{Setting(tr("add"),"epg-add",modifier=Modifier.focusRequester(requests.getValue("epg-add"))){editEpg()}}
            item{Setting(tr("channel_matching"),"epg-matches",subtitle=tr("of_manual", epgLinks.size, data.channels.size, data.epgBindings.size),modifier=if(data.sources.isEmpty())toInterval else Modifier.focusRequester(requests.getValue("epg-matches"))){matchQuery="";openPanel("epgMatchChannels")}}
            if(data.sources.isNotEmpty())item{
                if(epgUpdating)Action(tr("cancel_update"),key="epg-cancel",modifier=toInterval,enabled=!busy){lifecycleScope.launch{EpgUpdateService.cancelAndWait(this@MainActivity)}}
                else Action(tr("update_now"),key="epg-update",modifier=toInterval,enabled=!busy){updateEpg()}
            }
        }
        HorizontalDivider(color=T.border)
        Setting(tr("update_every"),"epg-interval",if(data.epgAutoUpdate)tr("h", data.epgIntervalHours)else tr("off"),modifier=Modifier.focusRequester(intervalAction)){openPanel("epgInterval")}
    }
    @Composable private fun EpgEditor(){
        Field(tr("name_optional"),epgName){epgName=it.take(120)}
        Field(tr("xmltv_xmltv_gz_url"),epgDraft){epgDraft=it.take(8192)}
        Action(tr("paste"),key="epg-paste"){
            val value=(getSystemService(CLIPBOARD_SERVICE)as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if(value.isBlank())error=tr("no_url_in_the_clipboard")else epgDraft=value.trim().take(8192)
        }
        Action(tr("save"),key="epg-save",enabled=!busy){saveEpg()}
        Action(tr("from_phone_via_qr"),key="epg-phone",enabled=!busy){openPhone("epgEdit")}
    }
    @Composable private fun EpgMenu(){data.sources.find{it.id==epgSourceId}?.let{source->
        Caption(source.name)
        val status=epgStatuses[source.id]
        Caption(if(status==null||status.updated==0L)tr("not_downloaded_yet")else tr("last_successful_update_programmes", SimpleDateFormat("dd.MM HH:mm",L10n.locale).format(Date(status.updated)), status.programmes))
        if(status!=null&&status.until>0)Caption(tr("data_until", SimpleDateFormat("dd.MM HH:mm",L10n.locale).format(Date(status.until))))
        Caption(if(status?.tomorrow==true)tr("this_source_includes_programmes_for_tomorrow")else tr("this_source_has_no_programmes_for_tomorrow"))
        epgFailures[source.id]?.let{Caption(tr("error", it)+if((status?.updated?:0)>0)tr("the_previous_programme_guide_is_preserved")else"")}
        Setting(tr("change_order"),"epg-order"){epgOrder=data.sources.toList();movingEpg=null;openPanel("epgOrder")}
        Action(if(updatingEpgId==source.id)tr("updating_m146")else tr("update"),Modifier.fillMaxWidth(),key="epg-refresh-one",enabled=!epgUpdating,row=true){updateEpg(listOf(source))}
        Setting(tr("edit"),"epg-edit"){editEpg(source)}
        Action(tr("delete"),Modifier.fillMaxWidth(),key="epg-delete",enabled=!busy,row=true){openPanel("epgDelete")}
    }}
    @Composable private fun EpgOrderScreen(){
        Caption(tr("higher_sources_have_priority_ok_picks_up_or_releases_a_source_moves_it"))
        epgOrder.forEachIndexed{index,source->androidx.compose.runtime.key(source.id){
            val moving=movingEpg==source.id
            val bring=remember{androidx.compose.foundation.relocation.BringIntoViewRequester()}
            LaunchedEffect(index,moving){if(moving){withFrameNanos{};bring.bringIntoView()}}
            Setting("${index+1}. ${source.name}","epg-move:${source.id}",if(moving)"↕ ✓"else"↕",if(moving)tr("moving_ok_to_finish")else tr("ok_to_move"),Modifier.bringIntoViewRequester(bring).onPreviewKeyEvent{event->
                val key=event.nativeKeyEvent
                if(moving&&key.keyCode in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN)){
                    if(key.action==AndroidKey.ACTION_DOWN){
                        val from=epgOrder.indexOfFirst{it.id==source.id}
                        val to=(from+if(key.keyCode==AndroidKey.KEYCODE_DPAD_UP)-1 else 1).coerceIn(0,epgOrder.lastIndex)
                        if(from!=to)epgOrder=epgOrder.toMutableList().apply{add(to,removeAt(from))}
                    };true
                }else false
            }){movingEpg=if(moving)null else source.id}
        }}
    }
    private fun offsetLabel(minutes:Int):String=if(minutes==0)tr("no_offset")else tr("h_min", if(minutes>0)"+"else"−", kotlin.math.abs(minutes)/60, kotlin.math.abs(minutes)%60)
    private fun setOffset(minutes:Int){work{change{it.copy(epgOffsets=if(minutes==0)it.epgOffsets-matchTarget else it.epgOffsets+(matchTarget to minutes.coerceIn(-720,720)))};epgRevision++}}
    @Composable private fun EpgOffsetScreen(){
        val minutes=data.epgOffsets[matchTarget]?:0
        Caption(data.channels.find{it.id==matchTarget}?.name.orEmpty())
        Text(offsetLabel(minutes),Modifier.testTag("offset-value"),color=T.text,fontSize=T.title)
        Caption(tr("adjusts_this_channel_only_1_hour_moves_a_programme_from_12_00_to_13_00"))
        Action(tr("30_minutes_earlier"),key="offset-minus",enabled=!busy&&minutes> -720){setOffset(minutes-30)}
        Action(tr("no_offset"),key="offset-reset",enabled=!busy){setOffset(0)}
        Action(tr("30_minutes_later"),key="offset-plus",enabled=!busy&&minutes<720){setOffset(minutes+30)}
    }
    @Composable private fun PlaylistEpgScreen(){
        val chosen=data.playlistEpgSources[epgPlaylistId]
        Caption(tr("automatic_uses_source_order_a_selected_source_applies_to_this_playlist"))
        Setting(tr("automatic"),"playlist-epg-auto",if(chosen==null)"✓"else""){work{change{it.copy(playlistEpgSources=it.playlistEpgSources-epgPlaylistId)};epgRevision++;returnTo("playlistMenu")}}
        data.sources.forEach{source->Setting(source.name,"playlist-epg:${source.id}",if(chosen==source.id)"✓"else""){work{change{it.copy(playlistEpgSources=it.playlistEpgSources+(epgPlaylistId to source.id))};epgRevision++;returnTo("playlistMenu")}}}
        if(data.sources.isEmpty())Caption(tr("add_a_source_in_programme_guides_first"))
    }
    @Composable private fun ColumnScope.MatchChannels(){
        val channels=remember(data,matchQuery,unmatchedOnly,epgLinks){data.channels.filter{(!unmatchedOnly||it.id !in epgLinks)&&it.name.contains(matchQuery,true)}}
        val scroll=rememberLazyListState();val scope=rememberCoroutineScope()
        val search=remember{FocusRequester()};val first=remember{FocusRequester()}
        val keyboard=LocalSoftwareKeyboardController.current
        Setting(tr("show"),"match-filter",if(unmatchedOnly)tr("without_epg")else tr("all")){unmatchedOnly=!unmatchedOnly}
        Field(tr("find_channel"),matchQuery,modifier=Modifier.testTag("match-query").focusRequester(search).onPreviewKeyEvent{event->
            val key=event.nativeKeyEvent
            if(key.keyCode==AndroidKey.KEYCODE_DPAD_DOWN){
                if(key.action==AndroidKey.ACTION_DOWN&&key.repeatCount==0){keyboard?.hide();if(channels.isNotEmpty())scope.launch{scroll.scrollToItem(0);withFrameNanos{};runCatching{first.requestFocus()}}}
                true
            }else false
        }){matchQuery=it}
        Caption(tr("channels", channels.size))
        LaunchedEffect(matchQuery,unmatchedOnly){scroll.scrollToItem(0)}
        LazyColumn(Modifier.weight(1f).padding(T.focusGap),state=scroll,verticalArrangement=Arrangement.spacedBy(T.tiny)){
            itemsIndexed(channels,key={_,c->c.id}){i,c->Setting(c.name,"match:${c.id}",modifier=if(i==0)Modifier.focusRequester(first).focusProperties{up=search}else Modifier,subtitle=if(c.id in data.epgBindings)tr("set_manually")else if(c.id in epgLinks)tr("automatic")else tr("no_match")){matchTarget=c.id;openPanel("epgMatchSource")}}
            if(channels.isEmpty())item{Caption(tr("no_channels_found"))}
        }
    }
    @Composable private fun MatchSources(){
        Caption(data.channels.find{it.id==matchTarget}?.name.orEmpty())
        Setting(tr("time_offset"),"epg-offset",offsetLabel(data.epgOffsets[matchTarget]?:0)){openPanel("epgOffset")}
        Setting(tr("automatic"),"match-auto",if(matchTarget !in data.epgBindings)"✓"else""){work{change{it.copy(epgBindings=it.epgBindings-matchTarget)};epgRevision++;returnTo("epgMatchChannels")}}
        data.sources.forEach{source->Setting(source.name,"match-source:${source.id}"){matchSource=source.id;catalogQuery="";openPanel("epgMatchCatalog")}}
        if(data.sources.isEmpty())Caption(tr("add_a_programme_guide_source_first"))
    }
    @Composable private fun ColumnScope.MatchCatalog(){
        val catalog=remember(matchCatalog,catalogQuery){matchCatalog.filter{it.id.contains(catalogQuery,true)||it.names.any{n->n.contains(catalogQuery,true)}}}
        val scroll=rememberLazyListState();val scope=rememberCoroutineScope()
        val search=remember{FocusRequester()};val first=remember{FocusRequester()}
        val keyboard=LocalSoftwareKeyboardController.current
        Notice()
        Caption(data.channels.find{it.id==matchTarget}?.name.orEmpty())
        Field(tr("search_epg"),catalogQuery,autoFocus=true,modifier=Modifier.testTag("catalog-input").focusRequester(search).onPreviewKeyEvent{event->
            val key=event.nativeKeyEvent
            if(key.keyCode==AndroidKey.KEYCODE_DPAD_DOWN){
                if(key.action==AndroidKey.ACTION_DOWN&&key.repeatCount==0){keyboard?.hide();if(catalog.isNotEmpty())scope.launch{scroll.scrollToItem(0);withFrameNanos{};runCatching{first.requestFocus()}}}
                true
            }else false
        }){catalogQuery=it}
        Caption(tr("matches", catalog.size))
        LaunchedEffect(catalogQuery){scroll.scrollToItem(0)}
        LazyColumn(Modifier.weight(1f).padding(T.focusGap),state=scroll,verticalArrangement=Arrangement.spacedBy(T.tiny)){
            itemsIndexed(catalog,key={_,entry->entry.id}){i,entry->Setting(entry.names.firstOrNull()?:entry.id,"catalog:${entry.id}",if(data.epgBindings[matchTarget]==EpgBinding(matchSource,entry.id))"✓"else"",modifier=if(i==0)Modifier.focusRequester(first).focusProperties{up=search}else Modifier,subtitle=entry.id){work{
                change{it.copy(epgBindings=it.epgBindings+(matchTarget to EpgBinding(matchSource,entry.id)))}
                val hasSchedule=withContext(Dispatchers.IO){repository.schedule(matchTarget,EpgRetention.cutoff(System.currentTimeMillis()),System.currentTimeMillis()+3*86400000L,1).isNotEmpty()}
                if(!hasSchedule)updateEpg(data.sources.filter{it.id==matchSource})
                epgRevision++;returnTo("epgMatchChannels")
            }}}
            if(catalog.isEmpty())item{Caption(if(matchCatalog.isEmpty())tr("the_catalogue_will_appear_after_updating_this_source")else tr("no_matches"))}
        }
    }

    private fun filtered():List<Channel> = data.channels.filter {
        (when(group){BuiltinGroups.ALL->true;BuiltinGroups.FAVOURITES->it.id in data.favourites;BuiltinGroups.RECENT->it.id in recentOrder.ids;else->it.group==group}) && it.name.contains(query,ignoreCase=true)
    }.let { if(group==BuiltinGroups.RECENT)it.sortedBy{c->recentOrder.ids.indexOf(c.id)}else it }

    private fun guideKey(event:AndroidKey):Boolean {
        if(screen!="guide" || data.playlists.isEmpty())return false
        val keys=listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN,AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT,AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER)
        if(event.keyCode !in keys)return false
        if(event.action==AndroidKey.ACTION_DOWN){
            val visible=filtered();val index=visible.indexOfFirst{it.id==selectedChannel}.coerceAtLeast(0)
            when(event.keyCode){
                AndroidKey.KEYCODE_DPAD_UP -> visible.getOrNull((index-1).coerceAtLeast(0))?.let{selectedChannel=it.id}
                AndroidKey.KEYCODE_DPAD_DOWN -> visible.getOrNull((index+1).coerceAtMost((visible.size-1).coerceAtLeast(0)))?.let{selectedChannel=it.id}
                AndroidKey.KEYCODE_DPAD_LEFT -> if(guideZone==1){if(epgRows[selectedChannel].isNullOrEmpty())guideZone=0 else moveProgramme(-1)}else if(event.repeatCount==0)openPanel("groups")
                AndroidKey.KEYCODE_DPAD_RIGHT -> if(guideZone==0){guideZone=1;guideTime=0L}else moveProgramme(1)
                else -> if(event.repeatCount==0){if(guideZone==1)openProgrammeDetails()else visible.getOrNull(index)?.let{tune(it)}}
            }
        }
        return true
    }
    private fun openProgrammeDetails(){
        detailsReturn=if(screen=="browse")"browse"else"guide"
        screen="details";panelStack.clear();error="";message=""
    }
    private fun selectedProgramme():Programme?=programme(selectedChannel,guideTime.takeIf{it>0}?:nowTime)
    private fun moveProgramme(direction:Int){
        val rows=epgRows[selectedChannel].orEmpty();val time=guideTime.takeIf{it>0}?:nowTime
        val current=selectedProgramme()
        val next=if(direction>0)rows.firstOrNull{it.start>(current?.start?:time)}else rows.lastOrNull{it.start<(current?.start?:time)}
        next?.let{guideTime=it.start}
    }
    private fun programmeSpan(p:Programme):String{
        val date=SimpleDateFormat("EEE, d MMM HH:mm",L10n.locale)
        val day=SimpleDateFormat("yyyyMMdd",Locale.ROOT)
        return date.format(Date(p.start))+" — "+if(day.format(Date(p.start))==day.format(Date(p.end)))timeLabel(p.end)else date.format(Date(p.end))
    }
    private fun programmeStatus(p:Programme)=when{p.start>nowTime->tr("up_next");p.end<=nowTime->tr("finished");else->tr("live_now")}
    private fun panelTitle(page:String)=when(page){
        "updates"->tr("update_title")
        "epgOrder"->tr("guide_priority");"epgOffset"->tr("epg_time_offset");"playlistEpg"->tr("programme_guide_source");"phone"->tr("url_from_phone");"settings"->tr("settings");"playlists"->tr("playlists");"source"->if(editingPlaylist==null)tr("new_playlist")else tr("edit_playlist");"files"->tr("choose_file")
        "language"->tr("language");"appearance"->tr("appearance");"playback"->tr("playback");"audio"->tr("audio");"remote"->tr("remote_control")
        "startup"->tr("on_startup");"backup"->tr("backup");"restorePassword"->tr("legacy_backup");"restoreConfirm"->tr("restore_data")
        "more"->tr("more");"search"->tr("search");"epg"->tr("programme_guides")
        "epgEdit"->if(epgSourceId.isEmpty())tr("add_programme_guide")else tr("edit_programme_guide")
        "epgMenu"->tr("epg_source");"epgDelete"->tr("delete_source");"epgInterval"->tr("update_every")
        "epgMatchChannels"->tr("channel_matching");"epgMatchSource"->tr("choose_source");"epgMatchCatalog"->tr("epg_channel")
        "playlistMenu"->tr("playlist");"playlistDelete"->tr("delete_playlist");else->"TV Wave"
    }
    private fun defaultFocus(page:String=screen):String=when(page){
        "updates"->"update-action"
        "epgOrder"->epgOrder.firstOrNull()?.let{"epg-move:${it.id}"}.orEmpty();"epgOffset"->"offset-reset";"playlistEpg"->"playlist-epg-auto";"phone"->"phone-close";"settings"->"playlists";"playlists"->data.playlists.firstOrNull()?.let{"playlist:${it.id}"}?:"add";"source"->if(candidate!=null)"source-add"else "source-url"
        "files"->files.firstOrNull()?.let{"file:${it.path}"}?:"file-up"
        "language"->"language:${L10n.language}";"appearance"->"font-size";"playback","audio"->"audio-language";"startup"->"resume";"backup"->if(loadFailed)"restore"else "export"
        "restorePassword"->"legacy-open";"restoreConfirm"->"restore-apply";"more"->"pause";"search"->"search-input"
        "epg"->data.sources.firstOrNull()?.let{"epg-source:${it.id}"}?:"epg-add"
        "epgEdit"->"epg-paste";"epgMenu"->"epg-edit";"epgDelete"->"delete-cancel";"epgInterval"->"interval:${data.epgIntervalHours}"
        "epgMatchChannels"->"match-filter";"epgMatchSource"->"match-auto";"epgMatchCatalog"->"catalog-input"
        "playlistMenu"->"playlist-edit";"playlistDelete"->"delete-cancel";"main"->"main-tv";"groups"->"group:${group}";"player"->"player-favourite";"guide"->if(loadFailed)"restore"else"add";else->"panel-back"
    }
    private fun focusSurface(focused:Boolean,fill:androidx.compose.ui.graphics.Color=T.surface):Modifier {
        return Modifier.background(if(focused)T.accent else fill,RoundedCornerShape(T.radius)).drawWithContent{
            drawContent()
            if(focused){
                val line=T.focusLine.toPx();val gap=T.focusGap.toPx();val inset=line+gap/2
                drawRoundRect(T.text,Offset(line/2,line/2),Size(size.width-line,size.height-line),CornerRadius(T.buttonRadius.toPx()),style=Stroke(line))
                drawRoundRect(T.bg,Offset(inset,inset),Size(size.width-inset*2,size.height-inset*2),CornerRadius(T.radius.toPx()),style=Stroke(gap))
            }
        }
    }
    @Composable private fun App(){
        val density=LocalDensity.current
        if(loaded&&!loadFailed&&data.uiLanguage.isEmpty()){
            MaterialTheme(colorScheme=darkColorScheme(background=T.bg,surface=T.surface,onBackground=T.text,onSurface=T.text,primary=T.accent,onPrimary=T.onAccent)){
                BackHandler { finish() }
                Column(Modifier.fillMaxSize().background(T.bg).padding(T.safe).semantics{testTagsAsResourceId=true},verticalArrangement=Arrangement.spacedBy(T.gap)){
                    Text("TV Wave",color=T.accent,fontSize=T.title)
                    Text(tr("choose_language"),color=T.text,fontSize=T.title)
                    Caption(tr("language_intro"))
                    LanguageOptions(firstLaunch=true)
                    Notice()
                }
            }
            return
        }
        CompositionLocalProvider(LocalDensity provides Density(density.density,density.fontScale*fontPercent/100f)){AppContent()}
    }
    @Composable private fun AppContent(){
        val rootFocus=remember{FocusRequester()}
        LaunchedEffect(loaded){
            while(loaded&&!loadFailed){
                if(!epgUpdating&&!busy)try{
                    stateLock.withLock{
                        val snapshot=data
                        val changed=withContext(Dispatchers.IO){repository.maintain(snapshot)}
                        if(changed)epgRevision++
                    }
                }catch(e:Exception){if(e is CancellationException)throw e;android.util.Log.w("TVWave","Cache maintenance deferred",e)}
                delay(3600000)
            }
        }
        LaunchedEffect(group){recentOrder.enter(group==BuiltinGroups.RECENT,data.history)}
        LaunchedEffect(loaded,data.sources,data.epgBindings,data.playlistEpgSources,epgRevision,nowTime/60000){
            if(loaded)withContext(Dispatchers.IO){
                val statuses=data.sources.associate{it.id to EpgCache.status(repository.readableDatabase,it)}
                withContext(Dispatchers.Main){epgStatuses=statuses}
            }
        }
        LaunchedEffect(loaded,data.sources,data.epgBindings,data.playlistEpgSources,epgRevision){
            if(loaded)withContext(Dispatchers.IO){
                val links=EpgCache.links(repository.readableDatabase);val logos=EpgCache.logos(repository.readableDatabase)
                withContext(Dispatchers.Main){epgLinks=links;epgLogos=logos}
            }
        }
        LaunchedEffect(loaded,data.sources,data.epgAutoUpdate,data.epgIntervalHours){
            while(loaded&&data.sources.isNotEmpty()&&data.epgAutoUpdate){
                if(!epgUpdating&&lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)){val pending=data.sources.filter{val updated=withContext(Dispatchers.IO){EpgCache.lastUpdated(repository.readableDatabase,it)};updated==0L||System.currentTimeMillis()-updated>data.epgIntervalHours*3600000L};if(pending.isNotEmpty())updateEpg(pending)}
                delay(60000)
            }
        }
        LaunchedEffect(matchSource,epgRevision){matchCatalog=if(matchSource.isEmpty())emptyList()else withContext(Dispatchers.IO){EpgCache.catalog(repository.readableDatabase,matchSource)}}
        LaunchedEffect(loaded,data.sources,data.epgBindings,data.epgOffsets,data.playlistEpgSources,epgRevision,selectedChannel,group,query,nowTime/60000,guideTime/3600000,playing?.id){
            if(loaded){
                val list=filtered();val index=list.indexOfFirst{it.id==selectedChannel}.coerceAtLeast(0)
                val ids=(list.drop((index-6).coerceAtLeast(0)).take(14).map{it.id}+listOfNotNull(playing?.id)).distinct()
                val from=EpgRetention.cutoff(nowTime)
                epgRows=withContext(Dispatchers.IO){
                    if(data.sources.isEmpty())emptyMap()
                    else ids.associateWith{id->
                        if(id==selectedChannel)repository.schedule(id,from,nowTime+3*86400000L,10000,data.epgOffsets[id]?:0)
                        else {
                            val pivot=guideTime.takeIf{it>0}?:nowTime
                            val window=repository.schedule(id,maxOf(from,pivot-3600000L),pivot+4*3600000L,300,data.epgOffsets[id]?:0)
                            val live=if(id==playing?.id&&kotlin.math.abs(pivot-nowTime)>3600000L)repository.schedule(id,nowTime-3600000L,nowTime+4*3600000L,300,data.epgOffsets[id]?:0)else emptyList()
                            (window+live).distinctBy{it.start}.sortedBy{it.start}
                        }
                    }
                }
            }
        }
        LaunchedEffect(archiveSession){
            while(archiveSession!=null){
                archivePosition=currentArchivePosition()
                val a=archiveSession
                if(a?.remote==true&&archivePosition>=a.duration&&player.playWhenReady){
                    archiveEnded=true;player.pause();controls=true
                }
                delay(250)
            }
        }
        LaunchedEffect(screen,if(screen=="player")controls else false){rootFocus.requestFocus()}
        LaunchedEffect(L10n.language){while(true){nowTime=System.currentTimeMillis();clockText=SimpleDateFormat("HH:mm",L10n.locale).format(Date());guideDate=SimpleDateFormat("EEE, d MMMM · HH:mm",L10n.locale).format(Date());delay(15000)}}
        MaterialTheme(colorScheme=darkColorScheme(background=T.bg,surface=T.surface,onBackground=T.text,onSurface=T.text,primary=T.accent,onPrimary=T.onAccent,error=T.danger)) {
            BackHandler{back()}
            ArchiveDialogs()
            LaunchedEffect(exitHintRevision){if(exitHint){delay(ExitConfirmation.WINDOW_MS);exitHint=false;exitConfirmation.reset()}}
            LaunchedEffect(busy){busySeconds=0;while(busy){delay(1000);busySeconds++}}
            LaunchedEffect(screen,controls,playback,interaction,seekDraft,pendingProgramme,archiveEnded,diagnosticsOpen){if(screen=="player"&&controls&&playback=="playing"&&seekDraft==null&&pendingProgramme==null&&!archiveEnded){delay(3000);controls=false}}
            LaunchedEffect(message){if(message.isNotEmpty()&&screen in listOf("guide","player","details","browse")){delay(4500);message=""}}
            Box(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.clipToBounds().background(T.bg).onPreviewKeyEvent{handleRemote(it.nativeKeyEvent)}.focusRequester(rootFocus).focusable()){
                if(!loaded)Text(tr(if(optimizingGuide)"optimizing_programme_storage" else "opening_tv_wave"),Modifier.align(Alignment.Center).padding(T.safe).widthIn(max=640.dp),color=T.text,fontSize=T.title,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                else {
                    val base=when(screen){"browse"->"player";"details"->"details";"guide","player"->screen;else->backgroundScreen}
                    val overlay=screen !in listOf("guide","player","browse","details")
                    val leftDepth=when{screen in listOf("main","search")->2;screen=="groups"->1;!overlay->0;panelStack.any{it in listOf("main","search")}->2;"groups" in panelStack->1;else->0}
                    val expandedMainWidth=T.leftMainWidth*maxOf(1f,LocalDensity.current.fontScale)
                    val mainWidth=when(leftDepth){2->expandedMainWidth;1->T.leftRailWidth;else->0.dp}
                    var lastMenuExpanded by remember{mutableStateOf(false)}
                    val menuExpanded=if(leftDepth>0)leftDepth==2 else lastMenuExpanded
                    SideEffect{if(leftDepth>0)lastMenuExpanded=leftDepth==2}
                    val guideShift by animateDpAsState(if(leftDepth>0)mainWidth+T.leftGroupWidth*maxOf(1f,LocalDensity.current.fontScale)else 0.dp,tween(T.menuMotionMs),label="guide-shift")
                    val groupShift by animateDpAsState(mainWidth,tween(T.menuMotionMs),label="group-shift")
                    BoxWithConstraints(Modifier.fillMaxSize().offset(x=guideShift).then(if(overlay)Modifier.clearAndSetSemantics{}else Modifier)){
                        val full=base=="player"
                        val motion=tween<androidx.compose.ui.unit.Dp>(T.videoMotionMs,easing=FastOutSlowInEasing)
                        val x by animateDpAsState(if(full)0.dp else T.safe,motion,label="video-x")
                        val y by animateDpAsState(if(full)0.dp else T.previewTop,motion,label="video-y")
                        val width by animateDpAsState(if(full)maxWidth else (maxWidth-T.safe*2)*.34f,motion,label="video-width")
                        val height by animateDpAsState(if(full)maxHeight else T.previewHeight,motion,label="video-height")
                        val radius by animateDpAsState(if(full)0.dp else T.previewRadius,motion,label="video-radius")
                        if(base=="details")ProgrammeDetails()else if(!full)Guide(active=!overlay)
                        // One PlayerView and one video surface survive preview/fullscreen transitions.
                        if(playing!=null)Box(Modifier.offset(x=x,y=y).size(width,height).clip(RoundedCornerShape(radius))){
                            Video(Modifier.fillMaxSize())
                            if(!full)Text(tr("watching", playing?.name),Modifier.align(Alignment.BottomStart).padding(T.small).background(T.bg,RoundedCornerShape(T.radius)).padding(horizontal=T.small,vertical=T.tiny),color=T.text,fontSize=T.micro,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        if(screen=="browse")ChannelBrowser()else if(full)PlayerScreen(active=!overlay)
                    }
                    AnimatedVisibility(visible=leftDepth>0,enter=slideInHorizontally(tween(T.menuMotionMs)){-it},exit=slideOutHorizontally(tween(T.menuMotionMs)){-it}){
                        CompositionLocalProvider(LocalPanelPage provides "groups"){Box(Modifier.fillMaxHeight().offset(x=groupShift).width((T.leftGroupWidth*maxOf(1f,LocalDensity.current.fontScale)))){GroupsDrawer()}}
                    }
                    AnimatedVisibility(visible=leftDepth>0,enter=slideInHorizontally(tween(T.menuMotionMs)){-it},exit=slideOutHorizontally(tween(T.menuMotionMs)){-it}){
                        val page=if(screen=="search")"search"else"main"
                        CompositionLocalProvider(LocalPanelPage provides page){Box(Modifier.fillMaxHeight().width(groupShift).clipToBounds()){
                            // Animate only the clipped viewport. Text is always measured at its
                            // final width, so letters never reflow into a vertical column.
                            Box(Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start,unbounded=true)
                                .width(if(menuExpanded)expandedMainWidth else T.leftRailWidth)){
                                if(menuExpanded)MainMenu()
                                else MenuRail(if(screen=="settings"||"settings" in panelStack)"settings"else if(group==BuiltinGroups.FAVOURITES)"favourites"else"tv"){key->panelFocus["main"]=key;showMainMenu()}
                            }
                        }}
                    }
                    val right=overlay&&screen !in listOf("groups","main","search")
                    var lastPanel by remember{mutableStateOf("settings")}
                    SideEffect{if(right)lastPanel=screen}
                    val panel=if(right)screen else lastPanel
                    AnimatedVisibility(visible=right,enter=fadeIn(tween(T.menuMotionMs)),exit=fadeOut(tween(T.menuMotionMs))){Box(Modifier.fillMaxSize().background(T.veil))}
                    AnimatedVisibility(visible=right,enter=slideInHorizontally(tween(T.menuMotionMs)){it}+fadeIn(tween(T.menuMotionMs)),exit=slideOutHorizontally(tween(T.menuMotionMs)){it}+fadeOut(tween(T.menuMotionMs))){
                        AnimatedContent(targetState=panel,transitionSpec={fadeIn(tween(T.menuMotionMs)) togetherWith fadeOut(tween(T.menuMotionMs))},label="panel-page"){page->
                            CompositionLocalProvider(LocalPanelPage provides page){Box(Modifier.fillMaxSize().focusProperties{canFocus=right&&page==screen}){Drawer()}}
                        }
                    }
                    if(exitHint || !overlay&&message.isNotEmpty()) {
                        Text(if(exitHint)tr("press_back_twice_to_exit")else message,Modifier.align(Alignment.BottomCenter).padding(bottom=T.footerSpace).background(T.raised,RoundedCornerShape(T.radius)).padding(T.gap),color=T.text,fontSize=T.caption)
                    }
                }
            }
        }
    }
    @Composable private fun ChannelLogo(channel:Channel,color:Color,border:Color){
        val url=epgLogos[channel.id].orEmpty()
        androidx.compose.runtime.key(channel.id,url){
            val bitmap by produceState<Bitmap?>(null,url){if(url.isNotEmpty())value=logoLoader.load(url)}
            Box(Modifier.size(T.channelLogo),contentAlignment=Alignment.Center){
                if(bitmap!=null)Image(bitmap!!.asImageBitmap(),null,Modifier.fillMaxSize().testTag("channel-logo:${channel.id}").padding(T.logoInset),contentScale=androidx.compose.ui.layout.ContentScale.Fit)
                else Box(Modifier.size(T.mono).testTag("channel-letter:${channel.id}").border(T.line,border,RoundedCornerShape(T.radius)),contentAlignment=Alignment.Center){Text(channel.name.take(1).uppercase(),color=color,fontSize=T.micro)}
            }
        }
    }
    @Composable private fun Caption(text:String,modifier:Modifier=Modifier){Text(text,modifier,color=T.muted,fontSize=T.caption,lineHeight=(T.caption)*1.3f)}
    @Composable private fun Notice(){
        if(busy)Text(if(screen=="restorePassword")tr("opening_legacy_backup_s", busySeconds)else tr("please_wait_s", busySeconds),color=T.accent,fontSize=T.caption)
        if(error.isNotEmpty())Text(error,color=T.danger,fontSize=T.caption)
        if(message.isNotEmpty())Text(message,color=T.success,fontSize=T.caption)
    }
    @Composable private fun Action(label:String,modifier:Modifier=Modifier,key:String=label,enabled:Boolean=true,row:Boolean=false,value:String="",subtitle:String="",icon:String?=null,onClick:()->Unit){
        val interactionSource=remember{MutableInteractionSource()}
        var focused by remember{mutableStateOf(false)}
        val requester=remember{FocusRequester()};val page=LocalPanelPage.current?:screen
        val interactive=enabled && page==screen
        // Invalidating a checked playlist while typing must not pull focus out of the field.
        // Validation/file loading already restore focus when busy finishes (interactive changes).
        LaunchedEffect(page,folder,key,interactive,if(page=="epg")epgUpdating else false){
            val wanted=when{
                page=="epg"&&epgUpdating&&panelFocus[page]=="epg-update"->"epg-cancel"
                page=="epg"&&!epgUpdating&&panelFocus[page]=="epg-cancel"->"epg-update"
                page=="files"||page=="source"&&candidate!=null->defaultFocus(page)
                else->panelFocus[page]?:defaultFocus(page)
            }
            if(key==wanted&&interactive){
                // Lazy items and animated panels may be placed a frame later.
                repeat(5){delay(60);if(runCatching{requester.requestFocus()}.getOrDefault(false))return@LaunchedEffect}
            }
        }
        val fg=if(focused)T.onAccent else if(enabled)T.text else T.muted
        val contentSizedMenuRow=row&&page in listOf("groups","main","search")
        Row(modifier.testTag(key).focusRequester(requester).onFocusChanged{focused=it.isFocused;if(it.isFocused)panelFocus[page]=key}
            .then(focusSurface(focused,if(row)T.surface else T.raised))
            .then(if(row)Modifier else Modifier.border(T.line,if(focused)T.accent else T.inputBorder,RoundedCornerShape(T.radius)))
            .clickable(enabled=interactive,interactionSource=interactionSource,indication=null,role=Role.Button,onClick=onClick)
            // Menu spacing comes from fixed padding, not a minimum row height:
            // smaller text pulls subsequent entries upward instead of leaving empty space.
            .then(if(contentSizedMenuRow)Modifier else Modifier.heightIn(min=if(row)T.settingRow*maxOf(1f,LocalDensity.current.fontScale)else T.actionHeight))
            .padding(horizontal=if(row)T.gap else T.actionPad,vertical=T.small),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
            if(icon!=null)MenuIcon(icon,fg,Modifier.size(T.menuIcon*if(contentSizedMenuRow)minOf(1f,LocalDensity.current.fontScale)else 1f))
            Column(if(row)Modifier.weight(1f)else Modifier){Text(label,color=fg,fontSize=T.body,fontWeight=FontWeight.Normal,lineHeight=(T.body)*1.3f,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(subtitle.isNotEmpty())Text(subtitle,color=if(focused)T.onAccent else T.muted,fontSize=T.micro,maxLines=2,overflow=TextOverflow.Ellipsis)}
            if(value.isNotEmpty())Text(value,color=if(focused)T.onAccent else T.muted,fontSize=T.caption)
        }
    }
    @Composable private fun Setting(label:String,key:String,value:String="›",subtitle:String="",modifier:Modifier=Modifier,onClick:()->Unit){Action(label,modifier.fillMaxWidth(),key,enabled=!busy,row=true,value=value,subtitle=subtitle,icon=key.removePrefix("main-").takeIf{key in listOf("main-search","main-tv","main-favourites","main-settings")},onClick=onClick)}
    @Composable private fun Field(label:String,value:String,secret:Boolean=false,autoFocus:Boolean=false,onSubmit:(()->Unit)?=null,onFocus:(Boolean)->Unit={},modifier:Modifier=Modifier,onChange:(String)->Unit){
        val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
        var focused by remember{mutableStateOf(false)}
        var editing by remember{mutableStateOf(autoFocus)}
        var consumeOkUp by remember{mutableStateOf(false)}
        val requester=remember{FocusRequester()}
        LaunchedEffect(autoFocus){if(autoFocus){delay(120);requester.requestFocus()}}
        LaunchedEffect(editing){if(editing){delay(160);keyboard?.show()}}
        val fg=if(focused)T.onAccent else T.text
        Column(verticalArrangement=Arrangement.spacedBy(T.tiny)){
            Text(label,color=T.text,fontSize=T.caption,lineHeight=T.caption*1.3f)
            BasicTextField(value=value,onValueChange=onChange,singleLine=true,readOnly=!editing,enabled=!busy,keyboardOptions=KeyboardOptions(showKeyboardOnFocus=autoFocus,imeAction=if(autoFocus)ImeAction.Search else ImeAction.Done),
                keyboardActions=KeyboardActions(onSearch={keyboard?.hide();onSubmit?.invoke()},onDone={keyboard?.hide();onSubmit?.invoke()}),
                textStyle=androidx.compose.ui.text.TextStyle(fontSize=T.description,lineHeight=T.description*1.3f,color=fg),cursorBrush=SolidColor(fg),
                modifier=modifier.fillMaxWidth().focusRequester(requester).onFocusChanged{if(focused&&!it.isFocused)editing=false;focused=it.isFocused;onFocus(it.isFocused)}.onPreviewKeyEvent{e->val event=e.nativeKeyEvent
                    when{
                        event.keyCode in listOf(AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER)&&event.action==AndroidKey.ACTION_DOWN&&!editing->{consumeOkUp=true;editing=true;true}
                        event.keyCode in listOf(AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER)&&event.action==AndroidKey.ACTION_UP&&consumeOkUp->{consumeOkUp=false;true}
                        event.action==AndroidKey.ACTION_DOWN&&event.keyCode in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN)->{keyboard?.hide();focus.moveFocus(if(event.keyCode==AndroidKey.KEYCODE_DPAD_DOWN)FocusDirection.Down else FocusDirection.Up);true}
                        else->false
                    }}
                    .semantics{contentDescription=label},
                visualTransformation=if(secret)PasswordVisualTransformation()else VisualTransformation.None,
                decorationBox={inner->Box(Modifier.fillMaxWidth().then(focusSurface(focused,T.bg)).border(T.line,if(focused)T.accent else T.inputBorder,RoundedCornerShape(T.radius)).heightIn(min=T.actionHeight).padding(horizontal=T.small,vertical=T.small),contentAlignment=Alignment.CenterStart){inner()}})
        }
    }
    @Composable private fun Video(modifier:Modifier){
        AndroidView(factory={ctx->(LayoutInflater.from(ctx).inflate(if(android.os.Build.HARDWARE.contains("amlogic",ignoreCase=true))R.layout.video_surface_native else R.layout.video_surface,null) as PlayerView).apply{useController=false;this.player=this@MainActivity.player;isFocusable=false;isFocusableInTouchMode=false;setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)}},modifier=modifier,onRelease={it.player=null})
    }
    @Composable private fun Guide(active:Boolean){
        val visible=remember(data,group,query){filtered()};val list=rememberLazyListState();val root=remember{FocusRequester()}
        val emptyAction=remember{FocusRequester()}
        val inputMode=androidx.compose.ui.platform.LocalInputModeManager.current
        val windowFocused=LocalWindowInfo.current.isWindowFocused
        val empty=data.playlists.isEmpty()||loadFailed
        LaunchedEffect(active,loaded,empty,loadFailed,windowFocused){
            if(active&&loaded&&empty&&windowFocused){
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                repeat(5){delay(60);if(runCatching{emptyAction.requestFocus()}.getOrDefault(false))return@LaunchedEffect}
            }
        }
        val index=visible.indexOfFirst{it.id==selectedChannel}.coerceAtLeast(0);val selected=visible.getOrNull(index)
        LaunchedEffect(active,loaded){if(active&&loaded&&data.playlists.isNotEmpty()){delay(80);root.requestFocus()}}
        val rowHeight=(T.row+T.small)*maxOf(1f,LocalDensity.current.fontScale)
        val numberMeasurer=androidx.compose.ui.text.rememberTextMeasurer()
        val numberStyle=androidx.compose.ui.text.TextStyle(fontSize=T.channelName,lineHeight=T.channelLine)
        val numberWidth=maxOf(T.channelNumber,with(LocalDensity.current){
            numberMeasurer.measure("8".repeat(visible.size.toString().length),numberStyle,maxLines=1).size.width.toDp()
        })
        BoxWithConstraints(Modifier.fillMaxSize().testTag("guide").semantics{stateDescription=if(guideZone==0)tr("channel_now")else if(guideTime==0L)tr("programmes_now")else tr("programmes_another_time")}.focusRequester(root).onPreviewKeyEvent{guideKey(it.nativeKeyEvent)}.focusable(enabled=active&&data.playlists.isNotEmpty())){
            val count=((maxHeight-T.tableTop-T.footerSpace-T.headHeight)/(rowHeight+T.rowGap)).toInt().coerceAtLeast(1)
            LaunchedEffect(index,group,query,count){
                if(index<list.firstVisibleItemIndex)list.scrollToItem(index)
                else if(index>=list.firstVisibleItemIndex+count)list.scrollToItem((index-count+1).coerceAtLeast(0))
            }
            val contentWidth=maxWidth-T.safe*2;val previewWidth=contentWidth*.34f
            val channelWidth=if(fontPercent>=115)T.largeChannelWidth else T.channelWidth
            Row(Modifier.fillMaxWidth().padding(horizontal=T.safe).offset(y=T.top),horizontalArrangement=Arrangement.SpaceBetween){Caption(tr("brand_name"),Modifier.testTag("guide-brand"));Caption(guideDate)}
            if(data.playlists.isEmpty()||loadFailed){
                Column(Modifier.align(Alignment.Center).padding(T.safe),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(T.pad)){
                    Text(if(loadFailed)tr("data_recovery")else tr("your_channels_belong_here"),color=T.text,fontSize=T.display,fontWeight=FontWeight.SemiBold)
                    Caption(if(loadFailed)error else tr("add_a_playlist_by_url_file_or_qr_code_from_your_phone"))
                    Row(horizontalArrangement=Arrangement.spacedBy(T.gap)){
                        if(!loadFailed)Action(tr("add_playlist"),Modifier.focusRequester(emptyAction),key="add",enabled=active){newSource()}
                        Action(tr("restore_backup"),if(loadFailed)Modifier.focusRequester(emptyAction)else Modifier,key="restore",enabled=active){openPanel("backup")}
                    }
                    if(!loadFailed)Row(horizontalArrangement=Arrangement.spacedBy(T.gap)){
                        Action(tr("from_phone_via_qr"),key="welcome-phone",enabled=active&&!busy){newPhonePlaylist()}
                        Action(tr("settings"),key="welcome-settings",enabled=active){openPanel("settings")}
                    }
                }
            }else{
                Box(Modifier.offset(x=T.safe,y=T.previewTop).width(previewWidth).height(T.previewHeight).clip(RoundedCornerShape(T.previewRadius)).background(T.surface)){
                    if(playing==null)Caption(tr("choose_a_channel"),Modifier.align(Alignment.Center))
                    if(playing!=null)Text(tr("watching", playing?.name),Modifier.align(Alignment.BottomStart).padding(T.small).background(T.bg,RoundedCornerShape(T.radius)).padding(horizontal=T.small,vertical=T.tiny),color=T.text,fontSize=T.micro,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Column(Modifier.offset(x=T.safe+previewWidth+T.pad,y=T.previewTop+T.tiny).width(contentWidth-previewWidth-T.pad).height(T.previewHeight),verticalArrangement=Arrangement.spacedBy(T.small)){
                    val programme=programme(selected?.id,guideTime.takeIf{it>0}?:nowTime)
                    Text("${selected?.name?:group} · ${programme?.let{timeRange(it)}?:tr("now")}",color=T.muted,fontSize=T.caption,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(programme?.title?:missingEpg(selected?.id),modifier=Modifier.testTag("guide-programme-title"),color=T.text,fontSize=T.title,lineHeight=(T.title)*1.2f,fontWeight=FontWeight.SemiBold,maxLines=if(fontPercent>110)1 else 2,overflow=TextOverflow.Ellipsis)
                    Text(programme?.description?.ifEmpty{tr("no_description")}?:if(data.sources.isEmpty())tr("add_xmltv_in_programme_guide_settings")else tr("no_programme_for_this_channel_at_the_selected_time"),modifier=Modifier.weight(1f,fill=false),color=T.muted,fontSize=T.description,maxLines=3,overflow=TextOverflow.Ellipsis)
                    Text(when(playback){"buffering"->tr("connecting_m225");"error"->recoveryStatus.ifEmpty{playbackError};"paused"->tr("pause");else->tr("live_tv")},Modifier.background(T.raised,RoundedCornerShape(T.radius)).padding(horizontal=T.small,vertical=T.tiny),color=if(playback=="error")T.danger else T.text,fontSize=T.micro)
                }
                // Leave room for the focus outline to the left of the preview/number baseline.
                Column(Modifier.fillMaxWidth().padding(start=T.safe-T.small,end=T.safe).padding(top=T.tableTop,bottom=T.footerSpace).fillMaxHeight()){
                    Row(Modifier.fillMaxWidth().padding(start=T.small).height(T.headHeight),verticalAlignment=Alignment.CenterVertically){
                        Text("${BuiltinGroups.label(group)} · ${visible.size}",Modifier.width(channelWidth),color=T.text,fontSize=T.caption,maxLines=1,overflow=TextOverflow.Ellipsis)
                        TimelineHeader(Modifier.weight(1f).padding(start=T.channelGap))
                    }
                    if(visible.isEmpty())Caption(tr("no_channels_here_yet_choose_another_group"),Modifier.padding(top=T.pad))
                    LazyColumn(state=list,modifier=Modifier.weight(1f).padding(vertical=T.focusGap),verticalArrangement=Arrangement.spacedBy(T.rowGap)){
                        itemsIndexed(visible,key={_,c->c.id}){i,c->
                            val chosen=i==index;val cellFocused=chosen&&active
                            Row(Modifier.fillMaxWidth().height(rowHeight),horizontalArrangement=Arrangement.spacedBy(T.channelGap)){
                                Row(Modifier.width(channelWidth+T.small).fillMaxHeight().then(focusSurface(cellFocused&&guideZone==0,T.bg))
                                    .focusProperties{canFocus=false}.clickable(enabled=active){selectedChannel=c.id;guideZone=0;tune(c)}.padding(start=T.small,end=T.tiny),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.channelContentGap)){
                                    val color=if(cellFocused&&guideZone==0)T.onAccent else T.text
                                    Text("${i+1}",Modifier.width(numberWidth),color=color,fontSize=T.channelName,lineHeight=T.channelLine,maxLines=1,textAlign=androidx.compose.ui.text.style.TextAlign.Start)
                                    ChannelLogo(c,color,if(cellFocused&&guideZone==0)T.onAccent else T.inputBorder)
                                    Text(c.name,Modifier.weight(1f),color=color,fontSize=T.channelName,lineHeight=T.channelLine,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    if(channelArchiveDays(c)>0)ArchiveIcon(color,Modifier.size(18.dp).testTag("channel-archive:${i}"))
                                    Box(Modifier.width(12.dp),contentAlignment=Alignment.Center){
                                        if(c.id==playing?.id||c.id in data.favourites)Text(if(c.id==playing?.id)"●"else"★",color=color,fontSize=T.micro)
                                    }
                                }
                                ProgrammeCells(c,cellFocused&&guideZone==1,active,Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=T.safe).padding(bottom=T.top),horizontalArrangement=Arrangement.SpaceBetween){
                    Caption(if(guideZone==1)tr("programmes_ok_details_back_now")else tr("groups_ok", if(selected?.id==playing?.id)tr("full_screen")else tr("watch")))
                    Caption(tr("settings_are_in_the_left_menu"))
                }
            }
        }
    }
    @Composable private fun BoxScope.GroupsDrawer(){
        val groups=remember(data){listOf(BuiltinGroups.ALL,BuiltinGroups.FAVOURITES,BuiltinGroups.RECENT)+data.channels.map{it.group}.distinct()}
        val state=rememberLazyListState(initialFirstVisibleItemIndex=groups.indexOf(group).coerceAtLeast(0))
        Column(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth().background(T.surface).padding(start=T.pad,end=T.pad,top=T.drawerTop,bottom=T.drawerBottom),verticalArrangement=Arrangement.spacedBy(T.gap)){
            Text(tr("groups"),color=T.text,fontSize=T.title,fontWeight=FontWeight.SemiBold)
            LazyColumn(state=state,modifier=Modifier.weight(1f).padding(T.focusGap),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                items(groups){g->Setting(BuiltinGroups.label(g),"group:${g}",if(group==g)"✓"else""){selectGroup(g)}}
            }
            Caption(tr("main_menu_channels"))
        }
    }
    @Composable private fun BoxScope.MainMenu(){
        Column(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth().background(T.surface)
            .padding(start=T.pad,end=T.pad,top=T.drawerTop,bottom=T.drawerBottom),verticalArrangement=Arrangement.spacedBy(T.gap)){
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(T.focusGap),verticalArrangement=Arrangement.spacedBy(T.gap)){
                BrandHeader()
                if(screen=="search")Field(tr("channel_name"),query,autoFocus=true,onSubmit={finishSearch()},onFocus={searchEditing=it}){query=it;selectedChannel=filtered().firstOrNull()?.id.orEmpty()}
                Setting(tr("search"),"main-search"){group=BuiltinGroups.ALL;openPanel("search")}
                if(screen=="search"){
                    Caption(tr("found", filtered().size))
                    Setting(tr("view_results"),"search-results",""){finishSearch()}
                }else{
                    Setting(tr("tv"),"main-tv",if(group==BuiltinGroups.ALL)"✓"else""){selectGroup(BuiltinGroups.ALL)}
                    Setting(BuiltinGroups.label(BuiltinGroups.FAVOURITES),"main-favourites",if(group==BuiltinGroups.FAVOURITES)"✓"else""){selectGroup(BuiltinGroups.FAVOURITES)}
                }
            }
            HorizontalDivider(color=T.border)
            Setting(tr("settings"),"main-settings"){openPanel("settings")}
            Caption(tr("channel_groups"))
        }
    }
    @Composable private fun BoxScope.Drawer(){
        val page=LocalPanelPage.current?:screen
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if(page=="phone")T.phoneDrawerFraction else if(page=="updates").46f else T.drawerFraction).background(T.surface)
            .padding(start=T.pad,end=T.safe,top=T.drawerTop,bottom=T.drawerBottom),verticalArrangement=Arrangement.spacedBy(T.small)){
            Text(if(panelStack.isNotEmpty())panelTitle(panelStack.last()).uppercase(L10n.locale)else tr("tv_wave"),color=T.muted,fontSize=T.micro,lineHeight=T.micro*1.3f,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(panelTitle(page),color=T.text,fontSize=T.title,lineHeight=(T.title)*1.2f,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            // Adding directly can leave focus at the bottom of a long form.
            // Keep its progress/errors visible while the form itself scrolls.
            if(page=="source")Notice()
            Spacer(Modifier.height(T.tiny))
            when(page){
                "files" -> FilesScreen()
                "epg" -> EpgSettings()
                "epgMatchChannels" -> MatchChannels()
                "epgMatchCatalog" -> MatchCatalog()
                "updates" -> Column(Modifier.weight(1f).padding(T.focusGap),verticalArrangement=Arrangement.spacedBy(T.small)){UpdateScreen()}
                else -> Column(Modifier.weight(1f).verticalScroll(remember(page){androidx.compose.foundation.ScrollState(0)}).padding(T.focusGap),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                    if(page!="source")Notice()
                    when(page){
                        "epgOrder"->EpgOrderScreen();"epgOffset"->EpgOffsetScreen();"playlistEpg"->PlaylistEpgScreen();"phone"->PhoneScreen();"source"->SourceScreen();"settings"->SettingsScreen();"playlists"->PlaylistsScreen()
                        "epgEdit"->EpgEditor();"epgMenu"->EpgMenu();"epgMatchSource"->MatchSources()
                        "epgInterval"->listOf(6,12,24).forEach{hours->Setting(if(hours==24)tr("every_24_hours")else tr("every_hours", hours),"interval:${hours}",if(data.epgIntervalHours==hours)"✓"else""){work{change{it.copy(epgIntervalHours=hours,epgAutoUpdate=true)};returnTo("epg")}}}
                        "epgDelete"->{Caption(tr("this_source_and_its_matches_will_be_removed_your_playlists_will_be_kep"));Action(tr("cancel_m245"),key="delete-cancel"){back()};Action(tr("delete"),key="epg-delete-confirm",enabled=!busy){deleteEpg()}}
                        "playlistMenu"->PlaylistMenu()
                        "playlistDelete"->{Caption(tr("this_playlist_and_its_channels_will_be_removed_from_the_app"));Action(tr("cancel_m245"),key="delete-cancel"){back()};Action(tr("delete"),key="playlist-delete-confirm",enabled=!busy){deletePlaylist()}}
                        "appearance"->AppearanceScreen()
                        "language"->LanguageOptions()
                        "playback","audio"->AudioScreen()
                        "startup"->{Caption(tr("the_tv_guide_opens_on_startup_you_can_also_play_the_last_channel_in_th"));Setting(tr("last_channel"),"resume",if(data.resume)tr("on")else tr("off")){persist{it.copy(resume=!it.resume)}}}
                        "remote"->{Caption(tr("use_the_arrow_keys_ok_and_back_to_watch_tv"));Caption(tr("channels_select_ok_watch_groups_and_menu_programmes_earlier_later_ok_d"));Setting(tr("got_it"),"panel-back",""){back()}}
                        "more"->MoreScreen()
                        "backup"->BackupScreen();"restorePassword"->RestorePassword();"restoreConfirm"->RestoreConfirm()
                    }
                }
            }
            Caption(if(panelStack.isEmpty())tr("back_close")else tr("back_previous_screen"))
        }
    }
    @Composable private fun AppearanceScreen(){
        val languageFocus=remember{FocusRequester()}
        val requester=remember{FocusRequester()};var focused by remember{mutableStateOf(false)}
        LaunchedEffect(Unit){delay(80);requester.requestFocus()}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text(tr("font_size"),color=T.text,fontSize=T.body)
            Text("${fontPercent}%",Modifier.testTag("font-value"),color=T.accent,fontSize=T.body)
        }
        Slider(value=fontPercent.toFloat(),onValueChange={resizeText(it.roundToInt())},
            valueRange=60f..130f,steps=13,
            colors=SliderDefaults.colors(thumbColor=if(focused)T.onAccent else T.accent,activeTrackColor=if(focused)T.onAccent else T.accent,inactiveTrackColor=T.inputBorder,activeTickColor=if(focused)T.accent else T.onAccent,inactiveTickColor=T.surface),
            onValueChangeFinished={resizeText(fontPercent,true)},
            modifier=Modifier.fillMaxWidth().height(T.sliderHeight).testTag("font-size").focusRequester(requester)
                .onFocusChanged{focused=it.isFocused;if(it.isFocused)panelFocus["appearance"]="font-size"}
                .then(focusSurface(focused,T.raised))
                .onPreviewKeyEvent{event->val key=event.nativeKeyEvent
                    if(key.keyCode in listOf(AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT)){
                        if(key.action==AndroidKey.ACTION_DOWN)resizeText(fontPercent+if(key.keyCode==AndroidKey.KEYCODE_DPAD_RIGHT)5 else -5)
                        if(key.action==AndroidKey.ACTION_UP)resizeText(fontPercent,true)
                        true
                    }else if(key.keyCode in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN)){
                        if(key.keyCode==AndroidKey.KEYCODE_DPAD_DOWN&&key.action==AndroidKey.ACTION_DOWN)languageFocus.requestFocus()
                        true
                    }else false
                }.semantics{contentDescription=tr("font_size");stateDescription="${fontPercent}%"})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Caption(tr("font_smaller"))
            Caption(tr("font_larger"))
        }
        Setting(tr("language"),"appearance-language",L10n.available.firstOrNull{it.locale==L10n.language}?.nativeName?:"English",
            modifier=Modifier.focusRequester(languageFocus).onPreviewKeyEvent{event->
                if(event.nativeKeyEvent.keyCode==AndroidKey.KEYCODE_DPAD_UP){
                    if(event.nativeKeyEvent.action==AndroidKey.ACTION_DOWN)requester.requestFocus()
                    true
                }else false
            }){openPanel("language")}
    }
    @Composable private fun LanguageOptions(firstLaunch:Boolean=false){
        val first=remember{FocusRequester()}
        val windowFocused=LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused,L10n.language){
            if(windowFocused)repeat(5){
                withFrameNanos{}
                if(runCatching{first.requestFocus()}.getOrDefault(false))return@LaunchedEffect
                delay(60)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=350.dp).testTag("language-list"),verticalArrangement=Arrangement.spacedBy(T.small)){
            items(L10n.available,key={it.locale}){pack->
                Action(pack.nativeName+(if(!firstLaunch&&pack.locale==L10n.language)" ✓"else""),
                    Modifier.fillMaxWidth().focusProperties{canFocus=true}.then(if(pack.locale==L10n.language)Modifier.focusRequester(first)else Modifier),
                    key="language:${pack.locale}",enabled=!busy,row=true){
                    work{
                        change{it.copy(uiLanguage=pack.locale)}
                        error="";message=""
                        if(!firstLaunch){screen=if(panelStack.isNotEmpty())panelStack.removeAt(panelStack.lastIndex)else "appearance";panelFocus["appearance"]="appearance-language"}else {
                            // First-launch language actions share the guide page. Their
                            // focus key must not survive into the empty-playlist screen.
                            panelFocus.remove("guide")
                            panelStack.clear();screen="guide"
                        }
                    }
                }
            }
        }
    }
    private fun installAppUpdate() {
        if(updateInstallBusy)return
        updateInstallBusy=true
        lifecycleScope.launch {
            try {
                val file=appUpdates.verifiedFile()
                if(Build.VERSION.SDK_INT>=26&&!packageManager.canRequestPackageInstalls()) {
                    appUpdates.installerMessage("update_permission")
                    updatePermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))
                } else {
                    val uri=FileProvider.getUriForFile(this@MainActivity,"$packageName.updates",file)
                    @Suppress("DEPRECATION")
                    val intent=Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true)
                    intent.clipData=android.content.ClipData.newRawUri("TV Wave update",uri)
                    appUpdates.installerMessage(null)
                    updateInstaller.launch(intent)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(e is UpdateFailure)appUpdates.invalidateDownload(e.key)else appUpdates.installerMessage("update_no_installer")}
            finally {updateInstallBusy=false}
        }
    }
    @Composable private fun ColumnScope.UpdateScreen() {
        val state by appUpdates.state.collectAsState()
        val updateActionFocus=remember{FocusRequester()}
        val notesFocus=remember{FocusRequester()}
        val recheckFocus=remember{FocusRequester()}
        var recheckFocused by remember{mutableStateOf(false)}
        val inputMode=androidx.compose.ui.platform.LocalInputModeManager.current
        LaunchedEffect(state.phase,state.messageKey,screen,updateWindowFocusRevision) {
            if(state.phase==UpdatePhase.READY&&screen=="updates"&&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)&&appUpdates.consumeAutoInstall())installAppUpdate()
            if(screen=="updates"&&hasWindowFocus()) {
                // System installer/settings may return in touch mode. Restore TV remote focus.
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                panelFocus["updates"]="update-action"
                repeat(5) {
                    delay(60)
                    if(screen!="updates")return@LaunchedEffect
                    if(runCatching{updateActionFocus.requestFocus()}.getOrDefault(false))return@LaunchedEffect
                }
            }
        }
        Caption(tr("update_installed",BuildConfig.VERSION_NAME),Modifier.testTag("update-version"))
        state.release?.let{UpdateReleaseBanner(it)}
        val status=when(state.phase){
            UpdatePhase.CHECKING->tr("update_checking")
            UpdatePhase.CURRENT->tr("update_current")
            UpdatePhase.AVAILABLE->""
            UpdatePhase.DOWNLOADING->tr("update_downloading",state.percent)
            UpdatePhase.READY->tr(if(updateInstallBusy)"update_verifying"else"update_ready")
            else->""
        }
        if(status.isNotEmpty())Text(status,Modifier.testTag("update-status"),color=T.text,fontSize=T.body)
        if(state.phase==UpdatePhase.DOWNLOADING)LinearProgressIndicator(progress={state.percent/100f},modifier=Modifier.fillMaxWidth())
        state.messageKey?.let{Text(tr(it),Modifier.testTag("update-message"),color=if(state.phase==UpdatePhase.ERROR)T.danger else T.muted,fontSize=T.caption)}
        val running=state.phase in listOf(UpdatePhase.CHECKING,UpdatePhase.DOWNLOADING)
        val canRecheck=state.release!=null&&!running
        val label=when{
            running->tr("cancel_m245")
            state.phase==UpdatePhase.READY->tr("update_install")
            state.release!=null->tr("update_now_action")
            else->tr("update_check")
        }
        Action(label,modifier=Modifier.fillMaxWidth().focusRequester(updateActionFocus).focusProperties{if(canRecheck)down=recheckFocus else if(state.release?.notes?.isNotBlank()==true)down=notesFocus},key="update-action",enabled=!updateInstallBusy){
            when{
                running->appUpdates.cancel()
                state.phase==UpdatePhase.READY->installAppUpdate()
                state.release!=null->appUpdates.download()
                else->appUpdates.check()
            }
        }
        if(canRecheck)Text(tr("update_check"),Modifier.fillMaxWidth().testTag("update-recheck")
            .focusRequester(recheckFocus).focusProperties{up=updateActionFocus;if(state.release?.notes?.isNotBlank()==true)down=notesFocus}
            .onFocusChanged{recheckFocused=it.isFocused;if(it.isFocused)panelFocus["updates"]="update-recheck"}
            .then(focusSurface(recheckFocused,T.surface))
            .clickable(enabled=!updateInstallBusy,interactionSource=remember{MutableInteractionSource()},indication=null,role=Role.Button){appUpdates.check()}
            .heightIn(min=32.dp).padding(horizontal=T.small,vertical=T.tiny),
            color=if(recheckFocused)T.onAccent else T.text,fontSize=T.caption,lineHeight=T.caption*1.3f)
        state.release?.localizedNotes(L10n.language)?.takeIf{it.isNotBlank()}?.let { notes ->
            HorizontalDivider(color=T.border)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text(tr("update_notes"),color=T.text,fontSize=T.body,fontWeight=FontWeight.SemiBold)
                Text("GitHub",color=T.muted,fontSize=T.micro)
            }
            UpdateReleaseNotes(notes,Modifier.weight(1f).fillMaxWidth(),notesFocus,if(canRecheck)recheckFocus else updateActionFocus)
        } ?: Spacer(Modifier.weight(1f))
    }

    @Composable private fun SettingsScreen(){
        Setting(tr("playlists"),"playlists"){openPanel("playlists")}
        Setting(tr("programme_guides"),"epg"){openPanel("epg")}
        Setting(tr("appearance"),"appearance"){openPanel("appearance")}
        Setting(tr("playback"),"playback"){openPanel("playback")}
        Setting(tr("remote_control"),"remote"){openPanel("remote")}
        Setting(tr("on_startup"),"startup"){openPanel("startup")}
        Setting(tr("backup"),"backup"){openPanel("backup")}
        Setting(tr("update_app"),"updates"){openPanel("updates")}
        Caption("TV Wave ${BuildConfig.VERSION_NAME}")
    }
    @Composable private fun PlaylistsScreen(){
        data.playlists.forEach{p->Setting(p.name,"playlist:${p.id}",subtitle=tr("channels_m261", p.channels.size, if(p.source.isEmpty())tr("file")else tr("url"))){selectedPlaylist=p.id;openPanel("playlistMenu")}}
        if(data.playlists.size<15)Setting(tr("add"),"add"){newSource()}else Caption(tr("15_playlists_added"))
    }
    @Composable private fun PlaylistMenu(){data.playlists.find{it.id==selectedPlaylist}?.let{p->
        Caption(p.name)
        Setting(tr("edit"),"playlist-edit"){newSource(p)}
        Setting(tr("programme_guide_source"),"playlist-epg",subtitle=data.sources.find{it.id==data.playlistEpgSources[p.id]}?.name?:tr("automatic_epg_source_order")){epgPlaylistId=p.id;openPanel("playlistEpg")}
        Setting(tr("delete"),"playlist-delete"){openPanel("playlistDelete")}
        if(p.source.isNotEmpty())Setting(tr("update_channels"),"playlist-refresh"){refresh(p)}
    }}
    @Composable private fun AudioScreen(){
        Setting(tr("audio_language"),"audio-language",when(data.audioLanguage){"ru"->tr("russian");"en"->tr("english");else->tr("original")}){
            work{change{it.copy(audioLanguage=when(it.audioLanguage){"ru"->"en";"en"->"";else->"ru"})};applyTracks()}
        }
        Setting(tr("subtitles"),"subtitles",if(data.subtitles)tr("on")else tr("off")){work{change{it.copy(subtitles=!it.subtitles)};applyTracks()}}
        Caption(tr("audio_tracks_and_subtitles_depend_on_the_channel"))
    }
    @Composable private fun MoreScreen(){
        Setting(if(player.isPlaying)tr("pause")else tr("resume"),"pause",""){togglePlayback();closePanels();controls=true}
        Setting(tr("subtitles"),"subtitles",if(data.subtitles)tr("on")else tr("off")){work{change{it.copy(subtitles=!it.subtitles)};applyTracks()}}
        Setting(tr("recently_watched"),"history"){selectGroup(BuiltinGroups.RECENT)}
        Setting(tr("settings"),"settings"){openPanel("settings")}
    }
    @Composable private fun SourceScreen(){
        Field(tr("name_optional"),sourceName){sourceName=it.take(120)}
        Caption(tr("where_is_your_playlist"))
        Row(horizontalArrangement=Arrangement.spacedBy(T.small)){
            Action((if(sourceMode=="url")"✓ "else"")+tr("url"),Modifier.weight(1f),key="source-url",enabled=!busy){sourceMode="url";candidate=null;error=""}
            Action((if(sourceMode=="file")"✓ "else"")+tr("file"),Modifier.weight(1f),key="source-file",enabled=!busy){sourceMode="file";candidate=null;error=""}
        }
        if(sourceMode=="url"){
            Field(tr("http_https_url"),sourceUrl){sourceUrl=it.take(8192);candidate=null;error=""}
            Action(tr("paste"),key="paste",enabled=!busy){paste()}
            Action(tr("check_url"),key="validate",enabled=!busy){validateUrl()}
        }
        if(sourceMode=="file"){
            Action(tr("choose_file_m275"),key="choose-file",enabled=!busy){chooseDocument("playlist")}
            Caption(if(chosenName.isBlank())tr("m3u_m3u8_supported")else chosenName)
        }
        Action(tr("from_phone_via_qr"),key="source-phone",enabled=!busy){openPhone("source")}
        candidate?.let{p->Text(tr("channels_groups", p.channels.size, p.channels.map{it.group}.distinct().size),color=T.success,fontSize=T.caption)}
        Action(if(editingPlaylist==null)tr("add")else tr("save"),key="source-add",enabled=!busy){addSource()}
    }
    @Composable private fun ColumnScope.FilesScreen(){
        val fileList=rememberLazyListState()
        val firstFile=remember{FocusRequester()}
        val inputMode=androidx.compose.ui.platform.LocalInputModeManager.current
        LaunchedEffect(folder,files){
            inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            fileList.scrollToItem(0)
            // The focused directory disappears when its contents replace the lazy list.
            // Wait for the new first row to be placed before restoring remote focus.
            repeat(5){delay(60);if(runCatching{firstFile.requestFocus()}.getOrDefault(false))return@LaunchedEffect}
        }
        Caption(folder?.name?.ifEmpty{tr("storage")}?:tr("files"))
        Row(horizontalArrangement=Arrangement.spacedBy(T.small)){
            Action(tr("import"),Modifier.weight(1f),key="file-import"){listFiles(imports)}
            Action(tr("folders"),Modifier.weight(1f),key="file-storage"){listFiles(File("/storage"))}
        }
        Setting("Downloads","file-downloads"){listFiles(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))}
        if(Build.VERSION.SDK_INT>=30&&!Environment.isExternalStorageManager()){
            Action(tr("allow_access"),Modifier.fillMaxWidth(),key="file-permission"){
                runCatching{startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${packageName}")))}.onFailure{error=tr("this_firmware_does_not_support_this_permission")}
            }
        }
        Notice()
        LazyColumn(Modifier.weight(1f).padding(T.focusGap),state=fileList,verticalArrangement=Arrangement.spacedBy(T.tiny)){
            item{Setting(tr("parent_folder"),"file-up","↑",modifier=if(files.isEmpty())Modifier.focusRequester(firstFile)else Modifier){folder?.parentFile?.let{listFiles(it)}}}
            items(files,key={it.path}){f->Setting(f.name,"file:${f.path}",if(f.isDirectory)"›"else"",if(f.isDirectory)tr("folder")else"",modifier=if(f==files.firstOrNull())Modifier.focusRequester(firstFile)else Modifier){if(f.isDirectory)listFiles(f)else readFile(f)}}
            if(files.isEmpty())item{Caption(tr("no_suitable_files_or_this_folder_is_unavailable"))}
        }
        Caption(if(filePurpose=="playlist")"M3U / M3U8"else tr("tvwavebackup_backup_files"))
    }
    @Composable private fun BackupScreen(){
        Caption(tr("settings_playlists_epg_urls_and_manual_matches_programme_schedules_are"))
        if(!loadFailed)Setting(tr("create_backup"),"export",subtitle=tr("no_password_channels", data.channels.size)){if(!loadFailed)startExport()}
        Setting(tr("restore_from_file"),"restore"){chooseDocument("backup")}
    }
    @Composable private fun RestorePassword(){
        Caption(tr("this_backup_was_created_by_an_older_version_with_a_password_new_backup"))
        Field(tr("legacy_backup_password"),password,true){password=it.take(128)}
        Action(tr("open_backup"),key="legacy-open",enabled=!busy){decryptBackup()}
    }
    @Composable private fun RestoreConfirm(){restoreState?.let{s->
        Text(tr("backup_verified"),color=T.success,fontSize=T.body)
        Caption(tr("text_size_audio_subtitles", s.fontPercent, s.audioLanguage.ifEmpty{tr("original")}, if(s.subtitles)tr("on")else tr("off")))
        Caption(tr("playlists_channels_favourites", s.playlists.size, s.channels.size, s.favourites.size))
        if(s.sources.isNotEmpty())Caption(tr("epg_sources_manual_matches_time_offsets_playlist_bindings_programme_sc", s.sources.size, s.epgBindings.size, s.epgOffsets.size, s.playlistEpgSources.size))
        Caption(tr("your_current_playlists_and_settings_will_be_replaced_by_this_backup"))
        Action(tr("restore"),key="restore-apply",enabled=!busy){applyRestore()}
        Action(tr("cancel_m245"),key="restore-cancel",enabled=!busy){back()}
    }}
    @Composable private fun ProgrammeDetails(){
        val focus=remember{FocusRequester()};val info=rememberScrollState();val scope=rememberCoroutineScope();val density=LocalDensity.current
        val rows=epgRows[selectedChannel].orEmpty();val current=selectedProgramme()
        val selectedIndex=rows.indexOfFirst{it.start==current?.start}.coerceAtLeast(0)
        val stripIndex=(selectedIndex-1).coerceAtLeast(0)
        // Start at the selection, including when EPG arrives after the screen opens.
        val strip=key(selectedChannel,rows.isNotEmpty()){
            rememberLazyListState(initialFirstVisibleItemIndex=stripIndex)
        }
        LaunchedEffect(Unit){delay(80);focus.requestFocus()}
        LaunchedEffect(current?.start){info.scrollTo(0)}
        LaunchedEffect(strip,stripIndex,rows.size){if(rows.isNotEmpty())strip.scrollToItem(stripIndex)}
        BoxWithConstraints(Modifier.fillMaxSize().background(T.bg).testTag("programme-details").focusRequester(focus)
            .onPreviewKeyEvent{event->val key=event.nativeKeyEvent
                when(key.keyCode){
                    AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT->{if(key.action==AndroidKey.ACTION_DOWN)moveProgramme(if(key.keyCode==AndroidKey.KEYCODE_DPAD_RIGHT)1 else -1);true}
                    AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN->{if(key.action==AndroidKey.ACTION_DOWN)scope.launch{info.animateScrollBy(with(density){T.descriptionScroll.toPx()}*if(key.keyCode==AndroidKey.KEYCODE_DPAD_DOWN)1 else -1)};true}
                    AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER->{if(key.action==AndroidKey.ACTION_DOWN&&key.repeatCount==0)requestProgrammePlayback();true}
                    else->false
                }
            }.focusable()){
            val width=maxWidth-T.safe*2;val previewWidth=width*.34f
            val bottom=T.detailsBottom*maxOf(1f,density.fontScale)
            if(playing==null)Box(Modifier.offset(x=T.safe,y=T.previewTop).width(previewWidth).height(T.previewHeight).background(T.surface,RoundedCornerShape(T.previewRadius)),contentAlignment=Alignment.Center){Caption(tr("live_tv_is_not_playing"))}
            Text(tr("programme_details"),Modifier.offset(x=T.safe,y=T.top),color=T.muted,fontSize=T.caption)
            Column(Modifier.offset(x=T.safe,y=T.previewTop+T.previewHeight+T.small).width(previewWidth),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                Text(if(playing==null)tr("channel_is_not_playing")else if(playback=="playing")tr("live_tv_continues")else if(playback=="paused")tr("live_tv_paused")else tr("connecting_to_live_tv"),Modifier.testTag("details-playback"),color=T.muted,fontSize=T.micro)
                Text(playing?.name.orEmpty(),Modifier.testTag("details-live-channel"),color=T.text,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(if(info.canScrollForward)tr("read_description")else tr("end_of_description"),Modifier.testTag("details-scroll-position"),color=T.muted,fontSize=T.micro)
            }
            Column(Modifier.offset(x=T.safe+previewWidth+T.pad,y=T.previewTop).width(width-previewWidth-T.pad)
                .height(maxHeight-T.previewTop-bottom-T.pad).testTag("programme-description").verticalScroll(info),verticalArrangement=Arrangement.spacedBy(T.small)){
                Text(data.channels.firstOrNull{it.id==selectedChannel}?.name.orEmpty(),color=T.muted,fontSize=T.caption)
                Text(current?.title?:missingEpg(),Modifier.testTag("details-title"),color=T.text,fontSize=T.title,fontWeight=FontWeight.SemiBold)
                if(current!=null){
                    Text(programmeSpan(current),Modifier.testTag("details-time"),color=T.text,fontSize=T.caption)
                    Text(tr("min", (current.end-current.start)/60000, programmeStatus(current)),color=T.muted,fontSize=T.micro)
                }
                Text(current?.description?.ifBlank{tr("the_epg_source_did_not_provide_a_description")}?:tr("no_data_for_this_time_choose_a_nearby_programme_or_return_to_live_tv"),Modifier.testTag("details-description"),color=T.text,fontSize=T.description,lineHeight=T.description*1.4f)
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(bottom).padding(horizontal=T.safe).padding(bottom=T.top),verticalArrangement=Arrangement.spacedBy(T.small)){
                Text(tr("channel_schedule", current?.let{SimpleDateFormat("EEE, d MMMM",L10n.locale).format(Date(it.start))}?:tr("today")),color=T.muted,fontSize=T.caption,maxLines=1)
                LazyRow(state=strip,modifier=Modifier.fillMaxWidth().weight(1f).padding(T.focusGap),horizontalArrangement=Arrangement.spacedBy(T.small)){
                    items(rows,key={it.start}){p->
                        val selected=p.start==current?.start
                        Column(Modifier.width(T.programmeCardWidth*maxOf(1f,density.fontScale)).fillMaxHeight().testTag("details-programme:${p.start}")
                            .then(focusSurface(selected)).archiveOutline(programmeHasArchive(data.channels.firstOrNull{it.id==selectedChannel},p),if(selected)T.onAccent else T.muted).focusProperties{canFocus=false}.clickable{if(selected)requestProgrammePlayback()else guideTime=p.start}.padding(T.small),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                            Text(timeRange(p),color=if(selected)T.onAccent else T.muted,fontSize=T.micro,maxLines=1)
                            Text(p.title,color=if(selected)T.onAccent else T.text,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                    if(rows.isEmpty())item{Caption(tr("no_programme_guide_yet"))}
                }
                Text(tr("programmes_ok_watch_back_current_programme"),color=T.muted,fontSize=T.micro,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
    @Composable private fun ChannelBrowser(){
        val focus=remember{FocusRequester()}
        val info=rememberScrollState();val scope=rememberCoroutineScope();val density=LocalDensity.current
        val channels=filtered();val channelIndex=channels.indexOfFirst{it.id==selectedChannel}.coerceAtLeast(0)
        val rows=epgRows[selectedChannel].orEmpty();val current=selectedProgramme()
        val programmeIndex=rows.indexOfFirst{it.start==current?.start}.coerceAtLeast(0)
        val programmeTop=(programmeIndex-2).coerceAtLeast(0)
        // Position before the first layout, rather than animating from channel 1 / oldest EPG.
        val channelList=rememberLazyListState(initialFirstVisibleItemIndex=channelIndex)
        val programmeList=key(selectedChannel,rows.isNotEmpty()){
            rememberLazyListState(initialFirstVisibleItemIndex=programmeTop)
        }
        LaunchedEffect(Unit){delay(80);focus.requestFocus()}
        LaunchedEffect(channelIndex){
            val layout=channelList.layoutInfo;val item=layout.visibleItemsInfo.firstOrNull{it.index==channelIndex}
            if(channels.isNotEmpty()&&(item==null||item.offset<layout.viewportStartOffset||item.offset+item.size>layout.viewportEndOffset))channelList.scrollToItem(channelIndex)
        }
        LaunchedEffect(selectedChannel,current?.start){info.scrollTo(0)}
        LaunchedEffect(programmeList,programmeTop,rows.size){if(rows.isNotEmpty())programmeList.scrollToItem(programmeTop)}
        Row(Modifier.fillMaxSize().background(T.veil).padding(horizontal=T.safe,vertical=T.top).testTag("channel-browser")
            .focusRequester(focus).onPreviewKeyEvent{event->val key=event.nativeKeyEvent
                when(key.keyCode){
                    AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT->{if(key.action==AndroidKey.ACTION_DOWN&&key.repeatCount==0)browseZone=(browseZone+if(key.keyCode==AndroidKey.KEYCODE_DPAD_RIGHT)1 else -1).coerceIn(0,2);true}
                    AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN->{if(key.action==AndroidKey.ACTION_DOWN){
                        val delta=if(key.keyCode==AndroidKey.KEYCODE_DPAD_DOWN)1 else -1
                        when(browseZone){
                            0->channels.getOrNull((channelIndex+delta).coerceIn(0,(channels.size-1).coerceAtLeast(0)))?.let{selectedChannel=it.id;guideTime=0L}
                            1->moveProgramme(delta)
                            2->scope.launch{info.animateScrollBy(with(density){T.descriptionScroll.toPx()}*delta)}
                        }
                    };true}
                    AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER->{if(key.action==AndroidKey.ACTION_DOWN&&key.repeatCount==0){
                        if(browseZone==0)channels.getOrNull(channelIndex)?.let{tune(it,false);screen="player";controls=true;interaction++}else if(browseZone==1)requestProgrammePlayback()else openProgrammeDetails()
                    };true}
                    else->false
                }
            }.focusable(),horizontalArrangement=Arrangement.spacedBy(T.gap)){
            Column(Modifier.weight(.28f).fillMaxHeight().background(T.browserSurface,RoundedCornerShape(T.radius)).padding(T.small),verticalArrangement=Arrangement.spacedBy(T.small)){
                Text(BuiltinGroups.label(group),color=T.text,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                LazyColumn(state=channelList,modifier=Modifier.weight(1f).padding(T.focusGap),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                    itemsIndexed(channels,key={_,c->c.id}){i,c->val selected=c.id==selectedChannel&&browseZone==0
                        Column(Modifier.fillMaxWidth().testTag("browse-channel:${i}").then(focusSurface(selected)).focusProperties{canFocus=false}
                            .clickable{selectedChannel=c.id;guideTime=0L;browseZone=0}.padding(T.small),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
                                ChannelLogo(c,if(selected)T.onAccent else T.text,if(selected)T.onAccent else T.inputBorder)
                                Text("${i+1}  ${c.name}",Modifier.weight(1f),color=if(selected)T.onAccent else T.text,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                                if(channelArchiveDays(c)>0)ArchiveIcon(if(selected)T.onAccent else T.text,Modifier.size(18.dp))
                                if(c.id==playing?.id)Text("●",color=if(selected)T.onAccent else T.text,fontSize=T.micro)
                            }
                            Text(programme(c.id)?.title?:missingEpg(c.id),color=if(selected)T.onAccent else T.muted,fontSize=T.micro,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(tr("ok_watch_schedule"),color=T.muted,fontSize=T.micro)
            }
            Column(Modifier.weight(.31f).fillMaxHeight().background(T.browserSurface,RoundedCornerShape(T.radius)).padding(T.small),verticalArrangement=Arrangement.spacedBy(T.small)){
                Text(channels.getOrNull(channelIndex)?.name.orEmpty(),Modifier.testTag("browse-selected-channel"),color=T.text,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                LazyColumn(state=programmeList,modifier=Modifier.weight(1f).padding(T.focusGap),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                    itemsIndexed(rows,key={_,p->p.start}){i,p->val selected=p.start==current?.start&&browseZone==1
                        Column(Modifier.fillMaxWidth().testTag("browse-programme:${i}").then(focusSurface(selected)).archiveOutline(programmeHasArchive(channels.getOrNull(channelIndex),p),if(selected)T.onAccent else T.muted).focusProperties{canFocus=false}
                            .clickable{guideTime=p.start;browseZone=1}.padding(T.small),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                            val day=SimpleDateFormat("dd.MM",L10n.locale)
                            if(i==0||day.format(Date(rows[i-1].start))!=day.format(Date(p.start)))Text(SimpleDateFormat("EEE, d MMMM",L10n.locale).format(Date(p.start)),color=if(selected)T.onAccent else T.muted,fontSize=T.micro)
                            Text("${timeLabel(p.start)}  ${p.title}",color=if(selected)T.onAccent else T.text,fontSize=T.caption,maxLines=3,overflow=TextOverflow.Ellipsis)
                        }
                    }
                    if(rows.isEmpty())item{Caption(missingEpg())}
                }
                Text(tr("programmes_ok_watch"),color=T.muted,fontSize=T.micro)
            }
            Column(Modifier.weight(.41f).fillMaxHeight().background(T.browserSurface,RoundedCornerShape(T.radius))
                .border(if(browseZone==2)T.focusLine else T.line,if(browseZone==2)T.accent else T.transparent,RoundedCornerShape(T.radius)).padding(T.gap),verticalArrangement=Arrangement.spacedBy(T.small)){
                Column(Modifier.weight(1f).verticalScroll(info),verticalArrangement=Arrangement.spacedBy(T.small)){
                    Text(current?.title?:missingEpg(),Modifier.testTag("browse-title"),color=T.text,fontSize=T.hudTitle,fontWeight=FontWeight.SemiBold)
                    if(current!=null){Text(programmeSpan(current),color=T.muted,fontSize=T.micro);Text(tr("min", (current.end-current.start)/60000, programmeStatus(current)),color=T.muted,fontSize=T.micro)}
                    Text(current?.description?.ifBlank{tr("the_epg_source_did_not_provide_a_description")}?:tr("choose_a_channel_or_add_a_programme_guide_source"),Modifier.testTag("browse-description"),color=T.text,fontSize=T.description,lineHeight=T.description*1.4f)
                }
                Text(if(browseZone==2){if(info.canScrollForward)tr("read_programmes")else tr("end_of_description_programmes")}else tr("read_description_m322"),Modifier.testTag("browse-scroll-position"),color=T.muted,fontSize=T.micro)
                Text(tr("back_to_watching"),color=T.muted,fontSize=T.micro)
                Text(if(archiveSession!=null){if(playback=="playing")tr("recording_continues")else tr("recording_paused")}else if(playback=="playing")tr("live_tv_continues")else tr("live_tv_paused"),Modifier.testTag("browse-playback"),color=T.muted,fontSize=T.micro)
            }
        }
    }

    @Composable private fun PlayerScreen(active:Boolean){
        var health by remember{mutableStateOf(PlaybackHealth())}
        LaunchedEffect(active,controls,diagnosticsOpen){
            if(!active)diagnosticsOpen=false
            while(active&&controls){
                @Suppress("DEPRECATION")
                val refresh=windowManager.defaultDisplay.refreshRate
                health=playbackDiagnostics.snapshot(refresh)
                delay(1000)
            }
        }
        val a=archiveSession
        val current=a?.programme?:programme(playing?.id)
        var liveNow by remember{mutableLongStateOf(System.currentTimeMillis())}
        LaunchedEffect(controls,a){while(controls&&a==null){liveNow=System.currentTimeMillis();delay(1000)}}
        val duration=current?.let{(it.end-it.start).coerceAtLeast(1)}?:0
        val available=current?.let{(liveNow-it.start).coerceIn(0,duration)}?:0
        val position=if(a!=null)archivePosition.coerceAtMost(duration)else available
        val canSeek=active&&archiveSourceAvailable()&&current!=null
        val forward=a!=null&&position<a.available(System.currentTimeMillis())-1000
        LaunchedEffect(canSeek,position>0,forward,playback){
            if(hudFocus=="player-timeline"&&!canSeek||hudFocus!="player-timeline"&&hudFocus !in hudKeys())hudFocus="player-pause"
        }
        BoxWithConstraints(Modifier.fillMaxSize()){
            val hudMinimum=maxHeight*T.hudFraction
            if((playback!="playing"&&playback!="paused")||recoveryStatus.isNotEmpty())Text(recoveryStatus.ifEmpty{when(playback){"buffering"->tr("connecting_m225");"paused"->tr("pause");"error"->playbackError;else->tr("playback_stopped")}},Modifier.align(Alignment.Center).widthIn(max=T.recoveryWidth).testTag("playback-status").background(T.hud,RoundedCornerShape(T.radius)).padding(T.pad),color=T.text,fontSize=T.body)
            if(controls){
                Row(Modifier.fillMaxWidth().padding(horizontal=T.safe,vertical=T.top),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(playing?.name.orEmpty(),Modifier.background(T.hud,RoundedCornerShape(T.radius)).padding(T.small),color=T.text,fontSize=T.description,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(clockText,Modifier.background(T.hud,RoundedCornerShape(T.radius)).padding(T.small),color=T.text,fontSize=T.body)
                }
                Box(Modifier.align(Alignment.BottomCenter)){
                    PlaybackHud(current?.title?:playing?.name?:tr("live_tv"),
                        (current?.let{if(a!=null)programmeSpan(it)else timeRange(it)}?:missingEpg(playing?.id))+(if(videoHeight>0)" · ${videoHeight}p"else ""),
                        a==null&&player.isPlaying,if(a!=null)tr("recording")else if(playback=="paused")tr("pause")else tr("not_live"),hudMinimum,if(recoveryJob?.isActive==true)tr("cancel")else if(playback in listOf("error","stopped"))tr("retry")else if(player.isPlaying)tr("pause")else tr("watch"),!player.isPlaying,
                        seekDraft?:position,duration,if(a!=null)a.available(System.currentTimeMillis())else available,seekDraft!=null,
                        canSeek&&position>0,forward,canSeek,
                        data.subtitles,playing?.id in data.favourites,hudFocus,hudRequests,{hudFocus=it},::hudAction,archiveDays=channelArchiveDays(playing),health=health,diagnosticsEnabled=diagnosticsOpen)
                }
            }
            if(active&&controls&&diagnosticsOpen)PlaybackHealthPanel(health,Modifier.align(Alignment.TopEnd).padding(top=80.dp,end=T.safe).width(360.dp))
        }
    }
}
