package ainterface

import ainterface.ProcessActorProtocol._
import akka.actor.ActorLogging
import akka.ainterface.ErlProcessActor
import akka.ainterface.datatype.{ErlAtom, ErlPid, ErlReference, ErlTerm}
import scala.collection.immutable.Queue

class ProcessActor extends ErlProcessActor with ActorLogging {
  override def preStart(): Unit = {
    log.info("{} started.", process.self)
  }

  var queue: Queue[ErlTerm] = Queue.empty

  override def receive: Receive = {
    case Receive => queue.dequeueOption match {
      case None => sender() ! None
      case Some((message, q)) =>
        sender() ! Some(message)
        queue = q
    }
    case SelfPid => sender() ! process.self
    case MakeRef => sender() ! process.makeRef()
    case Register(name, pid) => sender() ! process.register(name, pid)
    case Unregister(name) => sender() ! process.unregister(name)
    case Send(dest, message) => sender() ! process.send(dest, message)
    case RegSend(name, None, message) => sender() ! process.send(name, message)
    case RegSend(name, Some(node), message) => sender() ! process.send(name, node, message)
    case Link(to) => sender() ! process.link(to)
    case Unlink(to) => sender() ! process.unlink(to)
    case Monitor(pid) => sender() ! process.monitor(pid)
    case Demonitor(ref) => sender() ! process.demonitor(ref)
    case Exit(reason) => sender() ! process.exit(reason)
    case Exit2(pid, reason) => sender() ! process.exit(pid, reason)
    case x: ErlTerm => queue = queue.enqueue(x)
    case x => log.error("Received an unexpected message. {}", x)
  }

  override def postStop(): Unit = {
    log.info("{} terminated.", process.self)
  }
}

object ProcessActorProtocol {
  case object Receive
  case object SelfPid
  case object MakeRef
  case class Register(name: ErlAtom, pid: ErlPid)
  case class Unregister(name: ErlAtom)
  case class Send(dest: ErlPid, message: ErlTerm)
  case class RegSend(name: ErlAtom, node: Option[ErlAtom], message: ErlTerm)
  case class Link(to: ErlPid)
  case class Unlink(to: ErlPid)
  case class Monitor(pid: ErlPid)
  case class Demonitor(ref: ErlReference)
  case class Exit(reason: ErlTerm)
  case class Exit2(pid: ErlPid, reason: ErlTerm)
}
