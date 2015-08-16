package akka.ainterface.remote.epmd.client

import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scodec.bits.BitVector
import scodec.{Attempt, Encoder}

class PortPlease2ReqSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "codec" should {
    "encode" in {
      forAll { alive: String =>
        val actual = Encoder.encode(PortPlease2Req(alive))
        val expected = Attempt.Successful(BitVector(122) ++ BitVector.encodeUtf8(alive).right.get)
        assert(actual === expected)
      }
    }
  }
}
