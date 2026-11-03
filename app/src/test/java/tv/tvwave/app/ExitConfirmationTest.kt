package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Test

class ExitConfirmationTest {
    @Test fun twoPressesWithinWindowExit() {
        val exit = ExitConfirmation()
        assertFalse(exit.press(100))
        assertTrue(exit.press(900))
        assertFalse(exit.press(1000))
    }
    @Test fun expiredPressStartsANewWindow() {
        val exit = ExitConfirmation()
        assertFalse(exit.press(100))
        assertFalse(exit.press(2600))
        assertTrue(exit.press(3000))
    }
    @Test fun navigationDisarmsExit() {
        val exit = ExitConfirmation()
        assertFalse(exit.press(100))
        exit.reset()
        assertFalse(exit.press(200))
    }
}
