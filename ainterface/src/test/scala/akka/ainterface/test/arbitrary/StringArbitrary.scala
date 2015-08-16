package akka.ainterface.test.arbitrary

import org.scalacheck.{Arbitrary, Gen}

trait StringArbitrary {
  val arbReadableString: Arbitrary[String] = Arbitrary {
    Gen.frequency(
      1 -> Arbitrary.arbitrary[String],
      10 -> Gen.listOfN(10, Gen.alphaNumChar).map(xs => new String(xs.toArray))
    )
  }
}
