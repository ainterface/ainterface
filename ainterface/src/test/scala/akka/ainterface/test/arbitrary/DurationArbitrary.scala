package akka.ainterface.test.arbitrary

import java.util.concurrent.TimeUnit
import org.scalacheck.{Arbitrary, Gen}
import scala.concurrent.duration.FiniteDuration

trait DurationArbitrary {
  implicit val arbFiniteDuration: Arbitrary[FiniteDuration] = Arbitrary(genFiniteDuration())

  def genFiniteDuration(minMillis: Int = 1, maxMillis: Int = Int.MaxValue): Gen[FiniteDuration] = {
    Gen.chooseNum(minMillis, maxMillis).map(FiniteDuration(_, TimeUnit.MILLISECONDS))
  }
}
