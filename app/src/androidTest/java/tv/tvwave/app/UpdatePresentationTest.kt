package tv.tvwave.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Tests only the update panel, with real release metadata staged on the emulator. */
@RunWith(AndroidJUnit4::class)
class UpdatePresentationTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    private fun node(id:String,focused:Boolean=false):androidx.test.uiautomator.UiObject2 {
        val end=SystemClock.uptimeMillis()+8000
        do {
            inst.uiAutomation.clearCache()
            device.findObject(if(focused)By.res(id).focused(true)else By.res(id))?.let{return it}
            SystemClock.sleep(80)
        }while(SystemClock.uptimeMillis()<end)
        error("Missing $id focused=$focused")
    }
    @Test fun reopeningClearsNegativeResultsButKeepsAvailableUpdates(){
        check(ctx.packageName=="tv.tvwave.app.debug"&&android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().waitForIdleTimeout=0
        val original=Repository(ctx).use{it.load()}
        try {
            Repository(ctx).use{it.save(AppState(uiLanguage="ru"),true)}
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                node("welcome-settings")
                fun open(){scenario.onActivity{a->MainActivity::class.java.getDeclaredMethod("openPanel",String::class.java).apply{isAccessible=true}.invoke(a,"updates")};node("update-action",true)}
                var flow:MutableStateFlow<AppUpdateState>?=null
                scenario.onActivity{a->
                    val vm=MainActivity::class.java.getDeclaredMethod("getAppUpdates").apply{isAccessible=true}.invoke(a)
                    @Suppress("UNCHECKED_CAST")
                    val value=AppUpdateViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<AppUpdateState>
                    flow=value
                }
                val release=AppRelease("99.0.0","Test release notes","https://example.invalid/tvwave.apk",100,"0".repeat(64))
                for(phase in listOf(UpdatePhase.CURRENT,UpdatePhase.AVAILABLE,UpdatePhase.ERROR,UpdatePhase.CHECKING,UpdatePhase.DOWNLOADING,UpdatePhase.READY)){
                    open()
                    val state=AppUpdateState(phase,if(phase in listOf(UpdatePhase.AVAILABLE,UpdatePhase.DOWNLOADING,UpdatePhase.READY))release else null)
                    scenario.onActivity{flow!!.value=state}
                    SystemClock.sleep(250)
                    device.pressBack()
                    val end=SystemClock.uptimeMillis()+5000
                    while(SystemClock.uptimeMillis()<end){
                        inst.uiAutomation.clearCache()
                        if(!device.hasObject(By.res("update-action")))break
                        SystemClock.sleep(80)
                    }
                    assertFalse("Back must leave the panel: $phase",device.hasObject(By.res("update-action")))
                    open()
                    val preserved=phase in listOf(UpdatePhase.AVAILABLE,UpdatePhase.DOWNLOADING,UpdatePhase.READY)
                    assertEquals(if(preserved)phase else UpdatePhase.IDLE,flow!!.value.phase)
                    if(phase==UpdatePhase.AVAILABLE){node("update-banner");node("update-recheck")}
                    if(!preserved){
                        assertFalse(device.hasObject(By.res("update-status")))
                        assertFalse(device.hasObject(By.res("update-banner")))
                        assertTrue(device.hasObject(By.text("Проверить обновление")))
                    }
                    scenario.onActivity{flow!!.value=AppUpdateState()}
                    device.pressBack();SystemClock.sleep(250)
                }
            }
        } finally {Repository(ctx).use{it.save(original,true)}}
    }
    @Test fun fixedActionAndScrollableGithubNotes(){
        check(ctx.packageName=="tv.tvwave.app.debug"&&android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().waitForIdleTimeout=0
        val dir=File(ctx.getExternalFilesDir(null),"update-layout").apply{mkdirs()}
        val release=AppRelease.newer(File(ctx.getExternalFilesDir(null),"update-release.json").readText(),"0.0.0")!!
        val original=Repository(ctx).use{it.load()}
        try {
            for((lang,font) in listOf("ru" to 100,"en" to 130,"ru" to 130)){
                Repository(ctx).use{it.save(AppState(uiLanguage=lang,fontPercent=font),true)}
                ActivityScenario.launch(MainActivity::class.java).use{scenario->
                    node("welcome-settings")
                    scenario.onActivity { a->MainActivity::class.java.getDeclaredMethod("openPanel",String::class.java).apply{isAccessible=true}.invoke(a,"updates") }
                    node("update-action",true)
                    assertFalse(device.hasObject(By.res("update-banner")))
                    device.takeScreenshot(File(dir,"idle-$lang-$font.png"))
                    fun state(value:AppUpdateState){scenario.onActivity{a->
                        val vm=MainActivity::class.java.getDeclaredMethod("getAppUpdates").apply{isAccessible=true}.invoke(a)
                        @Suppress("UNCHECKED_CAST")
                        val flow=AppUpdateViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<AppUpdateState>
                        flow.value=value
                    }}
                    state(AppUpdateState(UpdatePhase.AVAILABLE,release))
                    node("update-banner");node("update-action",true)
                    val before=node("update-action").visibleBounds
                    device.takeScreenshot(File(dir,"available-$lang-$font.png"))
                    device.pressDPadDown();node("update-recheck",true);device.pressDPadDown();node("update-notes",true)
                    repeat(12){device.pressDPadDown();SystemClock.sleep(80)}
                    assertEquals(before,node("update-action").visibleBounds)
                    node("update-notes",true)
                    device.takeScreenshot(File(dir,"scrolled-$lang-$font.png"))
                    repeat(16){device.pressDPadUp();SystemClock.sleep(80)}
                    node("update-action",true)
                    state(AppUpdateState(UpdatePhase.CURRENT))
                    node("update-status")
                    node("update-action",true)
                    assertFalse(device.hasObject(By.res("update-notes")))
                    assertTrue(device.hasObject(By.text(if(lang=="ru")"У вас установлена последняя версия."else"You have the latest version installed.")))
                    device.takeScreenshot(File(dir,"current-$lang-$font.png"))
                }
            }
        } finally {Repository(ctx).use{it.save(original,true)}}
    }
}
