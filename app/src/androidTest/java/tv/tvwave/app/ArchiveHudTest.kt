package tv.tvwave.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only the opt-in local recording on the emulator. UI actions use real D-pad events. */
@RunWith(AndroidJUnit4::class)
class ArchiveHudTest {
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var scenario:ActivityScenario<MainActivity>
    private fun key(code:Int){device.pressKeyCode(code);SystemClock.sleep(180)}
    private fun visible(id:String)=device.hasObject(By.res(id))
    private fun waitFor(label:String,condition:()->Boolean){
        val until=SystemClock.elapsedRealtime()+10000
        while(SystemClock.elapsedRealtime()<until){
            if(android.os.Build.VERSION.SDK_INT>=33)InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            if(condition())return
            SystemClock.sleep(150)
        }
        fail(label)
    }
    private fun <T> field(name:String):T {var result:T?=null;scenario.onActivity{a->@Suppress("UNCHECKED_CAST") result=MainActivity::class.java.getDeclaredMethod("get"+name.replaceFirstChar{it.uppercase()}).apply{isAccessible=true}.invoke(a) as T};@Suppress("UNCHECKED_CAST") return result as T}
    private fun position():Long {var result=0L;scenario.onActivity{a->val p=MainActivity::class.java.getDeclaredField("player").apply{isAccessible=true}.get(a) as ExoPlayer;result=p.currentPosition};return result}
    private fun ready(){waitFor("Recording ready"){var ready=false;scenario.onActivity{a->val p=MainActivity::class.java.getDeclaredField("player").apply{isAccessible=true}.get(a) as ExoPlayer;ready=p.playbackState==Player.STATE_READY};ready}}
    private fun seed(font:Int=100){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        check(File(ctx.filesDir,"archive-preview.mp4").exists()){"Stage the synthetic 60-minute file first"}
        TvFixture.seed(font=font,rich=true,media="http://10.0.2.2:8765/video.mp4?archive-demo=1")
    }
    @Test fun seedPreview(){seed(InstrumentationRegistry.getArguments().getString("font","100").toInt())}
    @Test fun programmeLaunchSeekCancelCommitAndEndChoice(){
        Configurator.getInstance().waitForIdleTimeout=0
        seed();scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            waitFor("Guide loaded"){visible("guide-channel:0")||device.hasObject(By.text("Тестовый канал 1"))}
            key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("First OK opens description"){visible("programme-details")}
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Current programme asks before restart"){visible("archive-start")&&visible("archive-watch-live")}
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Past programme launches archive"){field<ArchiveSession?>("archiveSession")!=null}
            ready();key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            waitFor("Central pause action focused"){device.hasObject(By.res("player-pause").focused(true))}
            key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Forward one minute changes actual player position"){position()>=60000}
            key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Rewind one minute"){position()<5000}
            key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(6){key(KeyEvent.KEYCODE_DPAD_RIGHT)}
            assertTrue(field<Long?>("seekDraft")!!>=1800000)
            assertTrue("Preview must not seek the player",position()<5000)
            SystemClock.sleep(3300)
            assertTrue("HUD stays visible during seek editing",visible("player-timeline"))
            key(KeyEvent.KEYCODE_BACK)
            assertNull(field<Long?>("seekDraft"));assertTrue(position()<5000)
            key(KeyEvent.KEYCODE_DPAD_CENTER);repeat(6){key(KeyEvent.KEYCODE_DPAD_RIGHT)};key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("OK commits 30-minute seek"){position()>=1800000}
            key(KeyEvent.KEYCODE_DPAD_CENTER);repeat(7){key(KeyEvent.KEYCODE_DPAD_RIGHT)};key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            waitFor("End choice"){visible("archive-next")&&visible("archive-end-live")}
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Next programme starts"){!field<Boolean>("archiveEnded")&&position()<5000};ready()
            waitFor("Next programme HUD focused"){device.hasObject(By.res("player-pause").focused(true))}
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            key(KeyEvent.KEYCODE_DPAD_DOWN);repeat(4){key(KeyEvent.KEYCODE_DPAD_RIGHT)}
            waitFor("Return to live action focused"){device.hasObject(By.res("player-live").focused(true))}
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Return to live clears archive session"){field<ArchiveSession?>("archiveSession")==null};ready()
        }catch(e:Throwable){device.takeScreenshot(File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"archive-test-failure.png"));throw e}finally{scenario.close()}
    }
}
