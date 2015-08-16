package akka.ainterface.remote.epmd.publisher

import java.nio.charset.StandardCharsets
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scodec.bits.BitVector
import scodec.{Attempt, Encoder}

class Alive2ReqSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "codec" should {
    "encode" in {
      forAll(Gen.chooseNum(1, 0xffff), arbitrary[String]) {
        (port: Int, alive: String) =>
          val req = Alive2Req(port, alive)

          val actual = Encoder.encode(req)
          val tag = BitVector(120)
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
          val bits = tag ++ portNo ++ nodeType ++ protocol ++ highestVersion ++ lowestVersion ++ nlen ++ nodeName ++ elen ++ extra
          assert(actual === Attempt.successful(bits))
      }
    }
  }
}
