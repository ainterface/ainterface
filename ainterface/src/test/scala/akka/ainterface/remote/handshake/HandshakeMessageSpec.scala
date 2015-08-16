package akka.ainterface.remote.handshake

import akka.ainterface.remote.handshake.HandshakeMessageArbitrary._
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.{arbBitVector, genByteString}
import akka.util.ByteString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scodec.Attempt.{Failure, Successful}
import scodec.Err.{General, InsufficientBits}
import scodec.bits.BitVector
import scodec.{Codec, DecodeResult, Decoder, Encoder}

class HandshakeMessageSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "HandshakeMessage.codec" should {
    "be able to encode and decode" in {
      def test[A: Arbitrary]()(implicit codec: Codec[HandshakeMessage[A]]) = {
        forAll { (message: HandshakeMessage[A], extra: BitVector) =>
          val bits = Encoder.encode(message).require
          val actual = Decoder.decode[HandshakeMessage[A]](bits ++ extra)
          assert(actual === Successful(DecodeResult(message, extra)))
        }
      }

      test[Name]()
      test[Status]()
      test[AliveAnswer]()
      test[Challenge]()
      test[ChallengeReply]()
      test[ChallengeAck]()
    }

    "need more bits" when {
      "receiving insufficient bits" in {
        def test[A: Arbitrary]()(implicit codec: Codec[HandshakeMessage[A]]) = {
          val gen = for {
            message <- arbitrary[HandshakeMessage[A]]
            bits = Encoder.encode(message).require
            length <- Gen.chooseNum(0L, bits.length - 1)
          } yield (bits, length)
          forAll(gen) {
            case (bits: BitVector, length: Long) =>
              val actual = Decoder.decode[HandshakeMessage[A]](bits.take(length))
              val Failure(err) = actual
              assert(err.isInstanceOf[InsufficientBits])
          }
        }

        test[Name]()
        test[Status]()
        test[AliveAnswer]()
        test[Challenge]()
        test[ChallengeReply]()
        test[ChallengeAck]()
      }
    }
  }


  "Name.codec" should {
    "encode" in {
      forAll(Gen.chooseNum(0, 0xffff), arbitrary[Int], arbitrary[String]) {
        (version: Int, flags: Int, name: String) =>
          val actual = Encoder.encode(Name(version, flags, name))
          val versionBits = BitVector.fromInt(version, size = 16)
          val flagsBits = BitVector.fromInt(flags)
          val nameBits = BitVector.encodeUtf8(name).right.get
          val expected = Successful(BitVector('n'.toByte) ++ versionBits ++ flagsBits ++ nameBits)
          assert(actual === expected)
      }
    }

    "decode" in {
      forAll(Gen.chooseNum(0, 0xffff), arbitrary[Int], arbitrary[String]) {
        (version: Int, flags: Int, name: String) =>
          val versionBits = BitVector.fromInt(version, size = 16)
          val flagsBits = BitVector.fromInt(flags)
          val nameBits = BitVector.encodeUtf8(name).right.get
          val bits = BitVector('n'.toByte) ++ versionBits ++ flagsBits ++ nameBits
          val actual = Decoder.decode[Name](bits)
          val expected = Successful(DecodeResult(Name(version, flags, name), BitVector.empty))
          assert(actual === expected)
      }
    }
  }

  "Status.codec" should {
    val cases = Seq(
      Ok -> "ok",
      OkSimultaneous -> "ok_simultaneous",
      Nok -> "nok",
      NotAllowed -> "not_allowed",
      Alive -> "alive"
    ).map {
      case (message, status) =>
        (message, BitVector('s'.toByte) ++ BitVector.encodeAscii(status).right.get)
    }

    "encode" in {
      cases.foreach {
        case (message, bits) =>
          val actual = Encoder.encode(message)
          assert(actual === Successful(bits))
      }
    }

    "succeed in decoding" when {
      "status is valid" in {
        cases.foreach {
          case (message, bits) =>
            val actual = Decoder.decode[Status](bits)
            val expected = Successful(DecodeResult(message, BitVector.empty))
            assert(actual === expected)
        }
      }
    }

    "fail in decoding" when {
      "status is invalid" in {
        forAll(Gen.alphaStr.filter(_.nonEmpty)) { status: String =>
          val bits = BitVector('s'.toByte) ++ BitVector.encodeAscii(status).right.get
          whenever(!cases.map(_._2).contains(bits)) {
            val actual = Decoder.decode[Status](bits)
            val Failure(err) = actual
            assert(err.isInstanceOf[General])
          }
        }
      }
    }
  }

  "AliveAnswer.codec" should {
    val cases = Seq(
      True -> "true",
      False -> "false"
    ).map {
      case (message, status) =>
        (message, BitVector('s'.toByte) ++ BitVector.encodeAscii(status).right.get)
    }

    "encode" in {
      cases.foreach {
        case (message, bits) =>
          val actual = Encoder.encode(message)
          assert(actual === Successful(bits))
      }
    }

    "succeed in decoding" when {
      "status is valid" in {
        cases.foreach {
          case (message, bits) =>
            val actual = Decoder.decode[AliveAnswer](bits)
            val expected = Successful(DecodeResult(message, BitVector.empty))
            assert(actual === expected)
        }
      }
    }

    "fail in decoding" when {
      "status is invalid" in {
        forAll(Gen.alphaStr.filter(_.nonEmpty)) { status: String =>
          val bits = BitVector('s'.toByte) ++ BitVector.encodeAscii(status).right.get
          whenever(!cases.map(_._2).contains(bits)) {
            val actual = Decoder.decode[AliveAnswer](bits)
            val Failure(err) = actual
            assert(err.isInstanceOf[General])
          }
        }
      }
    }
  }

  "Challenge.codec" should {
    "encode" in {
      forAll(Gen.chooseNum(0, 0xffff), arbitrary[Int], arbitrary[Int], arbitrary[String]) {
        case (version: Int, flags: Int, challenge: Int, name: String) =>
          val actual = Encoder.encode(Challenge(version, flags, challenge, name))
          val versionBits = BitVector.fromInt(version, size = 16)
          val flagsBits = BitVector.fromInt(flags)
          val challengeBits = BitVector.fromInt(challenge)
          val nameBits = BitVector.encodeUtf8(name).right.get
          val bits = BitVector('n'.toByte) ++ versionBits ++ flagsBits ++ challengeBits ++ nameBits
          assert(actual === Successful(bits))
      }
    }

    "decode" in {
      forAll(Gen.chooseNum(0, 0xffff), arbitrary[Int], arbitrary[Int], arbitrary[String]) {
        case (version: Int, flags: Int, challenge: Int, name: String) =>
          val versionBits = BitVector.fromInt(version, size = 16)
          val flagsBits = BitVector.fromInt(flags)
          val challengeBits = BitVector.fromInt(challenge)
          val nameBits = BitVector.encodeUtf8(name).right.get
          val bits = BitVector('n'.toByte) ++ versionBits ++ flagsBits ++ challengeBits ++ nameBits
          val actual = Decoder.decode[Challenge](bits)
          val result = DecodeResult(Challenge(version, flags, challenge, name), BitVector.empty)
          val expected = Successful(result)
          assert(actual === expected)
      }
    }
  }

  "ChallengeReply.codec" should {
    "encode" in {
      forAll(arbitrary[Int], genByteString(16)) { (challenge: Int, digest: ByteString) =>
        val actual = Encoder.encode(ChallengeReply(challenge, digest))
        val bits = BitVector('r'.toByte) ++ BitVector.fromInt(challenge) ++ BitVector(digest)
        assert(actual === Successful(bits))
      }
    }

    "decode" in {
      forAll(arbitrary[Int], genByteString(16)) { (challenge: Int, digest: ByteString) =>
        val bits = BitVector('r'.toByte) ++ BitVector.fromInt(challenge) ++ BitVector(digest)
        val actual = Decoder.decode[ChallengeReply](bits)
        val expected = Successful(DecodeResult(ChallengeReply(challenge, digest), BitVector.empty))
        assert(actual === expected)
      }
    }
  }

  "ChallengeAck.codec" should {
    "encode" in {
      forAll(genByteString(16)) { digest: ByteString =>
        val actual = Encoder.encode(ChallengeAck(digest))
        val bits = BitVector('a'.toByte) ++ BitVector(digest)
        assert(actual === Successful(bits))
      }
    }

    "decode" in {
      forAll(genByteString(16)) { digest: ByteString =>
        val bits = BitVector('a'.toByte) ++ BitVector(digest)
        val actual = Decoder.decode[ChallengeAck](bits)
        assert(actual === Successful(DecodeResult(ChallengeAck(digest), BitVector.empty)))
      }
    }
  }
}
