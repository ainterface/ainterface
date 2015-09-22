package akka.ainterface.remote

import akka.ainterface.datatype._
import akka.ainterface.test.arbitrary.AinterfaceArbitrary._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}

class SendSpec extends BaseSpec {
  // TODO: tests all formats
  private[this] def genErlTerm(level: Int): Gen[ErlTerm] = {
    def genScalar: Gen[ErlTerm] = Gen.oneOf(
      arbitrary[ErlInteger],
      arbitrary[ErlFloat],
      arbitrary[ErlAtom],
      arbitrary[ErlBitString],
      arbitrary[ErlBitStringImpl],
      arbitrary[ErlBinary],
      arbitrary[ErlReference],
      arbitrary[ErlNewReference],
      arbitrary[ErlExternalFun],
      //arbitrary[ErlPort],
      arbitrary[ErlPid]
    )

    genScalar
  }

  implicit lazy val arbErlTerm: Arbitrary[ErlTerm] = Arbitrary(genErlTerm(0))

  "ErlTermCodec" should {
    "encode/decode round-trip" in {
      forAll { term: ErlTerm =>
        process.send(ErlAtom("echo"), ErlAtom("erltest@okumin-mini.local"), ErlTuple(process.self, ErlAtom("mofu")))
        val echo = process.receive() match {
          case ErlTuple(from: ErlPid, _) => from
        }

        process.send(echo, ErlTuple(process.self, term))
        val response = process.receive()
        assert(response === ErlTuple(echo, term))
      }
    }
  }
}
