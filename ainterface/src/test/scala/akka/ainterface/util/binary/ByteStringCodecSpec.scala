package akka.ainterface.util.binary

import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbByteString
import akka.util.ByteString
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scodec.Attempt.{Failure, Successful}
import scodec.DecodeResult
import scodec.Err.{General, InsufficientBits}
import scodec.bits.BitVector

class ByteStringCodecSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "byteString" when {
    "the size is not specified" should {
      "succeed in encoding" in {
        forAll { byteString: ByteString =>
          val actual = ByteStringCodec.byteString.encode(byteString)
          assert(actual === Successful(BitVector(byteString.toArray)))
        }
      }

      "succeed in decoding" in {
        forAll { bytes: Array[Byte] =>
          val actual = ByteStringCodec.byteString.decode(BitVector(bytes))
          val expected = Successful(DecodeResult(ByteString(bytes), BitVector.empty))
          assert(actual === expected)
        }
      }
    }

    "the size is specified" should {
      "succeed in encoding" when {
        "receiving a ByteString with the specified length" in {
          forAll { byteString: ByteString =>
            val actual = ByteStringCodec.byteString(byteString.length).encode(byteString)
            assert(actual === Successful(BitVector(byteString.toArray)))
          }
        }
      }

      "fail in encoding" when {
        "receiving a ByteString with shorter length than the specified length" in {
          val gen = for {
            byteString <- Arbitrary.arbitrary[ByteString]
            if byteString.nonEmpty
            size <- Gen.chooseNum(0, byteString.size - 1)
          } yield (size, byteString)
          forAll(gen) {
            case (size: Int, byteString: ByteString) =>
              val actual = ByteStringCodec.byteString(size).encode(byteString)
              val Failure(err) = actual
              assert(err.isInstanceOf[General])
          }
        }
      }

      "succeed in decoding" when {
        "receiving sufficient bits" in {
          forAll { (bytes: Array[Byte], extra: Array[Byte]) =>
            val bits = BitVector(bytes ++ extra)
            val actual = ByteStringCodec.byteString(bytes.length).decode(bits)
            val expected = Successful(DecodeResult(ByteString(bytes), BitVector(extra)))
            assert(actual === expected)
          }
        }
      }

      "need more bits" when {
        "receiving insufficient bits" in {
          forAll(Gen.chooseNum[Int](1, Short.MaxValue), Arbitrary.arbitrary[Array[Byte]]) {
            (more: Int, bytes: Array[Byte]) =>
              val bits = BitVector(bytes)
              val size = bytes.length + more
              val actual = ByteStringCodec.byteString(size).decode(bits)
              val Failure(err: InsufficientBits) = actual
              assert(err === InsufficientBits(size * 8, bytes.length * 8, Nil))
          }
        }
      }
    }
  }
}
