package akka.ainterface.remote.epmd

import java.nio.charset.StandardCharsets
import org.scalacheck.Gen
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scodec.bits.BitVector
import scodec.{Attempt, Codec, Encoder}

class EpmdReqSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "Codec[EpmdReq[A]]" should {
    implicit val codec: Codec[String] = scodec.codecs.string(StandardCharsets.ISO_8859_1)

    "encode requests" in {
      forAll(Gen.alphaStr) { request: String =>
        val actual = Encoder.encode(EpmdReq(request))
        val bytes = request.getBytes(StandardCharsets.ISO_8859_1)
        val bits = BitVector.fromInt(bytes.length, size = 16) ++ BitVector(bytes)
        assert(actual === Attempt.successful(bits))
      }
    }
  }
}
