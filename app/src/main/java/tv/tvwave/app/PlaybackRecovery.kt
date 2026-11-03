package tv.tvwave.app

/** A bounded budget; brief recoveries do not replenish it. Main-thread owned. */
class PlaybackRecovery {
    var attempts=0; private set
    fun reset(){attempts=0}
    fun nextDelay():Long?=longArrayOf(2000,5000,10000).getOrNull(attempts)?.also{attempts++}
}
