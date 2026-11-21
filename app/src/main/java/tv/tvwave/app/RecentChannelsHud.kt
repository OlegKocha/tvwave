package tv.tvwave.app

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RecentChannelsHud(
    channels:List<Channel>, logo:@Composable (Channel, Color)->Unit,
    onWatch:(Channel)->Unit, onClose:()->Unit, onRemove:(String)->Unit
) {
    var index by remember { mutableIntStateOf(0) }
    val selectedIndex=index.coerceAtMost(channels.lastIndex)
    val channel=channels.getOrNull(selectedIndex)
    var pending by remember { mutableStateOf<Channel?>(null) }
    var openingTime by remember { mutableStateOf<Long?>(null) }
    val row=rememberLazyListState()
    val focus=remember { FocusRequester() }
    val scope=rememberCoroutineScope()
    val press=remember { ProgrammeOkPress(scope) }
    DisposableEffect(Unit){ onDispose { press.cancel() } }
    LaunchedEffect(pending){
        if(pending==null)focus.requestFocus()
    }
    LaunchedEffect(channels,selectedIndex){
        index=selectedIndex.coerceAtLeast(0)
        if(selectedIndex>=0){
            val info=row.layoutInfo
            val item=info.visibleItemsInfo.firstOrNull{it.index==selectedIndex}
            if(item==null || item.offset<info.viewportStartOffset || item.offset+item.size>info.viewportEndOffset)
                row.scrollToItem(selectedIndex)
        }
    }
    Column(Modifier.fillMaxWidth().background(T.browserSurface).testTag("player-recent")
        .onPreviewKeyEvent { event->
            val key=event.nativeKeyEvent
            when(key.keyCode){
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if(channel==null)true else press.handle(key,{onWatch(channel)},{
                        openingTime=key.downTime
                        pending=channel
                    })
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if(key.action==KeyEvent.ACTION_DOWN && key.repeatCount==0){press.cancel();onClose()}
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(key.action==KeyEvent.ACTION_DOWN && channels.isNotEmpty()){
                        press.cancel()
                        val step=if(key.keyCode==KeyEvent.KEYCODE_DPAD_LEFT)-1 else 1
                        index=(selectedIndex+step).coerceIn(channels.indices)
                    }
                    true
                }
                else -> false
            }
        }.focusRequester(focus).focusable().padding(horizontal=T.safe,vertical=T.pad),
        verticalArrangement=Arrangement.spacedBy(T.small)){
        Text(tr("recent_channels"),fontSize=T.title,color=T.text,fontWeight=FontWeight.SemiBold)
        if(channels.isEmpty())Text(tr("recent_channels_empty"),color=T.muted,fontSize=T.body)
        else LazyRow(state=row,horizontalArrangement=Arrangement.spacedBy(T.small)){
            itemsIndexed(channels,key={_,item->item.id}){i,item->
                val chosen=i==selectedIndex
                val color=if(chosen)T.onAccent else T.text
                val shape=RoundedCornerShape(T.radius)
                Row(Modifier.width(186.dp).heightIn(min=62.dp).testTag("recent-channel:"+item.id)
                    .semantics{selected=chosen}
                    .border(T.focusLine,if(chosen)T.text else T.border,shape)
                    .padding(T.focusLine+T.focusGap)
                    .background(if(chosen)T.accent else T.raised,shape)
                    .combinedClickable(onLongClick={index=i;openingTime=null;pending=item},onClick={onWatch(item)})
                    .padding(T.small),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(T.small)){
                    logo(item,color)
                    Text(item.name,color=color,fontSize=T.body,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
    pending?.let{item->
        HistoryDeleteDialog(tr("recent_channel_item",item.name),"recent-channel",openingTime,
            onDismiss={pending=null},onDelete={onRemove(item.id);pending=null})
    }
}
