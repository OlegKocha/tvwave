package tv.tvwave.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*

/** Stable menu typography, also used by its inline search input. */
fun menuLabelStyle() = TextStyle(
    fontFamily=FontFamily.SansSerif,
    fontSize=T.body,
    fontWeight=FontWeight.Normal,
    lineHeight=T.body*1.3f,
    letterSpacing=.5.sp
)

/** Screen-and-wave brand mark, kept separate from the menu's TV action. */
@Composable fun BrandMark(modifier:Modifier=Modifier){
    Image(painterResource(R.drawable.tvwave_mark),contentDescription="TV Wave",modifier=modifier.size(T.brandMark))
}

@Composable fun BrandHeader(){
    Row(Modifier.testTag("main-brand"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(T.small)){
        BrandMark(Modifier.clearAndSetSemantics{})
        Text("TV Wave",color=T.text,fontSize=T.body,fontWeight=FontWeight.SemiBold,maxLines=1,softWrap=false,overflow=TextOverflow.Clip)
    }
}

/** Original vector symbols, shared by the collapsed rail and labelled menu. */
@Composable fun MenuIcon(kind:String,color:Color,modifier:Modifier=Modifier){
    Canvas(modifier.size(T.menuIcon)){
        val w=size.width;val h=size.height;val line=w*.075f;val stroke=Stroke(line)
        when(kind){
            "search"->{drawCircle(color,w*.28f,Offset(w*.42f,h*.42f),style=stroke);drawLine(color,Offset(w*.63f,h*.63f),Offset(w*.9f,h*.9f),line)}
            "tv"->{drawRoundRect(color,Offset(w*.08f,h*.12f),Size(w*.84f,h*.6f),CornerRadius(w*.08f),style=stroke);drawLine(color,Offset(w*.5f,h*.74f),Offset(w*.5f,h*.9f),line);drawLine(color,Offset(w*.3f,h*.9f),Offset(w*.7f,h*.9f),line)}
            "favourites"->{val path=Path();repeat(10){i->val a=-Math.PI/2+i*Math.PI/5;val r=if(i%2==0).46f else .21f;val x=w*(.5f+r*kotlin.math.cos(a).toFloat());val y=h*(.5f+r*kotlin.math.sin(a).toFloat());if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};path.close();drawPath(path,color,style=stroke)}
            "reminders"->{
                val path=Path().apply{moveTo(w*.2f,h*.72f);lineTo(w*.27f,h*.6f);lineTo(w*.27f,h*.36f);cubicTo(w*.27f,h*.06f,w*.73f,h*.06f,w*.73f,h*.36f);lineTo(w*.73f,h*.6f);lineTo(w*.8f,h*.72f);close()}
                drawPath(path,color,style=stroke);drawArc(color,0f,180f,false,Offset(w*.4f,h*.75f),Size(w*.2f,h*.15f),style=stroke)
            }
            "settings"->{drawCircle(color,w*.29f,Offset(w*.5f,h*.5f),style=stroke);drawCircle(color,w*.1f,Offset(w*.5f,h*.5f),style=stroke);repeat(8){i->val a=i*Math.PI/4;val x=kotlin.math.cos(a).toFloat();val y=kotlin.math.sin(a).toFloat();drawLine(color,Offset(w*(.5f+x*.3f),h*(.5f+y*.3f)),Offset(w*(.5f+x*.45f),h*(.5f+y*.45f)),line)}}
        }
    }
}

@Composable fun MenuRail(active:String,onExpand:(String)->Unit){
    @Composable fun Item(key:String,label:String){
        Box(Modifier.size(T.railAction).testTag("rail-${key}")
            .background(if(active==key)T.railSelected else T.transparent,RoundedCornerShape(T.radius))
            .focusProperties{canFocus=false}
            .clickable(role=Role.Button){onExpand("main-${key}")}
            .semantics{contentDescription=label;selected=active==key},contentAlignment=Alignment.Center){
            MenuIcon(key,if(active==key)T.accent else T.muted)
        }
    }
    Column(Modifier.fillMaxSize().testTag("main-rail").background(T.raised).padding(top=T.drawerTop,bottom=T.drawerBottom),horizontalAlignment=Alignment.CenterHorizontally){
        BrandMark(Modifier.testTag("rail-brand"))
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement=Arrangement.spacedBy(T.small)){
            Item("search",tr("search"));Item("tv",tr("tv"));Item("favourites",tr("favourites"));Item("reminders",tr("reminders"))
        }
        Spacer(Modifier.weight(1f))
        Item("settings",tr("settings"))
        Spacer(Modifier.height(T.railFooter))
    }
}
