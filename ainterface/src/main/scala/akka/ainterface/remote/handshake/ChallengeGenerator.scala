package akka.ainterface.remote.handshake

import java.security.SecureRandom
import scala.util.Random

private[handshake] abstract class ChallengeGenerator {
  /**
   * Generates a challenge.
   */
  def genChallenge(): Int
}

private[handshake] object ChallengeGenerator extends ChallengeGenerator {
  private[this] val random: Random = new Random(new SecureRandom())
  /**
   * Generates a challenge.
   */
  override def genChallenge(): Int = random.nextInt()
}
