package tv.tvwave.app

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun UpdateReleaseBanner(release:AppRelease){
    val date=remember(release.publishedAt,L10n.language){
        runCatching{
            val parser=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("UTC")}
            parser.parse(release.publishedAt)?.let{SimpleDateFormat("d MMM yyyy",L10n.locale).format(it)}
        }.getOrNull()
    }
    val size=String.format(L10n.locale,"%.1f MB",release.size/1048576.0)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(T.radius))
        .background(Brush.linearGradient(listOf(Color(0xFF214B70),Color(0xFF203C42)))).testTag("update-banner")){
        Canvas(Modifier.matchParentSize()){
            val w=this.size.width;val h=this.size.height
            val wave=Path().apply{moveTo(-w*.05f,h*.8f);cubicTo(w*.18f,-h*.5f,w*.32f,h*1.65f,w*.58f,h*.5f);cubicTo(w*.8f,-h*.6f,w*.9f,-h*.4f,w*1.1f,h*.55f)}
            drawPath(wave,Color(0xFF79BEEC).copy(alpha=.45f),style=Stroke(1.5.dp.toPx()))
        }
        Column(Modifier.padding(horizontal=T.gap,vertical=T.small)){
            Text(tr("update_release_available"),color=Color(0xFFDCEBFA),fontSize=T.caption)
            Text(release.version,color=Color.White,fontSize=36.sp,lineHeight=40.sp,fontWeight=FontWeight.Bold)
            Text(listOfNotNull(date,size).joinToString(" · "),color=Color(0xFFDCEBFA),fontSize=T.micro)
        }
    }
}

@Composable
fun UpdateReleaseNotes(notes:String,modifier:Modifier,request:FocusRequester,action:FocusRequester){
    val scroll=rememberScrollState()
    val scope=rememberCoroutineScope()
    var focused by remember{mutableStateOf(false)}
    LaunchedEffect(notes){scroll.scrollTo(0)}
    // Preserve the publisher's order and wording. Category styling is only for actual headings.
    val paragraphs=remember(notes){notes.lines().map{it.trim()}.filter{it.isNotEmpty()}}
    val headings=setOf("новое","улучшено","исправлено","new","improved","fixed","what’s new","what's new")
    Box(modifier.testTag("update-notes").focusRequester(request)
        .onFocusChanged{focused=it.isFocused}
        .border(T.focusLine,if(focused)T.accent else Color.Transparent,RoundedCornerShape(T.radius))
        .onPreviewKeyEvent{event->
            val key=event.nativeKeyEvent
            if(key.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN))false
            else {
                if(key.action==KeyEvent.ACTION_DOWN){
                    if(key.keyCode==KeyEvent.KEYCODE_DPAD_UP&&scroll.value==0)action.requestFocus()
                    else {
                        val delta=(scroll.viewportSize*.65f).toInt().coerceAtLeast(1)*(if(key.keyCode==KeyEvent.KEYCODE_DPAD_DOWN)1 else -1)
                        scope.launch{scroll.scrollTo((scroll.value+delta).coerceIn(0,scroll.maxValue))}
                    }
                }
                true
            }
        }.focusable().padding(T.small)){
        Column(Modifier.fillMaxSize().padding(end=12.dp).verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(T.small)){
            paragraphs.forEach{raw->
                val heading=raw.removeSuffix(":").lowercase(Locale.ROOT) in headings
                Text(if(raw.startsWith("- "))"• "+raw.drop(2)else raw,color=if(heading)T.success else T.text,
                    fontSize=T.caption,lineHeight=T.caption*1.4f,fontWeight=if(heading)FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        if(scroll.maxValue>0)Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp).testTag("update-notes-scrollbar")){
            drawRoundRect(T.border,cornerRadius=androidx.compose.ui.geometry.CornerRadius(size.width))
            val thumb=(size.height*scroll.viewportSize/(scroll.maxValue+scroll.viewportSize).toFloat()).coerceIn(12.dp.toPx().coerceAtMost(size.height),size.height)
            val y=(size.height-thumb)*scroll.value/scroll.maxValue
            drawRoundRect(T.muted,topLeft=Offset(0f,y),size=androidx.compose.ui.geometry.Size(size.width,thumb),cornerRadius=androidx.compose.ui.geometry.CornerRadius(size.width))
        }
    }
}
