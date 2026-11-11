package tv.tvwave.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgrammeRemindersTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    private fun waitFor(label:String,timeout:Long=8000,condition:()->Boolean){
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){inst.uiAutomation.clearCache();if(condition())return;SystemClock.sleep(80)}
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"reminder-failure.png"));fail(label)
    }
    private fun visible(tag:String,wanted:Boolean=true)=waitFor(tag){device.hasObject(By.res(tag))==wanted}
    private fun key(code:Int){device.pressKeyCode(code);SystemClock.sleep(140)}
    private fun hold(){
        val t=SystemClock.uptimeMillis()
        inst.uiAutomation.injectInputEvent(KeyEvent(t,t,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0),true)
        SystemClock.sleep(650)
        // Real remotes keep sending DOWN events while the confirmation window opens.
        repeat(5){i->
            inst.uiAutomation.injectInputEvent(KeyEvent(t,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,i+1),true)
            SystemClock.sleep(100)
        }
        inst.uiAutomation.injectInputEvent(KeyEvent(t,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0),true)
        SystemClock.sleep(180)
    }
    private fun storeEmpty()=ProgrammeReminders(ctx.getSharedPreferences("programme_reminders",0)).read().isEmpty()
    private fun call(s:ActivityScenario<MainActivity>,name:String)=s.onActivity{a->MainActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(a)}
    @Test fun searchUsesMainMenuAndRemindersKeepPlaying(){
        Configurator.getInstance().waitForIdleTimeout=0
        ctx.getSharedPreferences("programme_reminders",0).edit().clear().commit()
        TvFixture.seed(rich=true)
        ActivityScenario.launch(MainActivity::class.java).use{s->
            waitFor("Guide loaded"){device.hasObject(By.text("Тестовый канал 1"))}
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            fun player() : androidx.media3.exoplayer.ExoPlayer {
                var p:androidx.media3.exoplayer.ExoPlayer?=null
                s.onActivity{a->p=MainActivity::class.java.getDeclaredField("player").apply{isAccessible=true}.get(a) as androidx.media3.exoplayer.ExoPlayer}
                return p!!
            }
            val original=player()
            fun playing():Boolean{var result=false;s.onActivity{result=original.isPlaying};return result}
            waitFor("Fixture video is playing",15000){playing()}
            fun videoView():androidx.media3.ui.PlayerView?{
                fun find(v:android.view.View):androidx.media3.ui.PlayerView?{
                    if(v is androidx.media3.ui.PlayerView)return v
                    if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
                    return null
                }
                var view:androidx.media3.ui.PlayerView?=null
                s.onActivity{view=find(it.window.decorView)};return view
            }
            val originalView=videoView();assertNotNull(originalView)
            call(s,"showReminders");visible("reminders-page")
            assertSame(originalView,videoView());assertSame(original,player())
            var before=0L;s.onActivity{before=original.currentPosition}
            SystemClock.sleep(3500)
            assertTrue("Playback continues in reminders",playing())
            s.onActivity{assertTrue("Video clock advances",original.currentPosition>before+2000)}
            s.onActivity{a->
                val state=MainActivity::class.java.getDeclaredField("guideSearch").apply{isAccessible=true}.get(a) as GuideSearchState
                state.beginVisit(listOf("Тестовый"))
                MainActivity::class.java.getDeclaredMethod("openPanel",String::class.java).apply{isAccessible=true}.invoke(a,"search")
            }
            visible("global-search")
            waitFor("Search opens on input"){device.findObject(By.res("search-input"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor("Down from input reaches TV"){device.findObject(By.res("main-tv"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor("Down reaches favourites"){device.findObject(By.res("main-favourites"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Reopening search focuses input"){device.findObject(By.res("search-input"))?.isFocused==true}
            // Reopening clears the query, but the persisted history is independently restored.
            key(KeyEvent.KEYCODE_BACK)
            waitFor("Back from input reaches Search menu"){device.findObject(By.res("main-search"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER)
            visible("reminders-page")
            assertSame(originalView,videoView())
            assertTrue("Returning from search keeps playback",playing())
        }
    }
    @Test fun persistenceAndDueWindow(){
        val prefs=ctx.getSharedPreferences("reminder-store-test",0);prefs.edit().clear().commit()
        val store=ProgrammeReminders(prefs);val now=1000000L
        val r=ProgrammeReminder("a","Channel",now+300000,now+3600000,"Programme")
        assertFalse(r.due(now-1));assertTrue(r.due(now));assertFalse(r.due(r.start));assertFalse(r.copy(notified=true).due(now))
        store.save(listOf(r,r));assertEquals(listOf(r),store.read())
        store.save(listOf(r.copy(notified=true)));assertTrue(ProgrammeReminders(prefs).read().single().notified)
        store.save(emptyList());assertTrue(store.read().isEmpty())
    }
    @Test fun allOffsetsPersistAndDoNotRepeat(){
        val prefs=ctx.getSharedPreferences("reminder-offset-test",0);prefs.edit().clear().commit()
        val store=ProgrammeReminders(prefs);val start=10_000_000L
        var r=ProgrammeReminder("a","Channel",start,start+3600000,"Programme")
        val enabled=reminderMinuteOptions.toSet()
        for(minutes in enabled.sortedDescending()){
            val at=start-minutes*60000L
            assertFalse(r.due(at-1,enabled));assertTrue(r.due(at,enabled))
            r=r.delivered(at,enabled);store.save(listOf(r));r=store.read().single()
            assertFalse("Threshold must fire only once",r.due(at,enabled))
        }
        assertFalse(r.due(start,enabled))
        val fresh=ProgrammeReminder("b","Channel",start,start+3600000,"Programme")
        assertFalse(fresh.due(start-60000,emptySet()))
        val resumed=fresh.delivered(start-6*60000,enabled)
        assertFalse(resumed.due(start-6*60000,enabled))
        assertTrue(resumed.due(start-5*60000,enabled))
        val state=AppState(reminderMinutes=setOf(5,30,60))
        assertEquals(state.reminderMinutes,StateCodec.decode(StateCodec.encode(state)).reminderMinutes)
        assertEquals(emptySet<Int>(),StateCodec.decode(StateCodec.encode(state.copy(reminderMinutes=emptySet()))).reminderMinutes)
        val old=org.json.JSONObject(String(StateCodec.encode(state),Charsets.UTF_8));old.remove("reminderMinutes")
        assertEquals(setOf(5),StateCodec.decode(old.toString().toByteArray(Charsets.UTF_8)).reminderMinutes)
    }
    @Test fun reminderSettingsPersistMultipleSelections(){
        Configurator.getInstance().waitForIdleTimeout=0
        ctx.getSharedPreferences("programme_reminders",0).edit().clear().commit()
        TvFixture.seed(rich=true)
        ActivityScenario.launch(MainActivity::class.java).use{s->
            waitFor("Guide loaded"){device.hasObject(By.text("Тестовый канал 1"))}
            s.onActivity{a->MainActivity::class.java.getDeclaredMethod("openPanel",String::class.java).apply{isAccessible=true}.invoke(a,"appearance")}
            waitFor("Slider focused"){device.findObject(By.res("font-size"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor("Reminder setting focused"){device.findObject(By.res("appearance-reminders"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("5 minute option focused"){device.findObject(By.res("reminder-minute:5"))?.isFocused==true}
            repeat(reminderMinuteOptions.size-1){key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER)}
            waitFor("All settings saved"){Repository(ctx).use{it.load().reminderMinutes}==reminderMinuteOptions.toSet()}
            s.recreate()
            waitFor("Guide after recreate"){device.hasObject(By.res("guide-brand"))}
            assertEquals(reminderMinuteOptions.toSet(),Repository(ctx).use{it.load().reminderMinutes})
        }
    }
    @Test fun guideReturnsToTvAfterReminders(){
        Configurator.getInstance().waitForIdleTimeout=0
        ctx.getSharedPreferences("programme_reminders",0).edit().clear().commit()
        TvFixture.seed(rich=true)
        ActivityScenario.launch(MainActivity::class.java).use{s->
            waitFor("Guide loaded"){device.hasObject(By.text("Тестовый канал 1"))}
            call(s,"showMainMenu")
            waitFor("TV focused"){device.findObject(By.res("main-tv"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER)
            visible("reminders-page")
            key(KeyEvent.KEYCODE_BACK)
            waitFor("Guide restored"){device.hasObject(By.text("Тестовый канал 1"))}
            key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_LEFT)
            waitFor("Guide always returns to TV, not stale Reminders"){device.findObject(By.res("main-tv"))?.isFocused==true}
        }
    }
    @Test fun guideHoldListDeleteAndNotificationTimeout(){
        check(ctx.packageName=="tv.tvwave.app.debug")
        Configurator.getInstance().waitForIdleTimeout=0
        ctx.getSharedPreferences("programme_reminders",0).edit().clear().commit()
        TvFixture.seed(rich=true)
        val store=ProgrammeReminders(ctx.getSharedPreferences("programme_reminders",0))
        ActivityScenario.launch(MainActivity::class.java).use{s->
            waitFor("Guide loaded"){device.hasObject(By.text("Тестовый канал 1"))}
            key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_RIGHT)
            hold();visible("reminder-confirm")
            assertTrue("Releasing the opening hold must not save a reminder",storeEmpty())
            assertTrue("Hold must not open programme details",!device.hasObject(By.res("programme-details")))
            key(KeyEvent.KEYCODE_DPAD_CENTER);visible("reminder-confirm",false)
            waitFor("Saved reminder"){store.read().size==1}
            call(s,"showMainMenu")
            waitFor("Main TV focused"){device.findObject(By.res("main-tv"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER)
            visible("reminders-page")
            val row="reminder:${store.read().single().id}"
            waitFor("Entry focuses nearest reminder"){device.findObject(By.res(row))?.isFocused==true}
            visible("reminder-live-preview")
            key(KeyEvent.KEYCODE_BACK)
            waitFor("Back focuses sidebar"){device.findObject(By.res("main-reminders"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            SystemClock.sleep(600)
            waitFor("Re-entry focuses nearest reminder"){device.findObject(By.res(row))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("Right focuses description"){device.findObject(By.res("reminder-info-scroll"))?.isFocused==true}
            assertFalse(device.findObject(By.res("reminder-delete"))?.isFocused==true)
            key(KeyEvent.KEYCODE_BACK)
            waitFor("Back returns to reminder"){device.findObject(By.res(row))?.isFocused==true}
            hold();visible("reminder-cancel")
            waitFor("Hold focuses delete"){device.findObject(By.res("reminder-delete"))?.isFocused==true}
            assertEquals(1,store.read().size)
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER)
            visible("reminder-cancel",false)
            waitFor("Cancel returns to reminder"){device.findObject(By.res(row))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            repeat(70){
                inst.uiAutomation.clearCache()
                if(device.findObject(By.res("reminder-delete"))?.isFocused!=true){key(KeyEvent.KEYCODE_DPAD_DOWN);SystemClock.sleep(120)}
            }
            waitFor("Description bottom reaches delete"){device.findObject(By.res("reminder-delete"))?.isFocused==true}
            key(KeyEvent.KEYCODE_DPAD_CENTER);visible("reminders-empty")
            assertTrue(store.read().isEmpty())
            // Due reminder uses the real clock and the real 15 second dismissal effect.
            val now=System.currentTimeMillis();val c=Repository(ctx).use{it.load().channels.first()}
            val due=ProgrammeReminder(c.id,c.name,now+240000,now+3600000,"Upcoming programme")
            s.onActivity{a->MainActivity::class.java.getDeclaredMethod("saveReminders",List::class.java).apply{isAccessible=true}.invoke(a,listOf(due))}
            visible("reminder-notification")
            assertTrue(device.hasObject(By.text("Закрыть")))
            waitFor("Auto dismiss in 15 seconds",18000){!device.hasObject(By.res("reminder-notification"))}
            assertTrue(store.read().single().notified)
            val second=due.copy(start=System.currentTimeMillis()+290000,title="Second reminder",notified=false)
            s.onActivity{a->MainActivity::class.java.getDeclaredMethod("saveReminders",List::class.java).apply{isAccessible=true}.invoke(a,listOf(second))}
            visible("reminder-notification");visible("reminder-countdown")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            visible("reminder-countdown",false)
            SystemClock.sleep(16000)
            visible("reminder-notification")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            visible("reminder-notification",false)
        }
    }
    @Test fun searchHoldAndShortOkRemainDistinct(){
        Configurator.getInstance().waitForIdleTimeout=0
        ctx.getSharedPreferences("programme_reminders",0).edit().clear().commit()
        TvFixture.seed(rich=true)
        ActivityScenario.launch(MainActivity::class.java).use{s->
            waitFor("Guide loaded"){device.hasObject(By.text("Тестовый канал 1"))}
            s.onActivity{a->
                val state=MainActivity::class.java.getDeclaredField("guideSearch").apply{isAccessible=true}.get(a) as GuideSearchState
                state.beginVisit(emptyList());state.query="Передача 2";state.submit();state.returnToResults=true
                MainActivity::class.java.getDeclaredMethod("openPanel",String::class.java).apply{isAccessible=true}.invoke(a,"search")
            }
            visible("search-programmes-heading")
            hold();visible("reminder-confirm")
            assertTrue("Releasing the opening hold must not save a reminder",storeEmpty())
            key(KeyEvent.KEYCODE_BACK);visible("reminder-confirm",false)
            // Restore list focus before short OK.
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            visible("programme-details")
            hold();visible("reminder-confirm")
            assertTrue("Details hold also requires a fresh confirmation",storeEmpty())
            key(KeyEvent.KEYCODE_DPAD_CENTER);visible("reminder-confirm",false)
            assertFalse(storeEmpty())
        }
    }
}
