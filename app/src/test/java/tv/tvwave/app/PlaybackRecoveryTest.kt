package tv.tvwave.app
import org.junit.Assert.*
import org.junit.Test
class PlaybackRecoveryTest {
 @Test fun boundedBudgetNeedsExplicitReset(){
  val budget=PlaybackRecovery()
  assertEquals(2000L,budget.nextDelay());assertEquals(5000L,budget.nextDelay());assertEquals(10000L,budget.nextDelay())
  repeat(5){assertNull(budget.nextDelay())};assertEquals(3,budget.attempts)
  budget.reset();assertEquals(2000L,budget.nextDelay())
 }
}
