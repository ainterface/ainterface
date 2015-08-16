package akka.ainterface.remote

import akka.ainterface._
import akka.ainterface.datatype.{ErlAtom, ErlInteger, ErlPid, ErlReference, ErlTerm, ErlTuple}
import scodec.Attempt.{failure, successful}
import scodec.bits.BitVector
import scodec.codecs._
import scodec.{Codec, DecodeResult, Err}

/**
 * A message from a remote node.
 * If it contains no control message, it is a tick message.
 */
private[remote] final case class RemoteMessage(controlMessage: Option[ControlMessage])

private[remote] object RemoteMessage {
  private[this] val LinkOp = ErlInteger(1)
  private[this] val SendOp = ErlInteger(2)
  private[this] val ExitOp = ErlInteger(3)
  private[this] val UnlinkOp = ErlInteger(4)
  private[this] val RegSendOp = ErlInteger(6)
  private[this] val GroupLeaderOp = ErlInteger(7)
  private[this] val Exit2Op = ErlInteger(8)

  private[this] val SendTTOp = ErlInteger(12)
  private[this] val ExitTTOp = ErlInteger(13)
  private[this] val RegSendTTOp = ErlInteger(16)
  private[this] val Exit2TTOp = ErlInteger(18)

  private[this] val MonitorPOp = ErlInteger(19)
  private[this] val DemonitorPOp = ErlInteger(20)
  private[this] val MonitorPExitOp = ErlInteger(21)

  private[this] val Cookie = ControlMessage.cookie

  private[this] val PassThrough = 112
  private[this] val VersionNumber = 131

  implicit private[this] val tupleWithVersionNumber: Codec[ErlTuple] = {
    constant(VersionNumber).dropLeft(akka.ainterface.datatype.ErlTermCodec.tupleCodec)
  }
  implicit private[this] val termWithVersionNumber: Codec[ErlTerm] = {
    constant(VersionNumber).dropLeft(akka.ainterface.datatype.ErlTermCodec.codec)
  }

  def codec(localNodeName: NodeName,
            remoteNodeName: NodeName): Codec[RemoteMessage] = {
    val ctlMsgCodec: Codec[ControlMessage] = {
      controlMessageCodec(localNodeName, remoteNodeName)
    }

    variableSizeBytesLong(uint32, bits).exmap(
      {
        case bits if bits.isEmpty => successful(RemoteMessage(None))
        case bits => ctlMsgCodec.decode(bits).flatMap {
          case DecodeResult(msg, BitVector.empty) => successful(RemoteMessage(Some(msg)))
          case x => failure(Err(s"Extra bits exist. $x"))
        }
      },
      {
        case RemoteMessage(None) => successful(BitVector.empty)
        case RemoteMessage(Some(msg)) => ctlMsgCodec.encode(msg)
      }
    )
  }

  private[this] def of(op: ErlInteger, terms: ErlTerm*): ErlTuple = ErlTuple(op +: terms: _*)
  private[this] def controlMessageCodec(localNodeName: NodeName,
                                        remoteNodeName: NodeName): Codec[ControlMessage] = {
    constant(PassThrough).dropLeft(Codec[ErlTuple]) >>~ { tuple =>
      val hasMessage = tuple.elements.headOption.exists {
        case SendOp | RegSendOp | SendTTOp | RegSendTTOp => true
        case _ => false
      }
      conditional(hasMessage, Codec[ErlTerm])
    } narrow(
      {
        case (ErlTuple(LinkOp, from: ErlPid, to: ErlPid), None) => successful(Link(from, to))
        case (ErlTuple(SendOp, _: ErlAtom, to: ErlPid), Some(message)) =>
          successful(Send(to, message))
        case (ErlTuple(ExitOp, from: ErlPid, to: ErlPid, reason), None) =>
          successful(Exit(from, to, reason))
        case (ErlTuple(UnlinkOp, from: ErlPid, to: ErlPid), None) =>
          successful(Unlink(from, to))
        case (ErlTuple(RegSendOp, from: ErlPid, _: ErlAtom, to: ErlAtom), Some(message)) =>
          successful(RegSend(from, Target.Name(to, localNodeName), message))
        case (ErlTuple(GroupLeaderOp, from: ErlPid, to: ErlPid), None) =>
          successful(GroupLeader(from, to))
        case (ErlTuple(Exit2Op, from: ErlPid, to: ErlPid, reason), None) =>
          successful(Exit2(from, to, reason))
        case (ErlTuple(SendTTOp, _: ErlAtom, to: ErlPid, traceToken), Some(message)) =>
          successful(SendTT(to, traceToken, message))
        case (ErlTuple(ExitTTOp, from: ErlPid, to: ErlPid, traceToken, reason), None) =>
          successful(ExitTT(from, to, traceToken, reason))
        case (ErlTuple(RegSendTTOp, from: ErlPid, _: ErlAtom, to: ErlAtom, traceToken), Some(message)) =>
          successful(RegSendTT(from, Target.Name(to, localNodeName), traceToken, message))
        case (ErlTuple(Exit2TTOp, from: ErlPid, to: ErlPid, traceToken, reason), None) =>
          successful(Exit2TT(from, to, traceToken, reason))
        case (ErlTuple(MonitorPOp, from: ErlPid, to: ErlPid, ref: ErlReference), None) =>
          successful(MonitorP(from, Target.Pid(to), ref))
        case (ErlTuple(MonitorPOp, from: ErlPid, to: ErlAtom, ref: ErlReference), None) =>
          successful(MonitorP(from, Target.Name(to, localNodeName), ref))
        case (ErlTuple(DemonitorPOp, from: ErlPid, to: ErlPid, ref: ErlReference), None) =>
          successful(DemonitorP(from, Target.Pid(to), ref))
        case (ErlTuple(DemonitorPOp, from: ErlPid, to: ErlAtom, ref: ErlReference), None) =>
          successful(DemonitorP(from, Target.Name(to, localNodeName), ref))
        case (ErlTuple(MonitorPExitOp, from: ErlPid, to: ErlPid, ref: ErlReference, reason: ErlTerm), None) =>
          successful(MonitorPExit(Target.Pid(from), to, ref, reason))
        case (ErlTuple(MonitorPExitOp, from: ErlAtom, to: ErlPid, ref: ErlReference, reason: ErlTerm), None) =>
          successful(MonitorPExit(Target.Name(from, remoteNodeName), to, ref, reason))
        case x => failure(Err(s"$x is not allowed as a control message."))
      },
      {
        case Link(from, to) => (of(LinkOp, from, to), None)
        case Send(to, message) => (of(SendOp, Cookie, to), Some(message))
        case Exit(from, to, reason) => (of(ExitOp, from, to, reason), None)
        case Unlink(from, to) => (of(UnlinkOp, from, to), None)
        case RegSend(from, Target.Name(to, `remoteNodeName`), message) =>
          (of(RegSendOp, from, Cookie, to), Some(message))
        case GroupLeader(from, to) => (of(GroupLeaderOp, from, to), None)
        case Exit2(from, to, reason) => (of(Exit2Op, from, to, reason), None)
        case SendTT(to, traceToken, message) =>
          (of(SendTTOp, Cookie, to, traceToken), Some(message))
        case ExitTT(from, to, traceToken, reason) =>
          (of(ExitTTOp, from, to, traceToken, reason), None)
        case RegSendTT(from, Target.Name(to, _), traceToken, message) =>
          (of(RegSendTTOp, from, Cookie, to, traceToken), Some(message))
        case Exit2TT(from, to, traceToken, reason) =>
          (of(Exit2TTOp, from, to, traceToken, reason), None)
        case MonitorP(from, Target.Pid(to), ref) => (of(MonitorPOp, from, to, ref), None)
        case MonitorP(from, Target.Name(to, `remoteNodeName`), ref) =>
          (of(MonitorPOp, from, to, ref), None)
        case DemonitorP(from, Target.Pid(to), ref) => (of(DemonitorPOp, from, to, ref), None)
        case DemonitorP(from, Target.Name(to, `remoteNodeName`), ref) =>
          (of(DemonitorPOp, from, to, ref), None)
        case MonitorPExit(Target.Pid(from), to, ref, reason) =>
          (of(MonitorPExitOp, from, to, ref, reason), None)
        case MonitorPExit(Target.Name(from, `localNodeName`), to, ref, reason) =>
          (of(MonitorPExitOp, from, to, ref, reason), None)
        case x =>
          sys.error(s"Source or target is illegal. message = $x, local = $localNodeName, remote = $remoteNodeName")
      }
      )
  }
}
