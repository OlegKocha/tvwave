package tv.tvwave.app

import android.view.KeyEvent
import android.view.ViewConfiguration
import kotlinx.coroutines.*

/** Distinguish a tap from a hold without firing playback before the hold completes. */
class ProgrammeOkPress(private val scope:CoroutineScope) {
    private var job:Job?=null
    private var down:Long?=null
    private var held=false
    fun cancel(){job?.cancel();job=null;down=null}
    fun handle(event:KeyEvent,onTap:()->Unit,onHold:()->Unit):Boolean {
        when(event.action){
            KeyEvent.ACTION_DOWN->if(event.repeatCount==0){
                cancel();down=event.downTime;held=false
                job=scope.launch{delay(ViewConfiguration.getLongPressTimeout().toLong());held=true;onHold()}
            }
            KeyEvent.ACTION_UP->{
                job?.cancel();job=null
                if(down==event.downTime&&!held&&!event.isCanceled){
                    if(event.eventTime-event.downTime>=ViewConfiguration.getLongPressTimeout())onHold()else onTap()
                }
                down=null
            }
        }
        return true
    }
}

/** A dialog must never treat the tail of its opening gesture as a new OK. */
class FreshOkPressGuard(private val openedAt:Long=android.os.SystemClock.uptimeMillis()) {
    private var acceptedDown:Long?=null
    fun consume(event:KeyEvent):Boolean {
        if(event.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER))return false
        return when(event.action){
            KeyEvent.ACTION_DOWN -> {
                if(event.repeatCount==0&&event.downTime>=openedAt){acceptedDown=event.downTime;false}else true
            }
            KeyEvent.ACTION_UP -> {
                val accepted=acceptedDown==event.downTime&&!event.isCanceled
                acceptedDown=null
                !accepted
            }
            else -> true
        }
    }
}
