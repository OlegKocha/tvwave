package tv.tvwave.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreEntryTest {
    @Test fun emptyAppRestoresDirectlyFromFilesAndBackReturnsToWelcome(){
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        check(ctx.packageName=="tv.tvwave.app.debug"&&android.os.Build.MODEL.contains("sdk"))
        val device=UiDevice.getInstance(inst)
        Configurator.getInstance().waitForIdleTimeout=0
        val original=Repository(ctx).use{it.load()}
        try {
            Repository(ctx).use{it.save(AppState(uiLanguage="ru"),true)}
            ActivityScenario.launch(MainActivity::class.java).use {
                fun node(tag:String,focused:Boolean=false):androidx.test.uiautomator.UiObject2 {
                    val until=android.os.SystemClock.uptimeMillis()+6000
                    do {
                        inst.uiAutomation.clearCache()
                        device.findObject(if(focused)By.res(tag).focused(true)else By.res(tag))?.let{return it}
                        android.os.SystemClock.sleep(80)
                    }while(android.os.SystemClock.uptimeMillis()<until)
                    device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"restore-failure.png"))
                    error("Missing $tag focused=$focused")
                }
                node("add",true)
                device.pressDPadRight();node("restore",true);device.pressDPadCenter()
                node("file-list")
                assertFalse(device.hasObject(By.res("export")))
                android.os.SystemClock.sleep(400)
                device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"restore-direct.png"))
                if(node("file-path").text!="/"){
                    device.pressBack()
                    android.os.SystemClock.sleep(400)
                    assertEquals("/",node("file-path").text)
                }
                device.pressBack()
                node("welcome-settings")
                assertFalse(device.hasObject(By.res("export")))
            }
        } finally {Repository(ctx).use{it.save(original,true)}}
    }
}
