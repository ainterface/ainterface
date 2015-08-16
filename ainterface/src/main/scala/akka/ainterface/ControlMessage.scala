package akka.ainterface

import akka.ainterface.datatype.{ErlAtom, ErlPid, ErlReference, ErlTerm}

private[ainterface] sealed abstract class ControlMessage {
  def target: Target
}

private[ainterface] object ControlMessage {
  /**
   * Cookie is currently unused.
   * @see [[http://comments.gmane.org/gmane.comp.lang.erlang.general/74817]]
   */
  val cookie: ErlAtom = ErlAtom("")
}

private[ainterface] sealed abstract class Target {
  def nodeName: NodeName
}
private[ainterface] object Target {
  final case class Pid(pid: ErlPid) extends Target {
    override def nodeName: NodeName = NodeName(pid.nodeName)
  }
  final case class Name(name: ErlAtom, nodeName: NodeName) extends Target
}

/**
 * {1, FromPid, ToPid}
 */
private[ainterface] final case class Link(from: ErlPid, to: ErlPid) extends ControlMessage {
  override def target: Target = Target.Pid(to)
}

/**
 * {2, Cookie, ToPid} and a message.
 */
private[ainterface] final case class Send(to: ErlPid, message: ErlTerm) extends ControlMessage {
  def cookie: ErlAtom = ControlMessage.cookie
  override def target: Target = Target.Pid(to)
}

/**
 * {3, FromPid, ToPid, Reason}
 * A process terminates, this message is sent to linked processes.
 */
private[ainterface] final case class Exit(from: ErlPid,
                                      to: ErlPid,
                                      reason: ErlTerm) extends ControlMessage {
  override def target: Target = Target.Pid(to)
}

/**
 * {4, FromPid, ToPid}
 */
private[ainterface] final case class Unlink(from: ErlPid, to: ErlPid) extends ControlMessage {
  override def target: Target = Target.Pid(to)
}

/**
 * {5}
 * Currently not used.
 */
//private[ainterface] case object NodeLink extends ControlMessage

/**
 * {6, FromPid, Cookie, ToName} and a message.
 */
private[ainterface] final case class RegSend(from: ErlPid,
                                         toName: Target.Name,
                                         message: ErlTerm) extends ControlMessage {
  def cookie: ErlAtom = ControlMessage.cookie
  override def target: Target = toName
}

/**
 * {7, FromPid, ToPid}
 */
private[ainterface] final case class GroupLeader(from: ErlPid, to: ErlPid) extends ControlMessage {
  override def target: Target = Target.Pid(to)
}

/**
 * {8, FromPid, ToPid, Reason}
 * erlang:exit/2 generates this message.
 */
private[ainterface] final case class Exit2(from: ErlPid,
                                       to: ErlPid,
                                       reason: ErlTerm) extends ControlMessage {
  override def target: Target = Target.Pid(to)
}

/**
 * {12, Cookie, ToPid, TraceToken} and a message.
 */
private[ainterface] final case class SendTT(to: ErlPid,
                                        traceToken: ErlTerm,
                                        message: ErlTerm) extends ControlMessage {
  def cookie: ErlAtom = ControlMessage.cookie
  override def target: Target = Target.Pid(to)
}

/**
 * {13, FromPid, ToPid, TraceToken, Reason}
 */
private[ainterface] final case class ExitTT(from: ErlPid,
                                        to: ErlPid,
                                        traceToken: ErlTerm,
                                        reason: ErlTerm) extends ControlMessage {
  override def target: Target = Target.Pid(to)
}

/**
 * {16, FromPid, Cookie, ToName, TraceToken} and a message.
 */
private[ainterface] case class RegSendTT(from: ErlPid,
                                     toName: Target.Name,
                                     traceToken: ErlTerm,
                                     message: ErlTerm) extends ControlMessage {
  def cookie: ErlAtom = ControlMessage.cookie
  override def target: Target = toName
}

/**
 * {18, FromPid, ToPid, TraceToken, Reason}
 */
private[ainterface] final case class Exit2TT(from: ErlPid,
                                         to: ErlPid,
                                         traceToken: ErlTerm,
                                         reason: ErlTerm) extends ControlMessage {
  override def target: Target = Target.Pid(to)
}

/**
 * {19, FromPid, ToProc, Ref}
 */
private[ainterface] final case class MonitorP(from: ErlPid,
                                          to: Target,
                                          ref: ErlReference) extends ControlMessage {
  override def target: Target = to
}

/**
 * {20, FromPid, ToProc, Ref}
 */
private[ainterface] final case class DemonitorP(from: ErlPid,
                                            to: Target,
                                            ref: ErlReference) extends ControlMessage {
  override def target: Target = to
}

/**
 * {21, FromProc, ToPid, Ref, Reason}
 */
private[ainterface] final case class MonitorPExit(from: Target,
                                              to: ErlPid,
                                              ref: ErlReference,
                                              reason: ErlTerm) extends ControlMessage {
  override def target: Target = Target.Pid(to)
}
