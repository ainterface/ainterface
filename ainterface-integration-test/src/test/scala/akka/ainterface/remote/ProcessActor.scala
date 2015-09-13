package akka.ainterface.remote

import akka.actor.{ActorLogging, ActorRef}
import akka.ainterface.ErlProcessActor
import akka.ainterface.datatype.{ErlAtom, ErlPid, ErlReference, ErlTerm}
import akka.ainterface.remote.ProcessActorProtocol._
import akka.pattern.ask
import akka.util.Timeout
import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag

class ProcessAdapter(val underlying: ActorRef) {
  implicit val timeout = Timeout(10.seconds)

  def await[A](x: Future[A]): A = Await.result(x, 10.seconds)

  def command[A: ClassTag](command: Any): A = await((underlying ? command).mapTo[A])

  def receive(): ErlTerm = command[ErlTerm](Receive)

  def self: ErlPid = command[ErlPid](SelfPid)

  def makeRef(): ErlReference = command[ErlReference](MakeRef)

  def register(name: ErlAtom, pid: ErlPid): Unit = command[Unit](Register(name, pid))

  def send(pid: ErlPid, message: ErlTerm): Unit = command[Unit](Send(pid, message))

  def send(name: ErlAtom, message: ErlTerm): Unit = command[Unit](RegSend(name, None, message))

  def send(name: ErlAtom, node: ErlAtom, message: ErlTerm): Unit = {
    command[Unit](RegSend(name, Some(node), message))
  }

  def link(to: ErlPid): Unit = command[Unit](Link(to))

  def unlink(to: ErlPid): Unit = command[Unit](Unlink(to))

  def monitor(to: ErlPid): ErlReference = command[ErlReference](Monitor(to))

  def demonitor(ref: ErlReference): Unit = command[Unit](Demonitor(ref))

  def exit(reason: ErlTerm): Unit = command[Unit](Exit(reason))

  def exit(pid: ErlPid, reason: ErlTerm): Unit = command[Unit](Exit2(pid, reason))
}

class ProcessActor extends ErlProcessActor with ActorLogging {
  override def preStart(): Unit = {
    log.info("{} started.", process.self)
  }

  var waiting: Option[ActorRef] = None
  var queue: Queue[ErlTerm] = Queue.empty

  override def receive: Receive = {
    case Receive => queue.dequeueOption match {
      case None => waiting = Some(sender())
      case Some((message, q)) =>
        sender() ! message
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
    case x: ErlTerm => waiting match {
      case Some(w) =>
        w ! x
        waiting = None
      case None => queue = queue.enqueue(x)
    }
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
