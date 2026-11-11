package tv.tvwave.app

import kotlinx.coroutines.launch
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date

fun reminderTime(r:ProgrammeReminder)=SimpleDateFormat("EEE, d MMM · HH:mm",L10n.locale).format(Date(r.start))
fun reminderCountdown(r:ProgrammeReminder,now:Long):String {
    val minutes=((r.start-now+59999)/60000).coerceAtLeast(0)
    return if(minutes==0L)tr("reminder_started")else tr("reminder_in_minutes",minutes)
}
@Composable fun ReminderButton(label:String,tag:String,modifier:Modifier=Modifier,onClick:()->Unit) {
    var focused by remember{mutableStateOf(false)}
    Box(modifier.testTag(tag).onFocusChanged{focused=it.isFocused}
        .background(if(focused)T.accent else T.raised,RoundedCornerShape(T.radius))
        .border(if(focused)2.dp else 1.dp,if(focused)T.text else T.border,RoundedCornerShape(T.radius))
        .clickable(onClick=onClick).padding(horizontal=T.gap,vertical=T.small)) {
        Text(label,color=if(focused)T.onAccent else T.text,fontSize=T.caption)
    }
}
// Shared geometry keeps the video at its final position on the very first frame.
fun reminderHeaderHeight(fontScale:Float)=40.dp*maxOf(1f,fontScale)
fun reminderPreviewRect(width:androidx.compose.ui.unit.Dp,fontScale:Float,hasItems:Boolean):androidx.compose.ui.unit.DpRect {
    val menu=T.leftMainWidth*maxOf(1f,fontScale)
    val content=width-menu-T.pad*2
    val centre=if(hasItems)(content-T.pad)*1.15f/2.15f else content
    val preview=minOf(centre,280.dp)
    val left=menu+T.pad+(centre-preview)/2
    val top=T.drawerTop+reminderHeaderHeight(fontScale)+T.gap
    return androidx.compose.ui.unit.DpRect(left,top,left+preview,top+preview*9f/16f)
}
/** Live preview and compact reminders share the centre column; details have their own focus. */
@Composable fun ReminderBrowser(
    reminders:List<ProgrammeReminder>,now:Long,listFocus:FocusRequester,navFocus:FocusRequester,entryRevision:Int,
    playing:Boolean,
    logo:@Composable (ProgrammeReminder,androidx.compose.ui.graphics.Color)->Unit,
    description:@Composable (ProgrammeReminder)->String?,onDelete:(ProgrammeReminder)->Unit,
    registerBack:((()->Boolean)?)->Unit
) {
    var selectedId by remember{mutableStateOf((reminders.firstOrNull{it.start>now}?:reminders.firstOrNull())?.id)}
    var actions by remember{mutableStateOf(false)}
    var infoFocused by remember{mutableStateOf(false)}
    var rightFocused by remember{mutableStateOf(false)}
    val selected=reminders.firstOrNull{it.id==selectedId}?:reminders.firstOrNull()
    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    val scope=rememberCoroutineScope()
    val deleteFocus=remember{FocusRequester()}
    val infoFocus=remember{FocusRequester()}
    val infoScroll=remember(selected?.id){androidx.compose.foundation.ScrollState(0)}
    val rowFocus=remember{mutableMapOf<String,FocusRequester>()}
    val press=remember{ProgrammeOkPress(scope)}
    var pressingRow by remember{mutableStateOf<ProgrammeReminder?>(null)}
    val density=androidx.compose.ui.platform.LocalDensity.current
    fun returnToList(){
        actions=false
        scope.launch {
            val index=reminders.indexOfFirst{it.id==selected?.id}
            if(index>=0){listState.scrollToItem(index);withFrameNanos{};rowFocus[selected!!.id]?.requestFocus()}
            else navFocus.requestFocus()
        }
    }
    fun enterActions(){actions=true;scope.launch{withFrameNanos{};deleteFocus.requestFocus()}}
    DisposableEffect(rightFocused,selected?.id,reminders){
        registerBack {if(rightFocused){returnToList();true}else false}
        onDispose{registerBack(null)}
    }
    DisposableEffect(Unit){onDispose{press.cancel()}}
    LaunchedEffect(entryRevision){
        val nearest=reminders.indexOfFirst{it.start>now}.coerceAtLeast(0)
        selectedId=reminders.getOrNull(nearest)?.id;actions=false
        listState.scrollToItem(nearest)
        withFrameNanos{}
        if(reminders.isNotEmpty())(rowFocus[selectedId]?:listFocus).requestFocus()else navFocus.requestFocus()
    }
    LaunchedEffect(reminders.map{it.id}){
        rowFocus.keys.retainAll(reminders.map{it.id}.toSet())
        if(reminders.isEmpty()){actions=false;navFocus.requestFocus()}
        else if(selectedId==null||reminders.none{it.id==selectedId}){
            selectedId=reminders.first().id;actions=false
            listState.scrollToItem(0);withFrameNanos{};listFocus.requestFocus()
        }
    }
    Column(Modifier.fillMaxSize().padding(start=T.pad,end=T.pad,top=T.drawerTop,bottom=T.drawerBottom)
        .onPreviewKeyEvent{event->
            // Keep the key-up here even when a hold moves focus to the delete button.
            val k=event.nativeKeyEvent
            if(k.keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,android.view.KeyEvent.KEYCODE_ENTER)&&pressingRow!=null){
                val r=pressingRow!!
                press.handle(k,{selectedId=r.id;infoFocus.requestFocus()},{selectedId=r.id;enterActions()})
                if(k.action==android.view.KeyEvent.ACTION_UP)pressingRow=null
                true
            }else false
        },verticalArrangement=Arrangement.spacedBy(T.gap)){
        Text(tr("reminders"),Modifier.height(reminderHeaderHeight(density.fontScale)),color=T.text,fontSize=T.title,fontWeight=FontWeight.SemiBold)
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(T.pad)){
            Column(Modifier.weight(1.15f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(T.gap)){
                BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){
                    Box(Modifier.width(minOf(maxWidth,280.dp)).aspectRatio(16f/9f)
                        .background(T.surface,RoundedCornerShape(T.previewRadius)).testTag("reminder-live-preview"),contentAlignment=Alignment.Center){
                        if(!playing)Text(tr("live_tv_is_not_playing"),color=T.muted,fontSize=T.micro)
                    }
                }
                if(selected==null)Text(tr("reminder_empty"),Modifier.testTag("reminders-empty"),color=T.muted,fontSize=T.caption)
                else LazyColumn(state=listState,modifier=Modifier.weight(1f).fillMaxWidth().testTag("reminders-list")
                    .focusRequester(listFocus).focusProperties{left=navFocus}.padding(T.focusGap),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                    items(reminders,key={it.id}){r->
                        val requester=rowFocus.getOrPut(r.id){FocusRequester()}
                        var focused by remember{mutableStateOf(false)}
                        Row(Modifier.fillMaxWidth().focusRequester(requester)
                            .onFocusChanged{focused=it.isFocused;if(it.isFocused){selectedId=r.id;actions=false}}
                            .testTag("reminder:${r.id}")
                            .background(if(focused)T.accent else T.surface,RoundedCornerShape(T.radius))
                            .border(1.dp,if(r.id==selected.id&&!focused)T.border else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(T.radius))
                            .onPreviewKeyEvent{event->
                                val k=event.nativeKeyEvent
                                when(k.keyCode){
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT->{if(k.action==android.view.KeyEvent.ACTION_DOWN)infoFocus.requestFocus();true}
                                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,android.view.KeyEvent.KEYCODE_ENTER->{
                                        if(k.action==android.view.KeyEvent.ACTION_DOWN&&k.repeatCount==0){
                                            pressingRow=r
                                            press.handle(k,{infoFocus.requestFocus()},{enterActions()})
                                        }
                                        true
                                    }
                                    else->false
                                }
                            }.clickable{selectedId=r.id;infoFocus.requestFocus()}.padding(T.small),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
                            val ink=if(focused)T.onAccent else T.text
                            logo(r,ink)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                                Text(r.channelName,color=if(focused)ink else T.muted,fontSize=T.micro,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(r.title,color=ink,fontSize=T.caption,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                            Text(reminderCountdown(r,now),color=ink,fontSize=T.micro,maxLines=1)
                        }
                    }
                }
            }
            if(selected!=null)Column(Modifier.weight(1f).fillMaxHeight().background(T.surface,RoundedCornerShape(T.radius)).padding(T.gap)
                .onFocusChanged{rightFocused=it.hasFocus}.focusGroup().testTag("reminder-description")
                .onPreviewKeyEvent{event->
                    if(event.nativeKeyEvent.keyCode==android.view.KeyEvent.KEYCODE_DPAD_LEFT){
                        if(event.nativeKeyEvent.action==android.view.KeyEvent.ACTION_DOWN)returnToList()
                        true
                    }else false
                },verticalArrangement=Arrangement.spacedBy(T.small)){
                val text=description(selected)
                Column(Modifier.weight(1f).fillMaxWidth().focusRequester(infoFocus).onFocusChanged{infoFocused=it.isFocused}
                    .border(1.dp,if(infoFocused)T.border else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(T.radius))
                    .onPreviewKeyEvent{event->
                        val k=event.nativeKeyEvent
                        when(k.keyCode){
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN,android.view.KeyEvent.KEYCODE_DPAD_UP->{
                                if(k.action==android.view.KeyEvent.ACTION_DOWN){
                                    if(k.keyCode==android.view.KeyEvent.KEYCODE_DPAD_DOWN&&!infoScroll.canScrollForward)enterActions()
                                    else scope.launch{infoScroll.scrollBy(with(density){72.dp.toPx()}*if(k.keyCode==android.view.KeyEvent.KEYCODE_DPAD_DOWN)1 else -1)}
                                };true
                            }
                            else->false
                        }
                    }.focusable().verticalScroll(infoScroll).padding(T.tiny).testTag("reminder-info-scroll"),verticalArrangement=Arrangement.spacedBy(T.gap)){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
                        logo(selected,T.text)
                        Text(selected.channelName,Modifier.weight(1f),color=T.text,fontSize=T.caption)
                    }
                    Text(selected.title,color=T.text,fontSize=T.body,fontWeight=FontWeight.SemiBold)
                    Text(reminderTime(selected)+" – "+SimpleDateFormat("HH:mm",L10n.locale).format(Date(selected.end)),color=T.muted,fontSize=T.micro)
                    Text(reminderCountdown(selected,now),color=T.muted,fontSize=T.micro)
                    Text(if(text==null)""else text.ifBlank{tr("the_epg_source_did_not_provide_a_description")},color=T.text,fontSize=T.caption)
                }
                if(actions){
                  Column(Modifier.testTag("reminder-actions"),verticalArrangement=Arrangement.spacedBy(T.small)){
                        ReminderButton(tr("reminder_remove"),"reminder-delete",Modifier.fillMaxWidth().focusRequester(deleteFocus).onPreviewKeyEvent{
                            if(it.nativeKeyEvent.keyCode==android.view.KeyEvent.KEYCODE_DPAD_UP){if(it.nativeKeyEvent.action==android.view.KeyEvent.ACTION_DOWN){actions=false;infoFocus.requestFocus()};true}else false
                        }){actions=false;onDelete(selected)}
                        if(actions)ReminderButton(tr("cancel_m245"),"reminder-cancel",Modifier.fillMaxWidth()){returnToList()}
                    }
                }
            }
        }
        Text(tr("reminder_browse_hint"),color=T.muted,fontSize=T.micro)
    }
}

@Composable fun ReminderConfirm(r:ProgrammeReminder,exists:Boolean,onConfirm:()->Unit,onDismiss:()->Unit) {
    val first=remember{FocusRequester()}
    val okGuard=remember{FreshOkPressGuard()}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent{okGuard.consume(it.nativeKeyEvent)}.semantics{testTagsAsResourceId=true}){
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.43f).background(T.surface).padding(T.pad).testTag("reminder-confirm"),verticalArrangement=Arrangement.spacedBy(T.gap)) {
                Text(tr("reminders"),color=T.muted,fontSize=T.caption)
                Text(tr(if(exists)"reminder_remove_question" else "reminder_add_question"),color=T.text,fontSize=T.title,fontWeight=FontWeight.SemiBold)
                Text(r.title,color=T.text,fontSize=T.body)
                Text(r.channelName+"\n"+reminderTime(r),color=T.muted,fontSize=T.caption)
                if(exists)Text(tr("reminder_remove_info"),color=T.muted,fontSize=T.caption)
                Spacer(Modifier.weight(1f))
                ReminderButton(tr(if(exists)"delete" else "reminder_add"),"reminder-confirm-action",Modifier.focusRequester(first),onConfirm)
                ReminderButton(tr("cancel_m245"),"reminder-confirm-cancel",onClick=onDismiss)
            }
        }
        LaunchedEffect(Unit){first.requestFocus()}
    }
}
/** Temporary reminder overlay. Closing it returns focus to the underlying screen. */
@Composable fun ReminderNotification(r:ProgrammeReminder,seconds:Int,interacted:Boolean,onInteraction:()->Unit,onWatch:()->Unit,onClose:()->Unit) {
    val first=remember{FocusRequester()}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
    val window=(LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect{window?.setDimAmount(0f)}
    Box(Modifier.fillMaxSize().onPreviewKeyEvent{if(it.nativeKeyEvent.action==android.view.KeyEvent.ACTION_DOWN)onInteraction();false}.semantics{testTagsAsResourceId=true}.padding(T.safe),contentAlignment=Alignment.TopEnd){
    Column(Modifier.widthIn(max=440.dp).testTag("reminder-notification")
        .background(T.surface.copy(alpha=.97f),RoundedCornerShape(T.previewRadius)).border(1.dp,T.border,RoundedCornerShape(T.previewRadius)).padding(T.gap),verticalArrangement=Arrangement.spacedBy(T.small)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)) {
            MenuIcon("reminders",T.accent)
            Text(tr("reminder_starts_soon"),color=T.text,fontSize=T.body,fontWeight=FontWeight.SemiBold)
        }
        Text(r.title,color=T.text,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(reminderCountdown(r,System.currentTimeMillis())+" · "+r.channelName+" · "+SimpleDateFormat("HH:mm",L10n.locale).format(Date(r.start)),color=T.muted,fontSize=T.micro)
        Row(horizontalArrangement=Arrangement.spacedBy(T.small)) {
            ReminderButton(tr("reminder_watch"),"reminder-watch",Modifier.focusRequester(first),onClick=onWatch)
            ReminderButton(tr("close"),"reminder-close",onClick=onClose)
        }
        if(!interacted)Text(tr("reminder_auto_close",seconds),Modifier.testTag("reminder-countdown"),color=T.muted,fontSize=T.micro)
    }
    }
    LaunchedEffect(Unit){first.requestFocus()}
    }
}
