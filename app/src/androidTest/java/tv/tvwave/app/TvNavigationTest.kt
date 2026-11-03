package tv.tvwave.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Synthetic state only. Never seed or clear a release installation or physical TV. */
object TvFixture {
    fun seed(count:Int=1,large:Boolean=false,font:Int=if(large)120 else 100,rich:Boolean=false,media:String="http://10.0.2.2:8765/video.mp4",todayOnly:Boolean=false) {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName=="tv.tvwave.app.debug")
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Emulator only" }
        val id=UUID.nameUUIDFromBytes("e2e-playlist".toByteArray()).toString()
        require(media.startsWith("http://10.0.2.2:"))
        val raw="#EXTM3U\n"+(1..16).joinToString("\n"){n->"#EXTINF:-1 tvg-id=\"test$n\" group-title=\"Тестовые\",Тестовый канал $n\n${media}"}+"\n"
        val p=Playlist(id,"Тестовый плейлист","",raw,M3u.parse(raw,id))
        // Management refresh replaces the rich schedule with today's programmes.
        // Pin the HTTP fixture to the device's calendar day, not the host clock.
        val dayStart=java.util.Calendar.getInstance().apply{set(java.util.Calendar.HOUR_OF_DAY,0);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis
        val query=if(todayOnly)"?dayStart=$dayStart"else ""
        val sources=(1..count).map{n->legacyEpgSource("http://10.0.2.2:8765/epg/$n.xml$query").copy(name="EPG ${n.toString().padStart(2,'0')}")}
        val state=AppState(uiLanguage="ru",playlists=listOf(p),large=large,fontPercent=font,history=p.channels.take(6).map{it.id},epgSources=sources)
        val now=System.currentTimeMillis();val fmt=SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US)
        val xml="<tv>"+(1..16).joinToString(""){"<channel id=\"test$it\"><display-name>Тестовый канал $it</display-name></channel>"}+(1..16).joinToString(""){channel->
            if(!rich)"<programme channel=\"test$channel\" start=\"${fmt.format(Date(now-3600000))}\" stop=\"${fmt.format(Date(now+3600000))}\"><title>Тестовая передача $channel</title></programme>"
            else ((if(media.endsWith("?archive-demo=1"))-125 else -24)..71).joinToString(""){slot->val start=now/3600000*3600000+slot*3600000L
                val description="Описание передачи $slot. "+(1..20).joinToString(" "){"Раздел $it: подробная история о путешествиях, людях и событиях. Описание можно прочитать целиком, не прерывая прямой эфир."}+" Конец описания $slot."
                "<programme channel=\"test$channel\" start=\"${fmt.format(Date(start))}\" stop=\"${fmt.format(Date(start+3600000))}\"><title>Передача $slot · Канал $channel</title><desc>$description</desc></programme>"
            }
        }+"</tv>"
        Repository(ctx).use{repo->
            repo.save(state,resetCache=true)
            val staged=File(ctx.cacheDir,"e2e-stage.db")
            for(source in sources){EpgCache.stage(staged,xml.byteInputStream(),p.channels,source,now,{});EpgCache.install(repo.writableDatabase,staged,source,state)}
            android.database.sqlite.SQLiteDatabase.deleteDatabase(staged)
        }
    }
}
@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private val ctx=instrumentation.targetContext
    private fun freshNode(selector:androidx.test.uiautomator.BySelector,timeout:Long=10000):androidx.test.uiautomator.UiObject2?{
        val until=SystemClock.uptimeMillis()+timeout
        do{
            if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
            device.findObject(selector)?.let{return it}
            SystemClock.sleep(50)
        }while(SystemClock.uptimeMillis()<until)
        device.takeScreenshot(File(ctx.getExternalFilesDir(null),"wait-failure.png"))
        device.dumpWindowHierarchy(File(ctx.getExternalFilesDir(null),"wait-failure.xml"))
        return null
    }
    private fun focus(id:String) {
        val until=SystemClock.uptimeMillis()+3000
        var ok:androidx.test.uiautomator.UiObject2?=null
        do {
            // Android accessibility caches can retain an outgoing animated panel.
            if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
            ok=device.findObject(By.res(id).focused(true))
            if(ok!=null)break
            SystemClock.sleep(50)
        }while(SystemClock.uptimeMillis()<until)
        if(ok==null){
            device.takeScreenshot(File(ctx.getExternalFilesDir(null),"focus-failure.png"))
            device.dumpWindowHierarchy(File(ctx.getExternalFilesDir(null),"focus-failure.xml"))
        }
        assertNotNull("Expected focused action $id; actual="+device.findObjects(By.focused(true)).joinToString{it.resourceName.orEmpty()+":"+it.text.orEmpty()},ok)
        val b=ok!!.visibleBounds
        assertTrue("Focused action must be visible: $id",b.width()>0&&b.height()>0&&b.left>=0&&b.top>=0&&b.right<=device.displayWidth&&b.bottom<=device.displayHeight)
    }
    private fun down(id:String){device.pressDPadDown();focus(id)}
    private fun up(id:String){device.pressDPadUp();focus(id)}
    private fun launch(){
        Configurator.getInstance().waitForIdleTimeout=100
        ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("guide-brand")),10000))
        // The first key changes input mode from touch to D-pad on a cold emulator.
        device.pressDPadDown();device.pressBack();focus("group:Все каналы")
        device.pressBack();focus("main-tv")
        down("main-favourites");down("main-settings");device.pressDPadCenter();focus("playlists")
        down("epg");device.pressDPadCenter()
    }
    @Test fun seedForMaestro(){
        val args=InstrumentationRegistry.getArguments()
        TvFixture.seed(args.getString("sources","1").toInt(),args.getString("large","false").toBoolean(),args.getString("font",if(args.getString("large","false")=="true")"120"else"100").toInt(),args.getString("rich","false").toBoolean(),args.getString("media","http://10.0.2.2:8765/video.mp4"))
    }
    @Test fun stageImportForMaestro(){
        check(ctx.packageName=="tv.tvwave.app.debug")
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeNotNull(args.getString("name"),args.getString("content"))
        val name=args.getString("name").orEmpty()
        require(name.matches(Regex("qa-[a-z-]+\\.(m3u8|tvwavebackup)")))
        val bytes=android.util.Base64.decode(args.getString("content"),android.util.Base64.DEFAULT)
        require(bytes.size in 1..32768)
        val folder=File(requireNotNull(ctx.getExternalFilesDir(null)),"Import").apply{mkdirs()}
        val file=File(folder,name);file.writeBytes(bytes)
        assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun epgFooterRoundTrip(){
        val args=InstrumentationRegistry.getArguments();val count=args.getString("sources","10").toInt()
        val large=args.getString("large","false").toBoolean()
        TvFixture.seed(count,large,args.getString("font",if(large)"120"else"100").toInt());launch()
        val keys=(1..count).map{n->"epg-source:"+legacyEpgSource("http://10.0.2.2:8765/epg/$n.xml").id}+(if(count<10)listOf("epg-add")else emptyList())+listOf("epg-matches","epg-update","epg-interval")
        focus(keys.first())
        repeat(args.getString("cycles","30").toInt()){
            keys.drop(1).forEach{down(it)}
            keys.dropLast(1).asReversed().forEach{up(it)}
        }
    }
    @Test fun appearanceSliderPersistsAndKeepsFocus(){
        TvFixture.seed();launch();device.pressBack();focus("epg")
        down("appearance");device.pressDPadCenter();focus("font-size")
        fun value(n:Int,focusedId:String="font-size"){
            val until=SystemClock.uptimeMillis()+3000;var found=false
            do{
                if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                found=device.hasObject(By.res("font-value").text("$n%"))
                if(found)break
                SystemClock.sleep(50)
            }while(SystemClock.uptimeMillis()<until)
            if(!found){device.takeScreenshot(File(ctx.getExternalFilesDir(null),"font-failure.png"));device.dumpWindowHierarchy(File(ctx.getExternalFilesDir(null),"font-failure.xml"))}
            assertTrue("Expected $n%",found);focus(focusedId)
        }
        value(100)
        repeat(8){device.pressDPadLeft();value(95-it*5)}
        device.pressDPadLeft();value(60)
        device.takeScreenshot(File(ctx.getExternalFilesDir(null),"font-60.png"))
        repeat(14){device.pressDPadRight();value(65+it*5)}
        device.pressDPadRight();value(130)
        device.takeScreenshot(File(ctx.getExternalFilesDir(null),"font-130.png"))
        val deadline=SystemClock.uptimeMillis()+5000
        while(Repository(ctx).use{it.load().fontPercent}!=130&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
        Repository(ctx).use{repo->val state=repo.load();assertEquals(130,state.fontPercent);assertEquals(130,BackupFile.decode(BackupFile.encode(state)).fontPercent)}
        down("font-reset");device.pressDPadCenter()
        value(100,"font-reset");up("font-size")
        device.takeScreenshot(File(ctx.getExternalFilesDir(null),"font-100.png"))
    }
    @Test fun hudOccupiesOneThird(){
        val size=InstrumentationRegistry.getArguments().getString("font","100").toInt()
        TvFixture.seed(font=size)
        Configurator.getInstance().waitForIdleTimeout=100
        ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(freshNode(By.res("guide-brand")))
        focus("guide")
        device.pressDPadDown();device.pressDPadCenter()
        assertNotNull("Preview must start",freshNode(By.textContains("Смотрим ·"),15000))
        device.pressDPadCenter();focus("player-pause");device.pressDPadCenter()
        val hud=device.wait(Until.findObject(By.res("player-hud")),3000)!!.visibleBounds
        assertTrue("HUD must be one third: $hud",kotlin.math.abs(device.displayHeight/3-hud.height())<=2)
        assertEquals(device.displayHeight,hud.bottom)
        for(id in listOf("player-favourite","player-subtitles","player-pause")){
            val b=device.findObject(By.res(id))!!.visibleBounds
            assertTrue("$id must fit in the HUD",hud.contains(b))
        }
        device.takeScreenshot(File(ctx.getExternalFilesDir(null),"hud-$size.png"))
    }

}
