package akka.ainterface.remote.epmd.publisher

import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbBitVector
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult, Decoder}

class Alive2RespSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "codec" should {
    "decode and return Alive2RespSuccess" when {
      "the response is a successful one" in {
        forAll(Gen.chooseNum(0, 0xffff), arbitrary[BitVector]) {
          (creation: Int, extra: BitVector) =>
            val creationBits = BitVector.fromInt(creation, size = 16)
            val bits = BitVector(121) ++ BitVector(0) ++ creationBits ++ extra

            val actual = Decoder.decode[Alive2Resp](bits)
            val expected = Attempt.successful(DecodeResult(Alive2RespSuccess(creation), extra))
            assert(actual === expected)
        }
      }
    }

    "decode and return Alive2RespFailure" when {
      "the response is a failed one" in {
        forAll(Gen.chooseNum(1, 0xff), Gen.chooseNum(0, 0xffff), arbitrary[BitVector]) {
          (result: Int, creation: Int, extra: BitVector) =>
            val creationBits = BitVector.fromInt(creation, size = 16)
            val bits = BitVector(121) ++ BitVector(result) ++ creationBits ++ extra

            val actual = Decoder.decode[Alive2Resp](bits)
            val resp = Alive2RespFailure(result, creation)
            val expected = Attempt.successful(DecodeResult(resp, extra))
            assert(actual === expected)
        }
      }
    }
  }
}
