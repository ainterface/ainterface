package ainterface

import ainterface.ProcessActorProtocol._
import akka.actor.ActorRef
import akka.ainterface.datatype.{ErlAtom, ErlPid, ErlReference, ErlTerm}
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag

class Process(val underlying: ActorRef) {
  implicit val timeout = Timeout(1.seconds)

  def await[A](x: Future[A]): A = Await.result(x, 10.seconds)

  def command[A: ClassTag](command: Any): A = await((underlying ? command).mapTo[A])

  def receive(): Option[ErlTerm] = command[Option[ErlTerm]](Receive)

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
