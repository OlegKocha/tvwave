package tv.tvwave.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

fun archiveDaysLabel(days:Int):String = L10n.days(days)

/** Original clock / backwards arrow vector; transparent background. */
@Composable fun ArchiveIcon(color:Color,modifier:Modifier=Modifier){
    Canvas(modifier.semantics{contentDescription=tr("catch_up_available")}){
        val w=size.width;val h=size.height;val line=w*.075f
        drawArc(color,215f,305f,false,Offset(w*.18f,h*.15f),Size(w*.73f,h*.73f),style=Stroke(line,cap=StrokeCap.Round))
        drawPath(Path().apply{moveTo(w*.08f,h*.17f);lineTo(w*.08f,h*.40f);lineTo(w*.31f,h*.40f)},color,style=Stroke(line,cap=StrokeCap.Round,join=StrokeJoin.Round))
        drawPath(Path().apply{moveTo(w*.55f,h*.32f);lineTo(w*.55f,h*.53f);lineTo(w*.70f,h*.62f)},color,style=Stroke(line,cap=StrokeCap.Round,join=StrokeJoin.Round))
    }
}

/** Inset from the solid focus ring; never changes layout or takes focus. */
fun Modifier.archiveOutline(available:Boolean,color:Color):Modifier = if(!available)this else
    semantics{contentDescription=tr("watch_from_start_available")}.drawWithContent {
        drawContent()
        val inset=6.dp.toPx();val width=size.width-inset*2;val height=size.height-inset*2
        if(width>4.dp.toPx()&&height>4.dp.toPx())drawRoundRect(color,Offset(inset,inset),Size(width,height),CornerRadius(2.dp.toPx()),
            style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
    }
