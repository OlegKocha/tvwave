package tv.tvwave.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Keep Material's drag/accessibility behaviour, with the same pill and thumb in every state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceScaleSlider(value:Int,onChange:(Int)->Unit,onFinished:()->Unit,modifier:Modifier=Modifier,min:Int=60,max:Int=130,step:Int=5){
    Slider(value=value.toFloat(),onValueChange={onChange((min+((it-min)/step).roundToInt()*step).coerceIn(min,max))},
        valueRange=min.toFloat()..max.toFloat(),steps=(max-min)/step-1,onValueChangeFinished=onFinished,modifier=modifier,
        thumb={Box(Modifier.size(28.dp).background(T.accent,CircleShape))},
        track={state->
            Canvas(Modifier.fillMaxWidth().height(28.dp)){
                val fraction=((state.value-min)/(max-min)).coerceIn(0f,1f)
                val radius=CornerRadius(size.height/2)
                drawRoundRect(T.inputBorder.copy(alpha=.55f),cornerRadius=radius)
                if(fraction>0f)drawRoundRect(T.switchBlue,size=Size(size.width*fraction,size.height),cornerRadius=radius)
            }
        })
}
