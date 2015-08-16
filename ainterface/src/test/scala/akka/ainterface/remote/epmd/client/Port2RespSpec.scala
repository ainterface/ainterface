package akka.ainterface.remote.epmd.client

import java.nio.charset.StandardCharsets
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult, Decoder, Err}

class Port2RespSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "codec" should {
    "succeeds to decode" when {
      "bits is valid" in {
        forAll(Gen.chooseNum(0, 0xffff), arbitrary[String]) {
          (port: Int, alive: String) =>
            val tag = BitVector(119)
            val result = BitVector(0)
            val portNo = BitVector.fromInt(port, size = 16)
            val nodeType = BitVector(77)
            val protocol = BitVector(0)
            val highestVersion = BitVector.fromInt(5, size = 16)
            val lowestVersion = BitVector.fromInt(5, size = 16)
            val aliveBytes = alive.getBytes(StandardCharsets.UTF_8)
            val nlen = BitVector.fromInt(aliveBytes.length, size = 16)
            val nodeName = BitVector(aliveBytes)
            val elen = BitVector.fromInt(0, size = 16)
            val extra = BitVector.empty
            val bits = tag ++ result ++ portNo ++ nodeType ++ protocol ++ highestVersion ++ lowestVersion ++ nlen ++ nodeName ++ elen ++ extra

            val actual = Decoder.decode[Port2Resp](bits)
            val resp = Port2RespSuccess(port, alive)
            val expected = Attempt.Successful(DecodeResult(resp, BitVector.empty))
            assert(actual === expected)
        }
      }
    }

    "decode and return InsufficientBits" when {
      "the response has the successful result but need more bits" in {
        val actual = Decoder.decode[Port2Resp](BitVector(119, 0))
        val expected = Attempt.failure(Err.InsufficientBits(16, 0, Nil))
        assert(actual === expected)
      }
    }

    "fails to decode" when {
      "bits is invalid" in {
        forAll(Gen.chooseNum(1, 255)) { result: Int =>
          val actual = Decoder.decode[Port2Resp](BitVector(119, result))
          val expected = Attempt.Successful(DecodeResult(Port2RespFailure(result), BitVector.empty))
          assert(actual === expected)
        }
      }
    }
  }
}
