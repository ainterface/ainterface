package ainterface

import akka.actor.ActorLogging
import akka.ainterface.ErlProcessActor
import akka.ainterface.datatype.{ErlAtom, ErlPid, ErlTuple}

class LinkActor extends SampleActor with ErlProcessActor with ActorLogging {
  override def preStart(): Unit = {
    log.debug("LinkActor starts. pid = {}", process.self)
    process.register(ErlAtom("link"), process.self)
  }

  override def receive: Receive = {
    case ErlTuple(ErlAtom("link"), pid: ErlPid) =>
      log.debug("Request for link from {}.", pid)
      process.link(pid)
      process.send(pid, ErlAtom("ok"))
    case ErlTuple(ErlAtom("unlink"), pid: ErlPid) =>
      log.debug("Request for unlink from {}.", pid)
      process.unlink(pid)
      process.send(pid, ErlAtom("ok"))
  }
}
