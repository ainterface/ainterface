package ainterface

import akka.actor.{ActorSystem, Props}
import akka.ainterface.AinterfaceSystem
import akka.ainterface.datatype.{ErlPid, ErlAtom, ErlTuple}

object Sample {
  val system = ActorSystem("ainterface-sample")
  AinterfaceSystem.init(system)

  val echo = system.actorOf(Props[EchoActor])
  val exit = system.actorOf(Props[ExitActor])
  val link = system.actorOf(Props[LinkActor])
  val log = system.actorOf(Props[LogActor])
  val monitor = system.actorOf(Props[MonitorActor])

  def send(name: String, node: String, message: String): Unit = {
    echo ! ErlTuple(ErlTuple(ErlAtom(name), ErlAtom(node)), ErlAtom(message))
  }

  val nonExistent: ErlPid = {
    val p = process()
    val pid = p.self
    system.stop(p.underlying)
    pid
  }

  def process(): Process = new Process(system.actorOf(Props[Process.PActor]))
}
