package tv.tvwave.app

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real HUD deadline, without Maestro's per-command idle wait or a timer override. */
@RunWith(AndroidJUnit4::class)
class HudNavigationTest {
    @Test fun playbackFocusAndThreeSecondHide() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val ctx=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        val font=InstrumentationRegistry.getArguments().getString("font","100").toInt()
        TvFixture.seed(1,font=font,rich=true)
        Configurator.getInstance().waitForIdleTimeout=0
        fun key(code:Int){
            val now=SystemClock.uptimeMillis()
            instrumentation.uiAutomation.injectInputEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0),true)
            instrumentation.uiAutomation.injectInputEvent(KeyEvent(now,now+1,KeyEvent.ACTION_UP,code,0),true)
            SystemClock.sleep(80)
        }
        fun visible(id:String,wanted:Boolean=true,timeout:Long=3000):Boolean {
            val deadline=SystemClock.uptimeMillis()+timeout
            do {
                if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                if(device.hasObject(By.res(id))==wanted)return true
                SystemClock.sleep(30)
            }while(SystemClock.uptimeMillis()<deadline)
            return false
        }
        fun focus(id:String){
            val deadline=SystemClock.uptimeMillis()+1500
            do {
                if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                if(device.hasObject(By.res(id).focused(true)))return
                SystemClock.sleep(30)
            }while(SystemClock.uptimeMillis()<deadline)
            fail("Expected HUD focus: $id at font $font")
        }
        ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("guide")),10000))
        key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_UP);focus("guide")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(device.wait(Until.hasObject(By.text("Смотрим · Тестовый канал 1")),15000))
        key(KeyEvent.KEYCODE_DPAD_CENTER);focus("player-pause")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);focus("player-subtitles")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);focus("player-favourite")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);focus("player-buffer")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);focus("player-live")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);focus("player-live")
        key(KeyEvent.KEYCODE_DPAD_LEFT);focus("player-buffer")
        key(KeyEvent.KEYCODE_DPAD_LEFT);focus("player-favourite")
        key(KeyEvent.KEYCODE_DPAD_LEFT);focus("player-subtitles")
        assertFalse(device.hasObject(By.res("channel-browser")))
        key(KeyEvent.KEYCODE_DPAD_LEFT);focus("player-pause")
        assertFalse(device.hasObject(By.res("channel-browser")))
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left edge opens browser",visible("channel-browser"))
        key(KeyEvent.KEYCODE_BACK);focus("player-pause")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);focus("player-subtitles")
        SystemClock.sleep(1000)
        if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
        assertTrue("HUD still visible after 1 second",visible("player-subtitles",timeout=100))
        assertTrue("HUD hides after inactivity",visible("player-subtitles",wanted=false,timeout=3500))
        // Reopening after inactivity restores the last button, unlike returning
        // from the channel browser, which starts at the favourite action.
        key(KeyEvent.KEYCODE_DPAD_CENTER);focus("player-subtitles")
    }
}
