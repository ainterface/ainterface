package akka.ainterface.test.arbitrary

import akka.io.Tcp.{Aborted, Closed, ConfirmedClosed, ConnectionClosed, ErrorClosed, PeerClosed}
import org.scalacheck.{Arbitrary, Gen}
import scodec.bits.BitVector

trait BitVectorArbitrary {
  implicit val arbBitVector: Arbitrary[BitVector] = Arbitrary {
    for {
      bytes <- Arbitrary.arbitrary[Array[Byte]]
      len <- Gen.posNum[Int]
    } yield BitVector(bytes).take(len)
  }

  implicit val arbConnectionClosed: Arbitrary[ConnectionClosed] = Arbitrary {
    Gen.oneOf(Closed, Aborted, ConfirmedClosed, PeerClosed, ErrorClosed("error"))
  }
}
