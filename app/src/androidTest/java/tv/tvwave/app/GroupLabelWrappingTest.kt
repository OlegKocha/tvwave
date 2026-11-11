package tv.tvwave.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupLabelWrappingTest {
    @Test fun russianGroupHasNoOrphanLetterAtMenuWidths() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val label="Познавательные"
        val base=TextStyle(fontSize=20.sp,lineHeight=26.sp,letterSpacing=.5.sp,localeList=LocaleList("en"))
        for(scale in listOf(.6f,1f,1.3f)) {
            val density=Density(2f*scale)
            val measurer=TextMeasurer(createFontFamilyResolver(context),density,LayoutDirection.Ltr)
            // Current menu width is 240dp minus padding; selection also reserves a marker.
            for(width in listOf(140,148,160,164)) {
                val result=measurer.measure(AnnotatedString(label),groupLabelStyle(base,label,"en"),
                    constraints=Constraints(maxWidth=(width*density.density).toInt()))
                assertEquals("Unexpected truncation",label.length,result.getLineEnd(result.lineCount-1))
                for(line in 0 until result.lineCount) {
                    val part=label.substring(result.getLineStart(line),result.getLineEnd(line)).trim()
                    assertTrue("scale=$scale width=$width line=$part",part.length>=2)
                    android.util.Log.i("TVWaveWrap","scale=$scale width=$width line=$part")
                }
            }
            val phrase="История просмотра"
            val result=measurer.measure(AnnotatedString(phrase),groupLabelStyle(base,phrase,"ru"),
                constraints=Constraints(maxWidth=(164*density.density).toInt()))
            assertEquals(2,result.lineCount)
            assertEquals("История",phrase.substring(0,result.getLineEnd(0)).trim())
        }
    }
}
