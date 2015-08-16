package akka.ainterface.remote.handshake

import akka.ainterface.util.binary.ByteStringCodec.byteString
import akka.util.ByteString
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

/**
 * @see [[http://www.erlang.org/doc/apps/erts/erl_dist_protocol.html#id92374]]
 */
private[handshake] final case class HandshakeMessage[A](message: A)

private[handshake] object HandshakeMessage {
  private[this] def codecOf[A: Codec]: Codec[HandshakeMessage[A]] = {
    variableSizeBytes(uint16, implicitly[Codec[A]]).as[HandshakeMessage[A]]
  }

  implicit val nameCodec: Codec[HandshakeMessage[Name]] = codecOf[Name]
  implicit val statusCodec: Codec[HandshakeMessage[Status]] = codecOf[Status]
  implicit val aliveAnswerCodec: Codec[HandshakeMessage[AliveAnswer]] = codecOf[AliveAnswer]
  implicit val challengeCodec: Codec[HandshakeMessage[Challenge]] = codecOf[Challenge]
  implicit val challengeReplyCodec: Codec[HandshakeMessage[ChallengeReply]] = {
    codecOf[ChallengeReply]
  }
  implicit val challengeAckCodec: Codec[HandshakeMessage[ChallengeAck]] = codecOf[ChallengeAck]
}

/**
 * 2) send_name/receive_name
 */
private[handshake] final case class Name(version: Int,
                                         flags: Int,
                                         name: String)

private[handshake] object Name {
  implicit val codec: Codec[Name] = constant('n').dropLeft(uint16 :: int32 :: utf8).as[Name]
}

/**
 * 3) recv_status/send_status
 */
private[handshake] sealed abstract class Status
private[handshake] case object Ok extends Status
private[handshake] case object OkSimultaneous extends Status
private[handshake] case object Nok extends Status
private[handshake] case object NotAllowed extends Status
private[handshake] case object Alive extends Status

private[handshake] object Status {
  private[this] val ok = "ok"
  private[this] val okSimultaneous = "ok_simultaneous"
  private[this] val nok = "nok"
  private[this] val notAllowed = "not_allowed"
  private[this] val alive = "alive"

  implicit val codec: Codec[Status] = constant('s').dropLeft(ascii).narrow[Status](
    {
      case `ok` => Attempt.successful(Ok)
      case `okSimultaneous` => Attempt.successful(OkSimultaneous)
      case `nok` => Attempt.successful(Nok)
      case `notAllowed` => Attempt.successful(NotAllowed)
      case `alive` => Attempt.successful(Alive)
      case x => Attempt.failure(Err(s"$x is not a Status."))
    },
    {
      case Ok => ok
      case OkSimultaneous => okSimultaneous
      case Nok => nok
      case NotAllowed => notAllowed
      case Alive => alive
    }
  )
}

/**
 * 3B) send_status/recv_status
 */
private[handshake] sealed abstract class AliveAnswer
private[handshake] case object True extends AliveAnswer
private[handshake] case object False extends AliveAnswer

private[handshake] case object AliveAnswer {
  private[this] val t = "true"
  private[this] val f = "false"

  implicit val codec: Codec[AliveAnswer] = constant('s').dropLeft(ascii).narrow[AliveAnswer](
    {
      case `t` => Attempt.successful(True)
      case `f` => Attempt.successful(False)
      case x => Attempt.Failure(Err(s"$x is not an AliveAnswer."))
    },
    {
      case True => t
      case False => f
    }
  )
}

/**
 * 4) recv_challenge/send_challenge
 */
private[handshake] final case class Challenge(version: Int,
                                              flags: Int,
                                              challenge: Int,
                                              name: String)

private[handshake] object Challenge {
  implicit val codec: Codec[Challenge] = {
    constant('n').dropLeft(uint16 :: int32 :: int32 :: utf8).as[Challenge]
  }
}

/**
 * 5) send_challenge_reply/recv_challenge_reply
 */
private[handshake] final case class ChallengeReply(challenge: Int, digest: ByteString)

private[handshake] object ChallengeReply {
  implicit val codec: Codec[ChallengeReply] = {
    constant('r').dropLeft(int32 :: byteString(DigestLength)).as[ChallengeReply]
  }
}

/**
 * 6) recv_challenge_ack/send_challenge_ack
 */
private[handshake] final case class ChallengeAck(digest: ByteString)

private[handshake] object ChallengeAck {
  implicit val codec: Codec[ChallengeAck] = {
    constant('a').dropLeft(byteString(DigestLength)).as[ChallengeAck]
  }
}
