package tv.tvwave.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ArchiveLoadingTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    private fun visible(tag:String,expected:Boolean=true) {
        val end=SystemClock.uptimeMillis()+6000
        do {
            inst.uiAutomation.clearCache()
            if(device.hasObject(By.res(tag))==expected)return
            SystemClock.sleep(80)
        }while(SystemClock.uptimeMillis()<end)
        fail("$tag visible must be $expected")
    }
    @Test fun slowRequestStaysVisibleAndOldCancelledRequestCannotHideNewRequest() {
        check(ctx.packageName=="tv.tvwave.app.debug"&&android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().waitForIdleTimeout=0
        val original=Repository(ctx).use{it.load()}
        val gates=List(3){CountDownLatch(1)}
        val entered=List(3){CountDownLatch(1)}
        val count=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val i=count.getAndIncrement()
            entered[i].countDown()
            check(gates[i].await(25,TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404)
                .message("Fixture").body("".toResponseBody()).build()
        }.build()
        try {
            Repository(ctx).use{it.save(AppState(uiLanguage="ru"),true)}
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                visible("welcome-settings")
                scenario.onActivity{MainActivity::class.java.getDeclaredField("http").apply{isAccessible=true}.set(it,client)}
                fun start()=scenario.onActivity { a->
                    val now=System.currentTimeMillis()
                    MainActivity::class.java.getDeclaredMethod("startArchive",Channel::class.java,Programme::class.java,
                        java.lang.Long.TYPE,String::class.java,java.lang.Boolean.TYPE,java.lang.Boolean.TYPE).apply{isAccessible=true}
                        .invoke(a,Channel("fixture","Archive fixture","Test","https://fixture.lunexas.top/iptv/test/1/index.m3u8",archiveDays=7),
                            Programme("fixture",now-7200000,now-3600000,"Fixture"),0L,"player-pause",true,true)
                }
                start();assertTrue(entered[0].await(5,TimeUnit.SECONDS));visible("archive-loading-progress")
                SystemClock.sleep(5200)
                visible("archive-loading-progress") // The old toast disappeared after 4.5 seconds.
                device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"archive-loading.png"))
                gates[0].countDown();visible("archive-loading",false)
                device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"archive-error.png"))
                inst.uiAutomation.clearCache()
                val until=SystemClock.uptimeMillis()+3000
                while(SystemClock.uptimeMillis()<until){
                    inst.uiAutomation.clearCache()
                    if(device.hasObject(By.textContains("не прислал")))break
                    SystemClock.sleep(80)
                }
                assertTrue(device.hasObject(By.textContains("не прислал")))
                start();assertTrue(entered[1].await(5,TimeUnit.SECONDS));visible("archive-loading")
                start();assertTrue(entered[2].await(5,TimeUnit.SECONDS));gates[1].countDown()
                SystemClock.sleep(400);visible("archive-loading")
                device.pressBack();visible("archive-loading",false)
                gates[2].countDown();SystemClock.sleep(400);visible("archive-loading",false)
                visible("welcome-settings")
            }
        } finally {
            gates.forEach{it.countDown()}
            Repository(ctx).use{it.save(original,true)}
        }
    }
}
