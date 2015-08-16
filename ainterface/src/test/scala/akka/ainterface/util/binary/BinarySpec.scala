package akka.ainterface.util.binary

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scodec.Attempt.Successful
import scodec.bits.BitVector
import scodec.codecs._
import scodec.{Codec, DecodeResult}

class BinarySpec extends WordSpec with GeneratorDrivenPropertyChecks {
  private[this] val tag: Byte = 100
  private[this] val codec: Codec[Int] = constant(tag).dropLeft(int8)
  private[this] val outOfRangeGen: Gen[Int] = Gen.oneOf(
    Gen.choose(Int.MinValue, Byte.MinValue - 1),
    Gen.choose(Byte.MaxValue + 1, Int.MaxValue)
  )

  "encode" should {
    "return ByteString" when {
      "Encoder results in success" in {
        forAll { v: Byte =>
          val actual = Binary.encode(v.toInt)(codec)
          assert(actual === ByteString(100, v))
        }
      }
    }

    "throw a RuntimeException" when {
      "Encoder results in failure" in {
        forAll(outOfRangeGen) { v: Int =>
          intercept[RuntimeException] {
            Binary.encode(v)(codec)
          }
        }
      }
    }
  }

  "decode" should {
    "return a successful result" when {
      "Decoder results in success" in {
        forAll { (v: Byte, bytes: Array[Byte]) =>
          val actual = Binary.decode(ByteString(tag +: v +: bytes))(codec)
          val expected = Successful(DecodeResult(v, BitVector(bytes)))
          assert(actual === expected)
        }
      }
    }

    "return a failure result" when {
      "Decoder results in failure" in {
        forAll { bytes: Array[Byte] =>
          val invalidTag: Byte = 99
          val actual = Binary.decode(ByteString(invalidTag +: bytes))(codec)
          assert(actual.isFailure)
        }
      }
    }
  }
}
