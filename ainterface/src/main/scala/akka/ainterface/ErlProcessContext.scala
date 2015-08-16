package akka.ainterface

import akka.actor.ActorContext
import akka.ainterface.datatype.{ErlAtom, ErlError, ErlExit, ErlPid, ErlReference, ErlTerm}

trait ErlProcessContext {
  private[ainterface] var linking: Set[ErlPid] = Set.empty
  private[ainterface] var linked: Set[ErlPid] = Set.empty
  private[ainterface] var monitoring: Map[ErlReference, Target] = Map.empty
  private[ainterface] var monitored: Map[ErlReference, (Target, ErlPid)] = Map.empty
  private[ainterface] var exitReason: Option[ErlTerm] = None

  private[ainterface] def deliverObituary(): Unit = {
    extension.localNode.remove(self)
    val reason = exitReason match {
      case None => ErlExit.Normal
      case Some(ErlExit.Kill) => ErlExit.Killed
      case Some(x) => x
    }
    linked.foreach { to =>
      extension.sendEvent(Exit(self, to, reason))
    }
    monitored.foreach {
      case (ref, (from, to)) =>
        extension.sendEvent(MonitorPExit(from, to, ref, reason))
    }
  }

  protected[this] def context: ActorContext
  private[this] def extension: AinterfaceExtensionImpl = AinterfaceExtension(context.system)

  val self: ErlPid = extension.localNode.createPid()
  def nodeName: ErlAtom = self.nodeName

  /**
   * Returns a unique reference.
   * @see [[http://www.erlang.org/doc/efficiency_guide/advanced.html#unique_references]]
   */
  def makeRef(): ErlReference = extension.localNode.makeRef()

  /**
   * Associates the name with the pid.
   * @param name the name
   * @param pid the pid
   * @throws ErlError badarg if the pid an existing local pid,
   *                  the name is already in use,
   *                  the pid already has another name or
   *                  the name is `undefined`.
   */
  def register(name: ErlAtom, pid: ErlPid): Unit = {
    extension.localNode.register(name, pid)
  }

  /**
   * Removes the registered name.
   * @param name the registered name
   * @throws ErlError badarg if the name is not registered.
   */
  def unregister(name: ErlAtom): Unit = {
    extension.localNode.unregister(name)
  }

  /**
   * Sends a message.
   * @param dest the destination of the message
   * @param msg the message
   */
  def send(dest: ErlPid, msg: ErlTerm): Unit = {
    extension.sendEvent(Send(dest, msg))
  }

  /**
   * Sends a message to the local process with the registered name.
   * @param name the target registered name
   * @param msg the message
   */
  def send(name: ErlAtom, msg: ErlTerm): Unit = {
    send(name, nodeName, msg)
  }

  /**
   * Sends a message to the process on the specified node with the registered name.
   * @param name the target registered name
   * @param node the target node
   * @param msg the message
   */
  def send(name: ErlAtom, node: ErlAtom, msg: ErlTerm): Unit = {
    extension.sendEvent(RegSend(self, Target.Name(name, NodeName(node)), msg))
  }

  /**
   * Creates a link between this process and the pid.
   * This process receives an exit signal with reason `noproc` if `to` process does not exist.
   * @note The behavior of erlang:link/1 depends on trapping exit or the location of the target.
   *       This `link` satisfies the transparency of those cases, so an exit signal is already
   *       received whenever the target process is not found.
   * @param to the pid to link.
   */
  def link(to: ErlPid): Unit = {
    if (to != self) {
      linking += to
      if (to.nodeName != self.nodeName) {
        extension.remoteNodeStatusEventBus.subscribe(context.self, NodeName(to.nodeName))
      }
      extension.sendEvent(Link(self, to))
    }
  }

  /**
   * Removes the link between this process and the pid.
   * `unlink` does nothing if no link with the pid exists.
   * Once `unlink` is called,
   * it is guaranteed that the no exit signal from the pid is received unless the link is set again.
   * @param to the pid to remove the link.
   */
  def unlink(to: ErlPid): Unit = {
    linking -= to
    extension.sendEvent(Unlink(self, to))
  }

  /**
   * Sends a request to monitor the process.
   * @param pid the pid to monitor
   * @return a monitor reference
   */
  def monitor(pid: ErlPid): ErlReference = {
    val ref = makeRef()
    monitoring += ref -> Target.Pid(pid)
    extension.remoteNodeStatusEventBus.subscribe(context.self, NodeName(pid.nodeName))
    extension.sendEvent(MonitorP(self, Target.Pid(pid), ref))
    ref
  }

  /**
   * Turns the monitoring off.
   * `demonitor` does nothing if the monitoring is already turned off.
   * Once `demonitor` is called, it is guaranteed that no down message is received.
   * @param ref the monitor reference
   */
  def demonitor(ref: ErlReference): Unit = {
    monitoring.get(ref) match {
      case None =>
      case Some(target) =>
        monitoring -= ref
        extension.sendEvent(DemonitorP(self, target, ref))
    }
  }

  def exit(reason: ErlTerm): Unit = reason match {
    case ErlExit.Normal => context.stop(context.self)
    case _ =>
      exitReason = Some(reason)
      throw ErlExit(reason)
  }

  /**
   * Sends an exit signal to the pid.
   * @param pid the target pid
   * @param reason the exit reason
   */
  def exit(pid: ErlPid, reason: ErlTerm): Unit = {
    extension.sendEvent(Exit2(self, pid, reason))
  }
}
