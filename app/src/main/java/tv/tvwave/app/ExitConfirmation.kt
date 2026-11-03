package tv.tvwave.app

/** Monotonic, two distinct Back presses at the root; other input disarms it. */
class ExitConfirmation {
    private var firstPress: Long? = null
    fun press(now: Long): Boolean {
        val first = firstPress
        if (first != null && now - first in 0 until WINDOW_MS) {
            reset()
            return true
        }
        firstPress = now
        return false
    }
    fun reset() { firstPress = null }
    companion object { const val WINDOW_MS = 2500L }
}
