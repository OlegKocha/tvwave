package tv.tvwave.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp

/** Preserve the preview's shape when the entire interface, rather than just text, is resized. */
fun guidePreviewWidth(contentWidth:Dp):Dp=minOf(T.previewHeight*(16f/9f),contentWidth*.48f)

/** Native 960×540dp mapping of the approved 1280×720 design canvas (factor 3/4). */
object T {
    const val phoneStyle="""body{font:18px system-ui;background:#151718;color:#f3f2ee;max-width:34rem;margin:3rem auto;padding:1.2rem}input,button{box-sizing:border-box;width:100%;font:inherit;padding:1rem;margin:.6rem 0;border-radius:.5rem}button{background:#e6e2d7;border:0;color:#202322}p{line-height:1.6}"""
    const val phoneDrawerFraction=.74f
    const val qrPixels=384; val qrSize=180.dp; val recoveryWidth=560.dp
    val searchSidebarWidth=176.dp; val searchTitle=22.sp
    const val searchResultsWeight=.57f; const val searchDetailsWeight=.43f
    val leftMainWidth=252.dp; val leftGroupWidth=240.dp
    val brandMark=32.dp
    val leftRailWidth=64.dp; val menuIcon=22.dp; val railAction=48.dp; val railFooter=48.dp
    val channelLogo=36.dp; val logoInset=3.dp; val channelContentGap=3.dp
    const val menuMotionMs=220; const val videoMotionMs=300
    val switchBlue=Color(0xFF1968D7); val switchWidth=52.dp; val switchHeight=28.dp
    val bg=Color(0xFF151718); val surface=Color(0xFF222526); val raised=Color(0xFF303435)
    val border=Color(0xFF484D4E); val inputBorder=Color(0xFF8D9696)
    val text=Color(0xFFF3F2EE); val muted=Color(0xFFBCC3C2); val accent=Color(0xFFE6E2D7)
    val onAccent=Color(0xFF202322); val danger=Color(0xFFFFB4AA); val success=Color(0xFFBFD8BD)
    val railSelected=accent.copy(alpha=.14f)
    const val hudFraction=1f/3f
    val browserSurface=bg.copy(alpha=.88f)
    val detailsBottom=164.dp; val programmeCardWidth=220.dp; val programmeCardHeight=82.dp; val descriptionScroll=100.dp
    val hudTitle=24.sp; val hudPadding=12.dp; val sliderHeight=48.dp
    val hud=bg.copy(alpha=.5f); val transparent=Color.Transparent; val hudIcon=14.dp; val hudButtonWidth=112.dp
    val hudInset=6.dp
    val liveDot=Color(0xFFFF565B); val hudTrack=Color.White.copy(alpha=.25f)
    val transportWidth=70.dp; val transportHeight=46.dp; val transportIcon=24.dp
    val loadingSize=40.dp; val loadingStroke=3.dp
    val transportCircle=40.dp; val hudUtilityCircle=30.dp
    val hudUtilityWidth=84.dp; val hudTimelineHeight=30.dp; val hudTrackHeight=3.dp
    val hudKnob=8.dp; val hudDot=6.dp; val hudCompactTitle=20.sp; val hudLabel=12.sp
    const val hudDisabledAlpha=.32f
    val veil=Color.Black.copy(alpha=.60f)
    val gap=12.dp; val small=8.dp; val tiny=4.dp; val pad=24.dp; val safe=48.dp
    val top=21.dp; val previewTop=54.dp; val previewHeight=165.dp; val tableTop=230.dp
    val footerSpace=48.dp; val headHeight=30.dp; val channelWidth=324.dp; val largeChannelWidth=348.dp; val channelNumber=34.dp; val mono=24.dp
    val row=36.dp; val largeRow=44.dp; val rowGap=2.dp; val channelGap=7.dp
    val drawerFraction=.4f; val groupFraction=.305f; val drawerTop=32.dp; val drawerBottom=23.dp
    val groupRow=42.dp; val largeGroupRow=50.dp; val settingRow=54.dp; val largeSettingRow=65.dp; val actionHeight=44.dp
    val title=28.sp; val display=30.sp; val body=20.sp; val caption=16.sp
    val channelName=16.sp; val channelLine=17.sp; val largeChannelLine=20.sp
    val channel=17.sp; val description=18.sp; val micro=14.sp; val large=24.sp; val largeCaption=18.sp
    val radius=4.dp; val buttonRadius=6.dp; val previewRadius=8.dp
    val line=1.dp; val focusLine=2.dp; val focusGap=2.dp; val actionPad=16.dp
}
