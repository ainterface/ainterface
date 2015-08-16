package akka.ainterface.remote.handshake

import akka.ainterface.test.arbitrary.AinterfaceArbitrary.{arbNodeName, genByteString}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import scodec.Codec

object HandshakeMessageArbitrary {
  implicit def arbHandshakeMessage[A: Arbitrary]
    (implicit codec: Codec[HandshakeMessage[A]]): Arbitrary[HandshakeMessage[A]] = {
    Arbitrary {
      arbitrary[A].map(HandshakeMessage.apply[A])
    }
  }

  implicit val arbName: Arbitrary[Name] = Arbitrary {
    for {
      version <- Gen.chooseNum(0, 0xffff)
      flags <- arbitrary[Int]
      name <- arbNodeName.arbitrary
    } yield Name(version, flags, name.asString)
  }

  implicit val arbStatus: Arbitrary[Status] = Arbitrary {
    Gen.oneOf(Ok, OkSimultaneous, Nok, NotAllowed, Alive)
  }

  implicit val arbAliveAnswer: Arbitrary[AliveAnswer] = Arbitrary {
    Gen.oneOf(True, False)
  }

  implicit val arbChallenge: Arbitrary[Challenge] = Arbitrary {
    for {
      version <- Gen.choose(0, 0xffff)
      flags <- arbitrary[Int]
      challenge <- arbitrary[Int]
      name <- arbitrary[String]
    } yield Challenge(version, flags, challenge, name)
  }

  implicit val arbChallengeReply: Arbitrary[ChallengeReply] = Arbitrary {
    for {
      challenge <- arbitrary[Int]
      digest <- genByteString(16)
    } yield ChallengeReply(challenge, digest)
  }

  implicit val arbChallengeAck: Arbitrary[ChallengeAck] = Arbitrary {
    genByteString(16).map(ChallengeAck.apply)
  }
}
