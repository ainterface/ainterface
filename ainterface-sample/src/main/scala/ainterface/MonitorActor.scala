package ainterface

import akka.actor.ActorLogging
import akka.ainterface.ErlProcessActor
import akka.ainterface.datatype.interpolation.atom
import akka.ainterface.datatype.{ErlAtom, ErlPid, ErlTuple}

class MonitorActor extends SampleActor with ErlProcessActor with ActorLogging {
  override def preStart(): Unit = {
    log.debug("MonitorActor starts. pid = {}", process.self)
    process.register(atom"monitor", process.self)
  }

  override def receive: Receive = {
    case ErlTuple(ErlAtom("monitor"), pid: ErlPid) =>
      log.info("Monitors {}.", pid)
      process.monitor(pid)
  }
}
