package tv.tvwave.app

import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

class GuideSearchState {
    var query by mutableStateOf("")
    var submittedQuery by mutableStateOf("")
    var searchRevision by mutableIntStateOf(0)
    var selectedKey by mutableStateOf("")
    var returnToResults=false
    var results by mutableStateOf(GuideSearchResults())
    var completedQuery by mutableStateOf("")
    var recentQueries by mutableStateOf<List<String>>(emptyList())
    fun submit(){
        submittedQuery=query.trim();selectedKey="";searchRevision++
    }
    fun beginVisit(recent:List<String>) {
        query="";submittedQuery="";selectedKey="";returnToResults=false
        results=GuideSearchResults();completedQuery="";recentQueries=recent
    }
}
private data class SearchEntry(val key:String,val channel:Channel,val programme:Programme?=null)

/** One remote-controlled list: channels always precede programme matches. */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun SearchScreen(
    state:GuideSearchState,loading:Boolean,error:String,detail:Programme?,
    brand:@Composable ()->Unit,
    field:@Composable (Modifier,Boolean,()->Unit,(Boolean)->Unit)->Unit,
    logo:@Composable (Channel,Color)->Unit,
    archiveAvailable:(Channel,Programme)->Boolean,
    span:(Programme)->String,now:Long,hasEpg:Boolean,
    onSelected:(Channel,Programme?)->Unit,onOpen:(Channel,Programme?)->Unit,
    onRememberQuery:(String)->Unit,onRemoveQuery:(String)->Unit,
    onTv:()->Unit,onFavourites:()->Unit,onSettings:()->Unit
) {
    val ready=state.completedQuery==state.submittedQuery
    val results=if(ready)state.results else GuideSearchResults()
    val entries=remember(results){results.channels.map{SearchEntry("channel:${it.id}",it)}+results.programmes.map{SearchEntry(it.key,it.channel,it.programme)}}
    val index=entries.indexOfFirst{it.key==state.selectedKey}.coerceAtLeast(0)
    val selected=entries.getOrNull(index)
    val list=rememberLazyListState()
    val listFocus=remember{FocusRequester()};val inputFocus=remember{FocusRequester()};val detailFocus=remember{FocusRequester()}
    val tvFocus=remember{FocusRequester()};val favouritesFocus=remember{FocusRequester()};val settingsFocus=remember{FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    var resultsFocused by remember{mutableStateOf(false)}
    val restoreResults=remember{state.returnToResults}
    var recentIndex by remember { mutableIntStateOf(0) }
    val showingHistory=state.submittedQuery.isBlank()
    var pressedRecent by remember { mutableStateOf<String?>(null) }
    var recentDownTime by remember { mutableLongStateOf(0L) }
    var recentLongPressHandled by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<String?>(null) }
    var deletionOpeningDownTime by remember { mutableStateOf<Long?>(null) }
    var restoreFocusRevision by remember { mutableIntStateOf(0) }
    fun dismissDeletion(){pendingDeletion=null;restoreFocusRevision++}
    fun confirmHeldQueryDeletion(){
        val query=pressedRecent?:return
        if(!recentLongPressHandled){
            recentLongPressHandled=true
            deletionOpeningDownTime=recentDownTime
            pendingDeletion=query
        }
    }
    LaunchedEffect(pressedRecent,resultsFocused,showingHistory){
        if(pressedRecent!=null&&resultsFocused&&showingHistory){
            delay(ViewConfiguration.getLongPressTimeout().toLong())
            confirmHeldQueryDeletion()
        }
    }
    LaunchedEffect(restoreFocusRevision){
        if(restoreFocusRevision>0){
            withFrameNanos{}
            if(state.recentQueries.isNotEmpty())listFocus.requestFocus()else inputFocus.requestFocus()
        }
    }
    LaunchedEffect(showingHistory,recentIndex,state.recentQueries){
        if(showingHistory&&state.recentQueries.isNotEmpty()){
            recentIndex=recentIndex.coerceIn(state.recentQueries.indices)
            val row=recentIndex+1
            val layout=list.layoutInfo
            val item=layout.visibleItemsInfo.firstOrNull{it.index==row}
            val headerHeight=layout.visibleItemsInfo.firstOrNull{it.key=="recent-heading"}?.size?:0
            if(recentIndex==0)list.scrollToItem(0)
            else if(item==null||item.offset<layout.viewportStartOffset+headerHeight||item.offset+item.size>layout.viewportEndOffset)
                list.scrollToItem(row,-headerHeight)
        }
    }
    fun focusResults(){keyboard?.hide();state.returnToResults=true;listFocus.requestFocus()}
    fun submitSearch(){state.submit();onRememberQuery(state.submittedQuery);focusResults()}
    fun moveToResults(){
        if(if(showingHistory)state.recentQueries.isNotEmpty()else entries.isNotEmpty())focusResults()
    }
    fun sidebarNavigation(up:FocusRequester,down:FocusRequester?)=Modifier.onPreviewKeyEvent{event->
        val key=event.nativeKeyEvent
        when(key.keyCode){
            KeyEvent.KEYCODE_DPAD_UP->{if(key.action==KeyEvent.ACTION_DOWN)up.requestFocus();true}
            KeyEvent.KEYCODE_DPAD_DOWN->{if(key.action==KeyEvent.ACTION_DOWN)down?.requestFocus();true}
            KeyEvent.KEYCODE_DPAD_RIGHT->{if(key.action==KeyEvent.ACTION_DOWN)moveToResults();true}
            else->false
        }
    }
    fun choose(index:Int){entries.getOrNull(index)?.let{state.selectedKey=it.key}}
    fun repeatQuery(query:String){state.query=query;submitSearch()}
    fun open(){
        if(showingHistory) {
            state.recentQueries.getOrNull(recentIndex)?.let{repeatQuery(it)}
        }else selected?.let{onRememberQuery(state.submittedQuery);state.returnToResults=true;onOpen(it.channel,it.programme)}
    }
    LaunchedEffect(selected?.key){selected?.let{onSelected(it.channel,it.programme)}}
    LaunchedEffect(selected?.key,results) {
        if(selected!=null) {
            state.selectedKey=selected.key
            val row=index+(if(results.channels.isNotEmpty())1 else 0)+(if(selected.programme!=null)1 else 0)
            val layout=list.layoutInfo
            val item=layout.visibleItemsInfo.firstOrNull{it.index==row}
            val firstInSection=index==0||index==results.channels.size
            val headerHeight=layout.visibleItemsInfo.firstOrNull { it.key=="channels-heading"||it.key=="programmes-heading" }?.size?:0
            if(firstInSection)list.scrollToItem(row-1)
            else if(item==null||item.offset<layout.viewportStartOffset+headerHeight||item.offset+item.size>layout.viewportEndOffset)
                list.scrollToItem(row,-headerHeight)

        }
    }
    LaunchedEffect(Unit){
        if(restoreResults){delay(180);listFocus.requestFocus()}
        else if(state.recentQueries.isNotEmpty()){delay(180);inputFocus.requestFocus()}
    }
    fun status(channel:Channel,p:Programme)=when {
        p.start>now->tr("search_upcoming")
        p.end>now->tr("live_now")
        archiveAvailable(channel,p)->tr("search_recording")
        else->tr("search_no_recording")
    }
    Column(Modifier.fillMaxSize().background(T.bg).padding(T.pad).testTag("global-search"),verticalArrangement=Arrangement.spacedBy(T.gap)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.pad)) {
            Box(Modifier.width(T.searchSidebarWidth)){brand()}
            Text(tr("search"),color=T.text,fontSize=T.title,fontWeight=FontWeight.SemiBold)
        }
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(T.gap)) {
            Column(Modifier.width(T.searchSidebarWidth).fillMaxHeight().background(T.surface,RoundedCornerShape(T.previewRadius)).padding(T.small),verticalArrangement=Arrangement.spacedBy(T.gap)) {
                field(Modifier.testTag("search-input").focusRequester(inputFocus).onPreviewKeyEvent{event->
                    val key=event.nativeKeyEvent
                    when(key.keyCode){
                        KeyEvent.KEYCODE_DPAD_DOWN->{if(key.action==KeyEvent.ACTION_DOWN){keyboard?.hide();tvFocus.requestFocus()};true}
                        KeyEvent.KEYCODE_DPAD_RIGHT->{if(key.action==KeyEvent.ACTION_DOWN)moveToResults();true}
                        else->false
                    }
                },!restoreResults&&state.recentQueries.isEmpty(),{submitSearch()},{if(it)state.returnToResults=false})
                SearchNav(tr("search"),"search-nav-input",sidebarNavigation(inputFocus,tvFocus)){inputFocus.requestFocus()}
                SearchNav(tr("tv"),"search-nav-tv",Modifier.focusRequester(tvFocus).then(sidebarNavigation(inputFocus,favouritesFocus)),onClick=onTv)
                SearchNav(BuiltinGroups.label(BuiltinGroups.FAVOURITES),"search-nav-favourites",Modifier.focusRequester(favouritesFocus).then(sidebarNavigation(tvFocus,settingsFocus)),onClick=onFavourites)
                Spacer(Modifier.weight(1f))
                SearchNav(tr("settings"),"search-nav-settings",Modifier.focusRequester(settingsFocus).then(sidebarNavigation(favouritesFocus,null)),onClick=onSettings)
            }
            Column(Modifier.weight(T.searchResultsWeight).fillMaxHeight().testTag("search-results")
                .focusRequester(listFocus).onFocusChanged{resultsFocused=it.isFocused;if(!it.isFocused)pressedRecent=null}
                .onPreviewKeyEvent{event->
                    val key=event.nativeKeyEvent
                    when(key.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_UP->{
                            if(key.action==KeyEvent.ACTION_DOWN) {
                                pressedRecent=null
                                val step=if(key.keyCode==KeyEvent.KEYCODE_DPAD_DOWN)1 else -1
                                if(showingHistory)recentIndex=(recentIndex+step).coerceIn(0,(state.recentQueries.size-1).coerceAtLeast(0))
                                else choose((index+step).coerceIn(0,(entries.size-1).coerceAtLeast(0)))
                            };true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT->{if(key.action==KeyEvent.ACTION_DOWN)inputFocus.requestFocus();true}
                        KeyEvent.KEYCODE_DPAD_RIGHT->{if(key.action==KeyEvent.ACTION_DOWN&&selected!=null)detailFocus.requestFocus();true}
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{
                            if(showingHistory){
                                when(key.action){
                                    KeyEvent.ACTION_DOWN->if(key.repeatCount==0){
                                        pressedRecent=state.recentQueries.getOrNull(recentIndex)
                                        recentDownTime=key.downTime;recentLongPressHandled=false
                                    }else if(key.isLongPress){confirmHeldQueryDeletion()}
                                    KeyEvent.ACTION_UP->{
                                        val pressed=pressedRecent
                                        if(pressed!=null&&!recentLongPressHandled&&!key.isCanceled){
                                            if(key.eventTime-key.downTime>=ViewConfiguration.getLongPressTimeout())confirmHeldQueryDeletion()
                                            else repeatQuery(pressed)
                                        }
                                        pressedRecent=null
                                    }
                                }
                            }else if(key.action==KeyEvent.ACTION_DOWN&&key.repeatCount==0)open()
                            true
                        }
                        else->false
                    }
                }.focusable(),verticalArrangement=Arrangement.spacedBy(T.small)) {
                if(loading){
                    Text(tr("search_loading"),color=T.muted,fontSize=T.micro)
                    LinearProgressIndicator(Modifier.fillMaxWidth(),color=T.accent,trackColor=T.border)
                }
                if(error.isNotEmpty())Text(error,color=T.danger,fontSize=T.caption)
                if(showingHistory&&state.recentQueries.isEmpty())Text(tr("search_prompt"),color=T.muted,fontSize=T.caption)
                else if(!showingHistory&&!loading&&ready&&entries.isEmpty()&&error.isEmpty())Text(tr("search_empty"),Modifier.testTag("search-empty"),color=T.muted,fontSize=T.caption)
                LazyColumn(state=list,modifier=Modifier.weight(1f).padding(T.focusGap),verticalArrangement=Arrangement.spacedBy(T.tiny)) {
                    if(showingHistory&&state.recentQueries.isNotEmpty()) {
                        stickyHeader(key="recent-heading"){SearchHeading(tr("search_recent"),"search-recent-heading")}
                        itemsIndexed(state.recentQueries,key={_,q->"recent:$q"}){i,q->
                            val focused=resultsFocused&&recentIndex==i
                            SearchRow("recent:$q",q,focused,logo={MenuIcon("search",if(focused)T.onAccent else T.muted,Modifier.size(24.dp))}){
                                recentIndex=i;open()
                            }
                        }
                    }
                    if(results.channels.isNotEmpty())stickyHeader(key="channels-heading"){SearchHeading(tr("search_channels_count",results.channels.size),"search-channels-heading")}
                    itemsIndexed(results.channels,key={_,c->"channel:${c.id}"}){i,c->
                        SearchRow("channel:${c.id}",c.name,resultsFocused&&index==i,
                            logo={logo(c,if(resultsFocused&&index==i)T.onAccent else T.text)}){choose(i);focusResults();onOpen(c,null)}
                    }
                    if(results.programmes.isNotEmpty())stickyHeader(key="programmes-heading"){
                        SearchHeading(if(results.programmeCount>results.programmes.size)tr("search_programmes_limit",results.programmes.size,results.programmeCount)else tr("search_programmes_count",results.programmeCount),"search-programmes-heading")
                    }
                    itemsIndexed(results.programmes,key={_,p->p.key}){i,p->
                        val selectedIndex=i+results.channels.size
                        SearchRow(p.key,p.programme.title,resultsFocused&&index==selectedIndex,
                            logo={logo(p.channel,if(resultsFocused&&index==selectedIndex)T.onAccent else T.text)}){choose(selectedIndex);focusResults();onOpen(p.channel,p.programme)}
                    }
                    if(!hasEpg&&state.submittedQuery.isNotBlank())item(key="no-epg"){Text(tr("search_no_epg"),color=T.muted,fontSize=T.micro)}
                }
            }
            Column(Modifier.weight(T.searchDetailsWeight).fillMaxHeight().background(T.surface,RoundedCornerShape(T.previewRadius)).padding(T.gap),verticalArrangement=Arrangement.spacedBy(T.small)) {
                if(selected!=null) {
                    val descriptionScroll=rememberScrollState()
                    LaunchedEffect(selected.key){descriptionScroll.scrollTo(0)}
                    Column(Modifier.weight(1f).verticalScroll(descriptionScroll),verticalArrangement=Arrangement.spacedBy(T.small)){
                    Row(Modifier.fillMaxWidth().background(T.raised,RoundedCornerShape(T.radius)).padding(T.gap),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
                        logo(selected.channel,T.text)
                        Text(selected.channel.name,color=T.text,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                    if(selected.channel.group.isNotBlank())Text(selected.channel.group,color=T.muted,fontSize=T.micro)
                    val p=selected.programme?.let{detail?.takeIf{d->d.start==it.start}?:it}?:detail
                    Text(if(selected.programme==null)tr("now")else tr("programme_details"),color=T.muted,fontSize=T.micro)
                    Text(p?.title?:selected.channel.name,Modifier.testTag("search-detail-title"),color=T.text,fontSize=T.searchTitle,fontWeight=FontWeight.SemiBold,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(p!=null){
                        Text(span(p),color=T.muted,fontSize=T.micro)
                        Text(status(selected.channel,p),Modifier.testTag("search-detail-status"),color=if(archiveAvailable(selected.channel,p))T.success else T.muted,fontSize=T.caption)
                    }
                    Text(p?.description?.ifBlank{tr("the_epg_source_did_not_provide_a_description")}?:tr("no_programme_guide_yet"),color=T.muted,fontSize=T.caption,lineHeight=T.caption*1.35f,maxLines=9,overflow=TextOverflow.Ellipsis)
                    }
                    SearchNav(tr(if(selected.programme==null)"watch" else "search_details"),"search-open",Modifier.focusRequester(detailFocus).onPreviewKeyEvent{e->
                        if(e.nativeKeyEvent.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){if(e.nativeKeyEvent.action==KeyEvent.ACTION_DOWN)focusResults();true}else false
                    }){open()}
                }else Text(tr("search_select_result"),color=T.muted,fontSize=T.caption)
            }
        }
        Text(tr(if(showingHistory&&state.recentQueries.isNotEmpty())"search_history_hint" else "search_remote_hint"),color=T.muted,fontSize=T.micro,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
    pendingDeletion?.let{query->
        SearchHistoryDeleteDialog(query,deletionOpeningDownTime,onDismiss={dismissDeletion()},onDelete={
            onRemoveQuery(query)
            recentIndex=recentIndex.coerceIn(0,(state.recentQueries.size-1).coerceAtLeast(0))
            dismissDeletion()
        })
    }
}

@Composable private fun SearchHistoryDeleteDialog(query:String,openingKeyDownTime:Long?,onDismiss:()->Unit,onDelete:()->Unit){
    val cancelFocus=remember{FocusRequester()}
    val deleteFocus=remember{FocusRequester()}
    var blockedOkDownTime by remember{mutableStateOf(openingKeyDownTime)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Column(Modifier.width(500.dp).background(T.surface,RoundedCornerShape(T.previewRadius)).padding(T.pad)
            .onPreviewKeyEvent{event->
                val key=event.nativeKeyEvent
                // The held key that opened the dialog must not also activate a button.
                if(blockedOkDownTime!=null&&key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)){
                    if(key.downTime==blockedOkDownTime){
                        if(key.action==KeyEvent.ACTION_UP)blockedOkDownTime=null
                        true
                    }else{blockedOkDownTime=null;false}
                }else false
            }
            .semantics{testTagsAsResourceId=true}.testTag("search-history-delete-dialog"),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(T.gap)){
            Text(tr("search_history_delete_title"),color=T.text,fontSize=T.title,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
            Text(tr("search_history_delete_query",query),color=T.muted,fontSize=T.caption,textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(T.small))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(T.gap)){
                SearchHistoryDialogButton(tr("cancel_m245"),"search-history-delete-cancel",
                    Modifier.weight(1f).focusRequester(cancelFocus).focusProperties{left=FocusRequester.Cancel;right=deleteFocus},onDismiss)
                SearchHistoryDialogButton(tr("delete"),"search-history-delete-confirm",
                    Modifier.weight(1f).focusRequester(deleteFocus).focusProperties{left=cancelFocus;right=FocusRequester.Cancel},onDelete)
            }
        }
        LaunchedEffect(Unit){cancelFocus.requestFocus()}
    }
}

@Composable private fun SearchHistoryDialogButton(label:String,key:String,modifier:Modifier,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    Box(modifier.testTag(key).onFocusChanged{focused=it.isFocused}
        .border(T.focusLine,if(focused)T.text else Color.Transparent,RoundedCornerShape(T.buttonRadius))
        .padding(T.focusLine+T.focusGap)
        .background(if(focused)T.accent else Color.Transparent,RoundedCornerShape(T.radius))
        .border(T.line,if(focused)Color.Transparent else T.inputBorder,RoundedCornerShape(T.radius))
        .clickable(interactionSource=remember{MutableInteractionSource()},indication=null,onClick=onClick)
        .heightIn(min=T.actionHeight).padding(horizontal=T.small,vertical=T.small),contentAlignment=Alignment.Center){
        Text(label,color=if(focused)T.onAccent else T.text,fontSize=T.body,fontWeight=FontWeight.Medium)
    }
}

@Composable private fun SearchHeading(label:String,key:String){
    Text(label,Modifier.fillMaxWidth().background(T.bg).testTag(key).padding(vertical=T.small),
        color=T.text,fontSize=T.caption,fontWeight=FontWeight.SemiBold)
}

@Composable private fun SearchRow(key:String,title:String,selected:Boolean,logo:(@Composable ()->Unit)?=null,onClick:()->Unit) {
    val color=if(selected)T.onAccent else T.text
    Row(Modifier.fillMaxWidth().heightIn(min=44.dp).testTag("search-hit:$key").background(if(selected)T.accent else T.surface,RoundedCornerShape(T.radius))
        .border(T.focusLine,if(selected)T.text else Color.Transparent,RoundedCornerShape(T.radius))
        .semantics{this.selected=selected;focused=selected}.focusProperties{canFocus=false}
        .clickable(interactionSource=remember{MutableInteractionSource()},indication=null,onClick=onClick)
        .padding(horizontal=T.small,vertical=T.tiny),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
        Box(Modifier.size(T.channelLogo),contentAlignment=Alignment.Center){logo?.invoke()}
        Text(title,Modifier.weight(1f),color=color,fontSize=T.caption,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable private fun SearchNav(label:String,key:String,modifier:Modifier=Modifier,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    val fg=if(focused)T.onAccent else T.text
    Row(modifier.fillMaxWidth().testTag(key).onFocusChanged{focused=it.isFocused}
        .background(if(focused)T.accent else Color.Transparent,RoundedCornerShape(T.radius))
        .border(T.focusLine,if(focused)T.text else Color.Transparent,RoundedCornerShape(T.radius))
        .clickable(interactionSource=remember{MutableInteractionSource()},indication=null,onClick=onClick).padding(T.small),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
        val icon=when(key){"search-nav-input"->"search";"search-nav-tv"->"tv";"search-nav-favourites"->"favourites";"search-nav-settings"->"settings";else->null}
        if(icon!=null)MenuIcon(icon,fg,Modifier.size(T.menuIcon))
        Text(label,Modifier.weight(1f),color=fg,fontSize=T.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}
