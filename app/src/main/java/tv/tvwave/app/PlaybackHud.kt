package tv.tvwave.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Native remote-control preview, shared by live and archive playback. */
@Composable
fun PlaybackHud(
    title:String, range:String, live:Boolean, modeLabel:String, minHeight:Dp, playLabel:String, paused:Boolean,
    position:Long, duration:Long, available:Long, editing:Boolean,
    canRewind:Boolean, canForward:Boolean, canSeek:Boolean, subtitles:Boolean, favourite:Boolean,
    wanted:String, requests:MutableMap<String,FocusRequester>, onFocus:(String)->Unit,
    onAction:(String)->Unit, archiveDays:Int=0
) {
    val compact=LocalDensity.current.fontScale>1.1f
    val progress=if(duration>0)position.toFloat()/duration else 0f
    val loaded=if(duration>0)available.toFloat()/duration else 0f
    Column(Modifier.fillMaxWidth().heightIn(min=minHeight).testTag("player-hud").background(T.hud)
        .padding(horizontal=T.safe,vertical=if(compact)T.tiny else T.hudInset),verticalArrangement=Arrangement.SpaceBetween) {
        val timeline=remember{FocusRequester()};var focused by remember{mutableStateOf(false)}
        DisposableEffect(Unit){requests["player-timeline"]=timeline;onDispose{requests.remove("player-timeline")}}
        LaunchedEffect(wanted,canSeek){if(wanted=="player-timeline"&&canSeek)timeline.requestFocus()}
        Column(Modifier.fillMaxWidth().height(T.hudTimelineHeight).testTag("player-timeline")
            .focusRequester(timeline).onFocusChanged{focused=it.isFocused;if(it.isFocused)onFocus("player-timeline")}
            .border(if(focused)T.focusLine else 0.dp,if(focused)T.accent else T.transparent,RoundedCornerShape(T.radius))
            .clickable(enabled=canSeek,role=Role.Button){onAction("player-timeline")}
            .semantics{contentDescription=tr("programme_position");stateDescription=tr("of", playbackClock(position), playbackClock(duration))}
            .padding(horizontal=T.tiny),verticalArrangement=Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text(if(editing)tr("go_to", playbackClock(position))else if(duration>0)playbackClock(position)else tr("no_timing_information"),Modifier.testTag("player-position"),color=T.text,fontSize=T.hudLabel,lineHeight=T.hudLabel*1.2f)
                Text(if(duration>0)"−${playbackClock((duration-position).coerceAtLeast(0))}"else "",color=T.muted,fontSize=T.hudLabel,lineHeight=T.hudLabel*1.2f)
            }
            Canvas(Modifier.fillMaxWidth().height(T.hudKnob)){
                val y=size.height/2
                drawLine(T.hudTrack,Offset(0f,y),Offset(size.width,y),T.hudTrackHeight.toPx())
                drawLine(T.inputBorder,Offset(0f,y),Offset(size.width*loaded.coerceIn(0f,1f),y),T.hudTrackHeight.toPx())
                drawLine(T.accent,Offset(0f,y),Offset(size.width*progress.coerceIn(0f,1f),y),T.hudTrackHeight.toPx())
                if(duration>0)drawCircle(T.accent,T.hudKnob.toPx()/2,Offset(size.width*progress.coerceIn(0f,1f),y))
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(T.tiny)){
                Text(title,Modifier.testTag("player-programme-title"),color=T.text,fontSize=T.hudCompactTitle,lineHeight=T.hudCompactTitle*1.2f,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(range,color=T.muted,fontSize=T.hudLabel,lineHeight=T.hudLabel*1.2f,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            HudButton("player-subtitles",tr("subtitles"),if(subtitles)tr("on")else tr("off"),"subtitles",true,subtitles,wanted,requests,onFocus,onAction)
            HudButton("player-favourite",tr("favourites"),if(favourite)tr("added")else tr("add"),"star",true,favourite,wanted,requests,onFocus,onAction)
        }
        Box(Modifier.fillMaxWidth()){
            Row(Modifier.align(Alignment.Center),horizontalArrangement=Arrangement.spacedBy(T.small),verticalAlignment=Alignment.CenterVertically){
                HudButton("player-rewind",tr("1_min"),null,"rewind",canRewind,false,wanted,requests,onFocus,onAction,true,showLabel=false)
                HudButton("player-pause",playLabel,null,if(paused)"play"else"pause",true,false,wanted,requests,onFocus,onAction,true,showLabel=false)
                HudButton("player-forward",tr("1_min_m366"),null,"forward",canForward,false,wanted,requests,onFocus,onAction,true,showLabel=false)
            }
            val request=remember{FocusRequester()};var hasFocus by remember{mutableStateOf(false)}
            DisposableEffect(Unit){requests["player-live"]=request;onDispose{requests.remove("player-live")}}
            LaunchedEffect(wanted,live){if(wanted=="player-live"&&!live)request.requestFocus()}
            Column(Modifier.align(Alignment.CenterEnd).testTag("player-live").focusRequester(request)
                .onFocusChanged{hasFocus=it.isFocused;if(it.isFocused)onFocus("player-live")}
                .background(if(hasFocus)T.accent else T.transparent,RoundedCornerShape(T.buttonRadius))
                .border(if(hasFocus)T.focusLine else 0.dp,if(hasFocus)T.accent else T.transparent,RoundedCornerShape(T.buttonRadius))
                .clickable(enabled=!live,role=Role.Button){onAction("player-live")}.padding(T.small),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(T.tiny)){
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
                Box(Modifier.size(T.hudDot).background(if(live)T.liveDot else if(hasFocus)T.onAccent else T.muted,CircleShape))
                Text(if(live)tr("live_tv")else modeLabel,color=if(hasFocus)T.onAccent else T.text,fontSize=T.hudLabel,lineHeight=T.hudLabel*1.2f)
                }
                // Reserve the second line so focus never shifts the HUD layout.
                Box(contentAlignment=Alignment.Center){
                    Text(tr("go_live_m367"),Modifier.alpha(if(hasFocus&&!live)1f else 0f).clearAndSetSemantics{},color=if(hasFocus)T.onAccent else T.text,fontSize=T.hudLabel,lineHeight=T.hudLabel*1.2f)
                    if(archiveDays>0&&!(hasFocus&&!live))Row(Modifier.testTag("player-archive-days"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.tiny)){
                        ArchiveIcon(T.muted,Modifier.size(T.hudIcon))
                        Text(archiveDaysLabel(archiveDays),color=T.muted,fontSize=T.hudLabel,lineHeight=T.hudLabel*1.2f)
                    }
                }
            }
        }
    }
}

@Composable
private fun HudButton(key:String,label:String,status:String?,glyph:String,enabled:Boolean,selected:Boolean,
    wanted:String,requests:MutableMap<String,FocusRequester>,onFocus:(String)->Unit,onAction:(String)->Unit,transport:Boolean=false,showLabel:Boolean=true){
    val requester=remember{FocusRequester()};var focused by remember{mutableStateOf(false)}
    DisposableEffect(key){requests[key]=requester;onDispose{requests.remove(key)}}
    LaunchedEffect(wanted,enabled){if(enabled&&wanted==key)requester.requestFocus()}
    val foreground=if(focused)T.onAccent else T.text
    Column(Modifier.width((if(transport)T.transportWidth else T.hudUtilityWidth)*maxOf(1f,LocalDensity.current.fontScale))
        .heightIn(min=T.transportHeight).testTag(key).alpha(if(enabled)1f else T.hudDisabledAlpha)
        .focusRequester(requester).onFocusChanged{focused=it.isFocused;if(it.isFocused)onFocus(key)}
        .clickable(enabled=enabled,role=Role.Button){onAction(key)}
        .semantics{contentDescription=if(status!=null)"${label} · ${status}"else label}
        .padding(if(LocalDensity.current.fontScale>1.1f)T.focusGap else T.tiny),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(T.tiny)){
        Box(Modifier.size(if(transport)T.transportCircle else T.hudUtilityCircle)
            .background(if(focused)T.accent else T.transparent,CircleShape)
            .border(if(focused)T.focusLine else 1.dp,if(focused)T.accent else T.muted,CircleShape),contentAlignment=Alignment.Center){
        Canvas(Modifier.size(if(transport)T.transportIcon else T.hudIcon).testTag("${key}-icon")){
            val w=size.width;val h=size.height;val stroke=Stroke(w*.065f)
            when(glyph){
                "star"->{val path=Path();repeat(10){i->val a=-Math.PI/2+i*Math.PI/5;val r=if(i%2==0).46f else .21f;val x=w*(.5f+r*kotlin.math.cos(a).toFloat());val y=h*(.5f+r*kotlin.math.sin(a).toFloat());if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};path.close();drawPath(path,foreground,style=if(selected)androidx.compose.ui.graphics.drawscope.Fill else stroke)}
                "pause"->{drawRect(foreground,Offset(w*.24f,h*.16f),Size(w*.16f,h*.68f));drawRect(foreground,Offset(w*.60f,h*.16f),Size(w*.16f,h*.68f))}
                "play"->{drawPath(Path().apply{moveTo(w*.28f,h*.12f);lineTo(w*.85f,h*.5f);lineTo(w*.28f,h*.88f);close()},foreground)}
                "rewind","forward"->{val forward=glyph=="forward";repeat(2){i->val left=w*(.12f+i*.38f);val right=left+w*.34f;drawPath(Path().apply{moveTo(if(forward)left else right,h*.22f);lineTo(if(forward)right else left,h*.5f);lineTo(if(forward)left else right,h*.78f);close()},foreground)}}
                else->{drawRect(foreground,Offset(w*.08f,h*.18f),Size(w*.84f,h*.64f),style=stroke);drawLine(foreground,Offset(w*.2f,h*.43f),Offset(w*.8f,h*.43f),w*.06f);drawLine(foreground,Offset(w*.2f,h*.61f),Offset(w*.43f,h*.61f),w*.06f);drawLine(foreground,Offset(w*.55f,h*.61f),Offset(w*.8f,h*.61f),w*.06f)}
            }
        }
        }
        if(showLabel)Text(label,color=T.text,fontSize=T.hudLabel,lineHeight=T.hudLabel*1.2f,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
