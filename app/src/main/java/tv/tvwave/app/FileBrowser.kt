package tv.tvwave.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Shared storage is shown as /, never Android's protected system root. */
internal object BrowserFiles {
    fun inside(root:File,file:File):Boolean=runCatching{
        val base=root.canonicalPath;val path=file.canonicalPath
        path==base||path.startsWith(base+File.separator)
    }.getOrDefault(false)
    fun path(root:File,file:File):String=if(file==root)"/" else "/"+file.relativeTo(root).invariantSeparatorsPath
    fun list(root:File,dir:File,playlist:Boolean):List<File>? {
        if(!inside(root,dir))return null
        return dir.listFiles()?.filter{inside(root,it)&&!it.name.startsWith('.')&&(it.isDirectory||
            if(playlist)it.extension.lowercase() in setOf("m3u","m3u8") else BackupFile.accepts(it.name))}
            ?.sortedWith(compareBy<File>{!it.isDirectory}.thenBy{it.name.lowercase()})
    }
}
private data class BrowserListing(val current:List<File>?=emptyList(),val parent:List<File> = emptyList(),val loading:Boolean=true)

@Composable fun FileBrowser(playlist:Boolean,active:Boolean,error:String="",busy:Boolean=false,
    reviewing:Boolean=false,verified:Boolean=false,reviewContent:@Composable ()->Unit={},
    onSelection:()->Unit={},onRestore:()->Unit={},onCancelReview:()->Unit={},onFile:(File)->Unit,onClose:()->Unit){
    val context=LocalContext.current
    val root=remember{Environment.getExternalStorageDirectory()}
    var directory by remember{mutableStateOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).takeIf{it.isDirectory}?:root)}
    var preferred by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf<File?>(null)}
    var refresh by remember{mutableIntStateOf(0)}
    var permissionError by remember{mutableStateOf("")}
    val allowed=remember(refresh){if(Build.VERSION.SDK_INT>=30)Environment.isExternalStorageManager()else ContextCompat.checkSelfPermission(context,Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED}
    val legacyPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){refresh++}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)refresh++}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer)}
    }
    val rowFocus=remember{mutableMapOf<String,FocusRequester>()}
    val detailsFocus=remember{FocusRequester()}
    val restoreFocus=remember{FocusRequester()}
    val cancelFocus=remember{FocusRequester()}
    var reviewFocused by remember{mutableStateOf(false)}
    LaunchedEffect(reviewing,verified,busy){
        if(reviewing&&!busy){
            withFrameNanos { }
            (if(verified)restoreFocus else cancelFocus).requestFocus()
        }
    }
    var detailsFocused by remember{mutableStateOf(false)}
    val detailsScroll=rememberScrollState()
    val scope=rememberCoroutineScope()
    val snapshot by produceState(BrowserListing(),directory,playlist,refresh){
        value=BrowserListing()
        value=withContext(Dispatchers.IO){BrowserListing(BrowserFiles.list(root,directory,playlist),
            if(directory==root)emptyList()else directory.parentFile?.let{BrowserFiles.list(root,it,playlist)}.orEmpty(),false)}
    }
    val first=remember{FocusRequester()}
    val close=remember{FocusRequester()}
    val permission=remember{FocusRequester()}
    val scroll=rememberLazyListState()
    fun returnToFile(){selected?.let{file->scope.launch{
        val index=snapshot.current.orEmpty().indexOf(file)
        if(index>=0){scroll.scrollToItem(index);withFrameNanos { };rowFocus[file.path]?.requestFocus()}
    }}}
    fun go(dir:File,focus:String=""){
        if(BrowserFiles.inside(root,dir)){preferred=focus;directory=dir;selected=null;onSelection()}
    }
    fun up(){if(directory!=root)directory.parentFile?.let{go(it,directory.name)}else onClose()}
    BackHandler(enabled=active){if(!busy){if(detailsFocused||reviewFocused)returnToFile()else up()}}
    val input=LocalInputModeManager.current
    val list=snapshot.current.orEmpty()
    val focusIndex=list.indexOfFirst{if(selected!=null)it==selected else it.name==preferred}.coerceAtLeast(0)
    LaunchedEffect(directory,snapshot,active,allowed){
        if(!active||snapshot.loading)return@LaunchedEffect
        selected=list.getOrNull(focusIndex)
        scroll.scrollToItem(focusIndex)
        input.requestInputMode(InputMode.Keyboard)
        repeat(8){
            delay(60)
            if(runCatching{(if(!allowed)permission else if(list.isNotEmpty())first else close).requestFocus()}.getOrDefault(false))return@LaunchedEffect
        }
    }
    Column(Modifier.fillMaxSize().background(T.surface).padding(horizontal=T.safe,vertical=T.pad).testTag("file-browser").focusGroup()
        .onPreviewKeyEvent { event ->
            if(busy)return@onPreviewKeyEvent true
            if(event.nativeKeyEvent.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){
                if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN&&event.nativeKeyEvent.repeatCount==0){
                    if(detailsFocused||reviewFocused)returnToFile()else if(directory!=root)up()
                }
                true
            }else if(event.nativeKeyEvent.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){
                // Never let spatial focus search enter an unpainted scroll container.
                if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN&&event.nativeKeyEvent.repeatCount==0&&!detailsFocused){
                    selected?.let{if(it.isDirectory)go(it)else if(reviewing)(if(verified)restoreFocus else cancelFocus).requestFocus()else detailsFocus.requestFocus()}
                }
                true
            }else false
        },verticalArrangement=Arrangement.spacedBy(T.gap)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.pad)){
            BrandHeader()
            Column(Modifier.weight(1f)){
                Text(tr(if(playlist)"choose_file_m275"else"restore_backup"),color=T.text,fontSize=T.title,fontWeight=FontWeight.SemiBold)
                Text(BrowserFiles.path(root,directory),color=T.muted,fontSize=T.caption,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("file-path"))
            }
            BrowserButton(tr("files_close"),Modifier.focusRequester(close).testTag("file-close"),enabled=!busy,onClick=onClose)
        }
        if(busy&&!reviewing)Text(tr("files_loading"),color=T.muted,fontSize=T.caption)
        if(error.isNotEmpty()&&!reviewing)Text(error,color=T.danger,fontSize=T.caption,modifier=Modifier.testTag("file-error"))
        if(!allowed){
            Text(tr("files_permission"),color=T.muted,fontSize=T.caption)
            BrowserButton(tr("allow_access"),Modifier.focusRequester(permission).testTag("file-permission")){
                permissionError=""
                if(Build.VERSION.SDK_INT>=30)runCatching{
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}")))
                }.onFailure{permissionError=tr("this_firmware_does_not_support_this_permission")}
                else legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if(permissionError.isNotEmpty())Text(permissionError,color=T.danger,fontSize=T.caption)
        Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(T.pad)){
            Column(Modifier.weight(.27f),verticalArrangement=Arrangement.spacedBy(T.small)){
                Text(if(directory==root)tr("files_tv_storage")else directory.parentFile?.name.orEmpty().let{if(directory.parentFile==root)"/"else it},color=T.text,fontSize=T.body,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(tr(if(directory==root)"files_root"else"files_parent"),color=T.muted,fontSize=T.micro)
                if(directory!=root){
                    val parentScroll=rememberLazyListState()
                    LaunchedEffect(directory,snapshot){if(!snapshot.loading)parentScroll.scrollToItem(snapshot.parent.indexOf(directory).coerceAtLeast(0))}
                    LazyColumn(state=parentScroll,modifier=Modifier.focusProperties{canFocus=false},verticalArrangement=Arrangement.spacedBy(T.tiny)){
                        itemsIndexed(snapshot.parent){_,file->
                            BrowserRow(file.name,file.isDirectory,false,file==directory,Modifier,{},null)
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(T.border))
            Column(Modifier.weight(.40f),verticalArrangement=Arrangement.spacedBy(T.small)){
                Text(if(directory==root)"/"else directory.name,color=T.text,fontSize=T.body,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(tr("files_current"),color=T.muted,fontSize=T.micro)
                LazyColumn(state=scroll,modifier=Modifier.weight(1f).testTag("file-list"),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                    itemsIndexed(list,key={_,file->file.path}){index,file->
                        val requester=rowFocus.getOrPut(file.path){FocusRequester()}
                        BrowserRow(file.name,file.isDirectory,true,false,
                            Modifier.testTag("file:${file.path}").focusRequester(requester).then(if(index==focusIndex)Modifier.focusRequester(first)else Modifier),
                            {if(selected!=file){selected=file;onSelection()}},{if(!busy){if(file.isDirectory)go(file)else{if(selected!=file){selected=file;onSelection()};onFile(file)}}})
                    }
                    if(list.isEmpty())item{Text(tr(if(snapshot.loading)"files_loading"else if(snapshot.current==null)"files_unreadable"else"files_empty"),color=T.muted,fontSize=T.caption)}
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(T.border))
            if(reviewing){
                Column(Modifier.weight(.33f).fillMaxHeight().testTag("backup-review")
                    .onFocusChanged{reviewFocused=it.hasFocus}.focusGroup(),verticalArrangement=Arrangement.spacedBy(T.small)){
                    Text(selected?.name.orEmpty(),color=T.text,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(T.small)){
                        if(busy){
                            Text(tr(if(verified)"files_restoring_backup"else"files_checking_backup"),color=T.text,fontSize=T.caption)
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("backup-check-progress"),color=T.accent,trackColor=T.border)
                        }else if(error.isNotEmpty()){
                            if(!verified)Text(tr("files_backup_invalid"),color=T.danger,fontSize=T.caption)
                            else reviewContent()
                            Text(error,color=T.muted,fontSize=T.micro,modifier=Modifier.testTag("file-error"))
                        }else reviewContent()
                    }
                    BrowserButton(tr("restore"),Modifier.fillMaxWidth().focusRequester(restoreFocus).testTag("restore-apply"),enabled=verified&&!busy,onClick=onRestore)
                    BrowserButton(tr("cancel_m245"),Modifier.fillMaxWidth().focusRequester(cancelFocus).testTag("restore-cancel"),enabled=!busy){onCancelReview();returnToFile()}
                }
            }else Column(Modifier.weight(.33f).testTag("file-details").focusRequester(detailsFocus)
                .onFocusChanged{detailsFocused=it.isFocused}
                .border(if(detailsFocused)2.dp else 0.dp,if(detailsFocused)T.accent else Color.Transparent,RoundedCornerShape(T.radius))
                .onPreviewKeyEvent{event->
                    if(event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)){
                        if(event.nativeKeyEvent.action==KeyEvent.ACTION_UP)selected?.takeIf{!it.isDirectory}?.let(onFile)
                        true
                    }else if(event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)){
                        if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN){
                            val delta=if(event.nativeKeyEvent.keyCode==KeyEvent.KEYCODE_DPAD_UP)-120 else 120
                            scope.launch{detailsScroll.scrollTo((detailsScroll.value+delta).coerceIn(0,detailsScroll.maxValue))}
                        }
                        true
                    }else false
                }.focusable().focusProperties{canFocus=false}.verticalScroll(detailsScroll).padding(T.small),verticalArrangement=Arrangement.spacedBy(T.gap)){
                val file=selected
                Text(tr(if(file?.isDirectory==true)"files_folder_details"else"files_details"),color=T.text,fontSize=T.body,fontWeight=FontWeight.SemiBold)
                if(file==null)Text(tr("files_choose"),color=T.muted,fontSize=T.caption)
                else {
                    BrowserIcon(file.isDirectory,T.accent,Modifier.size(40.dp))
                    Text(file.name,color=T.text,fontSize=T.body,fontWeight=FontWeight.SemiBold)
                    if(!file.isDirectory){
                        Text(tr("files_size")+" · "+Formatter.formatShortFileSize(context,file.length()),color=T.muted,fontSize=T.caption)
                        if(file.lastModified()>0)Text(tr("files_modified")+"\n"+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT,L10n.locale).format(Date(file.lastModified())),color=T.muted,fontSize=T.caption)
                    }
                    Text(tr("files_location")+"\n"+BrowserFiles.path(root,file),color=T.muted,fontSize=T.micro)
                    Text(tr(if(file.isDirectory)"files_open_folder"else if(playlist)"files_open_playlist"else"files_check_backup"),color=T.text,fontSize=T.caption)
                }
            }
        }
        Text(tr(if(directory==root)"files_root_keys"else"files_keys"),color=T.muted,fontSize=T.micro,maxLines=2)
    }
}

@Composable private fun BrowserButton(label:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    Text(label,color=if(!enabled)T.muted else if(focused)T.onAccent else T.text,fontSize=T.caption,
        modifier=modifier.onFocusChanged{focused=it.isFocused}
            .background(if(focused)T.accent else Color.Transparent,RoundedCornerShape(T.radius))
            .border(1.dp,if(focused)T.accent else T.inputBorder,RoundedCornerShape(T.radius)).clickable(enabled=enabled,onClick=onClick).padding(T.small))
}
@Composable private fun BrowserRow(label:String,folder:Boolean,interactive:Boolean,ancestor:Boolean,modifier:Modifier,onFocus:()->Unit,onClick:(()->Unit)?){
    var focused by remember{mutableStateOf(false)}
    val color=if(focused)T.onAccent else T.text
    Row(modifier.fillMaxWidth().onFocusChanged{focused=it.isFocused;if(it.isFocused)onFocus()}
        .background(if(focused)T.accent else if(ancestor)T.raised else Color.Transparent,RoundedCornerShape(T.radius))
        .then(if(interactive&&onClick!=null)Modifier.clickable(onClick=onClick)else Modifier)
        .padding(horizontal=T.small,vertical=T.gap),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
        BrowserIcon(folder,color,Modifier.size(22.dp))
        Text(label,color=color,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}
@Composable private fun BrowserIcon(folder:Boolean,color:Color,modifier:Modifier){
    Canvas(modifier){
        val w=size.width;val h=size.height
        val path=Path().apply{
            if(folder){moveTo(w*.05f,h*.2f);lineTo(w*.4f,h*.2f);lineTo(w*.5f,h*.32f);lineTo(w*.95f,h*.32f);lineTo(w*.95f,h*.85f);lineTo(w*.05f,h*.85f)}
            else {moveTo(w*.2f,h*.05f);lineTo(w*.65f,h*.05f);lineTo(w*.85f,h*.25f);lineTo(w*.85f,h*.95f);lineTo(w*.2f,h*.95f)}
            close()
        }
        if(folder)drawPath(path,color)else drawPath(path,color,style=Stroke(w*.06f))
    }
}
