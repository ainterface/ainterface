package akka.ainterface.local

import akka.ainterface._
import akka.ainterface.datatype.ErlExit

/**
 * Handles messages for non-existent processes.
 */
private[ainterface] final class LocalDeadLetterBox(extension: AinterfaceExtensionImpl) {
  def drop(message: ControlMessage): Unit = message match {
    case Link(from, to) =>
      extension.sendEvent(Exit(to, from, ErlExit.Noproc))
    case MonitorP(from, to, ref) =>
      extension.sendEvent(MonitorPExit(to, from, ref, ErlExit.Noproc))
    case msg @ (_: Send | _: Exit | _: Unlink | _: RegSend | _: GroupLeader |
                _: Exit2 | _: SendTT | _: ExitTT | _: RegSendTT | _: Exit2TT |
                _: DemonitorP | _: MonitorPExit) =>
      extension.system.log.debug("{} is a dead letter.", msg)
  }
}
