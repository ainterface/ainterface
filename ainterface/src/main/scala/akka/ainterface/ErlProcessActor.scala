package akka.ainterface

import akka.actor.{Actor, ActorContext}
import akka.ainterface.datatype.interpolation.atom
import akka.ainterface.datatype.{ErlExit, ErlReference, ErlTerm, ErlTuple}
import akka.ainterface.remote.RemoteNodeStatusEventBus.NodeDown

trait ErlProcessActor extends Actor { actor =>

  val process: ErlProcessContext = new ErlProcessContext {
    override protected[this] def context: ActorContext = actor.context
  }

  override protected[akka] def aroundPreStart(): Unit = {
    AinterfaceExtension(context.system).localNode.add(process.self, self)
    super.aroundPreStart()
  }

  override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    process.deliverObituary()
    super.aroundPreRestart(reason, message)
  }

  override protected[akka] def aroundPostRestart(reason: Throwable): Unit = {
    AinterfaceExtension(context.system).localNode.add(process.self, self)
    super.aroundPostRestart(reason)
  }

  override protected[akka] def aroundPostStop(): Unit = {
    process.deliverObituary()
    super.aroundPostStop()
  }

  override protected[akka] def aroundReceive(receive: Receive, msg: Any): Unit = {
    msg match {
      case event: ControlMessage => handleControlMessage(receive, event)
      case NodeDown(node) =>
        if (process.linking.exists(_.nodeName == node.asErlAtom)) {
          process.exit(ErlExit.Noconnection)
        }
        process.linked = process.linked.filterNot(_.nodeName == node.asErlAtom)
        val (down, alive) = process.monitoring.partition(_._2.nodeName == node)
        down.foreach {
          case (ref, target) => self ! processDown(target, ref, ErlExit.Noconnection)
        }
        process.monitoring = alive
        process.monitored = process.monitored.filterNot {
          case (_, (target, _)) => target.nodeName == node
        }
      case _ => super.aroundReceive(receive, msg)
    }
  }

  private[this] def handleControlMessage(receive: Receive, event: ControlMessage): Unit = {
    context.system.log.debug("{} received {}.", process.self, event)
    event match {
      case Link(from, _) => process.linked += from
      case Send(_, message) => super.aroundReceive(receive, message)
      case Exit(_, _, ErlExit.Normal) =>
      case Exit(from, _, _) if !process.linking.contains(from) =>
      case Exit(_, _, reason) => process.exit(reason)
      case Unlink(from, _) => process.linked -= from
      case RegSend(_, _, message) => super.aroundReceive(receive, message)
      case GroupLeader(_, _) =>
      case Exit2(_, _, ErlExit.Normal) =>
      case Exit2(_, _, reason) => process.exit(reason)
      case SendTT(_, _, message) => super.aroundReceive(receive, message)
      case ExitTT(_, _, _, ErlExit.Normal) =>
      case ExitTT(from, _, _, _) if !process.linking.contains(from) =>
      case ExitTT(_, _, _, reason) => process.exit(reason)
      case RegSendTT(_, _, _, message) => super.aroundReceive(receive, message)
      case Exit2TT(_, _, _, ErlExit.Normal) =>
      case Exit2TT(_, _, _, reason) => process.exit(reason)
      case MonitorP(from, to, ref) => process.monitored += ref -> (to, from)
      case DemonitorP(_, _, ref) => process.monitored -= ref
      case MonitorPExit(from, _, ref, reason) =>
        process.monitoring -= ref
        super.aroundReceive(receive, processDown(from, ref, reason))
    }
  }

  private[this] def processDown(from: Target, ref: ErlReference, reason: ErlTerm): ErlTuple = {
    val obj = from match {
      case Target.Pid(pid) => pid
      case Target.Name(name, node) => ErlTuple(name, node.asErlAtom)
    }
    ErlTuple(atom"DOWN", ref, atom"process", obj, reason)
  }
}
