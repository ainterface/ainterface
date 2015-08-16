package akka.ainterface.test.arbitrary

import akka.util.ByteString
import org.scalacheck.{Arbitrary, Gen}

trait ByteStringArbitrary {
  implicit val arbByteString: Arbitrary[ByteString] = Arbitrary {
    Gen.frequency(
      1 -> Arbitrary.arbitrary[Array[Byte]].map(ByteString.apply),
      5 -> Gen.alphaStr.map(ByteString.apply)
    )
  }

  val genNonEmptyByteString: Gen[ByteString] = {
    Gen.nonEmptyContainerOf[Array, Byte](Arbitrary.arbByte.arbitrary).map(ByteString.apply)
  }

  def genByteString(size: Int): Gen[ByteString] = {
    Gen.listOfN(size, Arbitrary.arbitrary[Byte]).map(_.toArray).map(ByteString.apply)
  }
}
