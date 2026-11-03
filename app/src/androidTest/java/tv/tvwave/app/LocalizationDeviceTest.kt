package tv.tvwave.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Synthetic content and debug package only. Never touches a physical TV. */
@RunWith(AndroidJUnit4::class)
class LocalizationDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val ctx=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private val screenshots get()=File(ctx.getExternalFilesDir(null),"localization").apply{mkdirs()}
    private fun guard(){
        check(ctx.packageName=="tv.tvwave.app.debug")
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0)
    }
    private fun node(id:String,focused:Boolean=false):androidx.test.uiautomator.UiObject2 {
        val until=SystemClock.uptimeMillis()+7000
        do {
            if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
            device.findObject(if(focused)By.res(id).focused(true)else By.res(id))?.let{return it}
            SystemClock.sleep(80)
        }while(SystemClock.uptimeMillis()<until)
        shot("failure")
        device.dumpWindowHierarchy(File(screenshots,"failure.xml"))
        error("Missing ${if(focused)"focused "else""}$id")
    }
    private fun click(id:String){node(id).click();SystemClock.sleep(450)}
    private fun shot(name:String){SystemClock.sleep(400);device.takeScreenshot(File(screenshots,"$name.png"))}
    private fun launch()=ActivityScenario.launch<MainActivity>(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    @Test fun languageBeforeOnboardingAndChangeFromAppearance(){
        guard();Repository(ctx).use{it.save(AppState(),true)}
        instrumentation.runOnMainSync{L10n.select("en")}
        launch().use {
            node("language:en",true)
            assertNull(device.findObject(By.res("add")))
            shot("language-en")
            device.pressDPadDown();node("language:ru",true);device.pressDPadCenter()
            node("add");assertTrue(device.hasObject(By.text("Добавить плейлист")))
            shot("onboarding-ru")
            click("welcome-settings");click("appearance")
            node("font-size",true)
            device.pressDPadDown();node("font-reset",true)
            device.pressDPadDown();node("appearance-language",true);device.pressDPadCenter()
            node("language:ru",true)
            device.pressDPadUp();node("language:en",true);device.pressDPadCenter()
            node("font-size");assertTrue(device.hasObject(By.text("Font size")))
            assertEquals("en",Repository(ctx).use{it.load().uiLanguage})
            shot("appearance-en")
        }
        launch().use {
            node("add");assertTrue(device.hasObject(By.text("Add playlist")))
            assertNull(device.findObject(By.res("language:en")))
            shot("onboarding-en")
        }
    }
    @Test fun phoneFormUsesSelectedLanguageAndOneTimeSubmission(){
        guard()
        for(locale in listOf("ru","en")){
            instrumentation.runOnMainSync{L10n.select(locale)}
            val received=java.util.concurrent.atomic.AtomicReference<String>()
            PhoneLink(java.net.InetAddress.getByName("127.0.0.1")){received.set(it)}.use{session->
                session.start()
                val page=java.net.URL(session.url).readText()
                assertTrue(page.contains("lang=\"$locale\""))
                assertTrue(page.contains(if(locale=="en")"Send to TV"else"Передать на телевизор"))
                assertTrue(page.contains("TV Wave"))
                val connection=java.net.URL(session.url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod="POST";connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                val address="https://example.test/demo.m3u8"
                connection.outputStream.use{it.write(("url="+java.net.URLEncoder.encode(address,"UTF-8")).toByteArray())}
                val result=connection.inputStream.bufferedReader().use{it.readText()}
                assertTrue(result.contains(if(locale=="en")"URL sent."else"Ссылка передана."))
                val until=SystemClock.uptimeMillis()+1000
                while(received.get()==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(20)
                assertEquals(address,received.get())
                assertTrue(runCatching{java.net.URL(session.url).readText()}.isFailure)
            }
        }
    }
    private fun seed(locale:String){
        val id=UUID.nameUUIDFromBytes("tvwave-documentation".toByteArray()).toString()
        val names=listOf("Wave Nature","Wave Cinema","Wave Discovery","Wave Travel","Wave Sport","Wave Music","Wave Family","Wave News")
        val raw="#EXTM3U\n"+names.mapIndexed{n,name->"#EXTINF:-1 tvg-id=\"demo$n\" tvg-rec=\"4\" group-title=\"Demo\",$name\nhttp://10.0.2.2:8765/video.mp4?archive-demo=1"}.joinToString("\n")+"\n"
        val playlist=Playlist(id,"Demo playlist","",raw,M3u.parse(raw,id))
        val source=legacyEpgSource("http://10.0.2.2:8765/epg/1.xml").copy(name="Demo guide")
        val state=AppState(playlists=listOf(playlist),favourites=setOf(playlist.channels.first().id),last=playlist.channels.first().id,
            history=playlist.channels.take(3).map{it.id},epgSources=listOf(source),uiLanguage=locale,epgAutoUpdate=false,fontPercent=100)
        val now=System.currentTimeMillis();val start=now/3600000*3600000;val fmt=SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US)
        val titles=listOf("Hidden landscapes","A journey through the mountains","The world at sunset","Stories by the sea","Into the forest","A new horizon")
        val xml="<tv>"+names.mapIndexed{n,name->"<channel id=\"demo$n\"><display-name>$name</display-name></channel>"}.joinToString("")+names.mapIndexed{n,_->
            (-4..8).joinToString(""){slot->val t=start+slot*3600000L;val title=titles[Math.floorMod(slot+n,titles.size)]
                "<programme channel=\"demo$n\" start=\"${fmt.format(Date(t))}\" stop=\"${fmt.format(Date(t+3600000))}\"><title>$title</title><desc>Discover quiet landscapes, remarkable places and the people who call them home. Demonstration programme guide for TV Wave. No TV subscription or personal playlist is included.</desc></programme>"
            }
        }.joinToString("")+"</tv>"
        Repository(ctx).use{repo->repo.save(state,true);val staged=File(ctx.cacheDir,"documentation-epg.db");EpgCache.stage(staged,xml.byteInputStream(),playlist.channels,source,now,{});EpgCache.install(repo.writableDatabase,staged,source,state);android.database.sqlite.SQLiteDatabase.deleteDatabase(staged)}
    }
    @Test fun documentationScreenshots(){
        guard()
        for(locale in listOf("ru","en")){
            seed(locale)
            launch().use {
                node("guide-programme-title");SystemClock.sleep(800)
                // First OK starts preview; second OK expands the current channel.
                device.pressDPadCenter();SystemClock.sleep(1000)
                shot("guide-$locale")
                device.pressDPadCenter();node("player-pause")
                shot("player-$locale")
                device.pressBack();device.pressBack();SystemClock.sleep(350)
                device.pressDPadLeft();SystemClock.sleep(300);device.pressDPadLeft();SystemClock.sleep(300)
                click("main-settings");shot("settings-$locale")
                click("epg");node("epg-add");shot("epg-$locale")
                device.pressBack();click("appearance");node("font-size");shot("appearance-$locale")
                click("appearance-language");node("language:$locale");shot("language-settings-$locale")
            }
        }
    }
}
