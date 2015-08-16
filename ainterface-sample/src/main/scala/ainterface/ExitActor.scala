package ainterface

import akka.actor.ActorLogging
import akka.ainterface.ErlProcessActor
import akka.ainterface.datatype.{ErlAtom, ErlPid, ErlTuple}

class ExitActor extends SampleActor with ErlProcessActor with ActorLogging {
  override def preStart(): Unit = {
    log.debug("ExitActor starts. pid = {}", process.self)
    process.register(ErlAtom("exit"), process.self)
  }

  override def receive: Receive = {
    case ErlAtom("exit") =>
      log.info("I'm dead.")
      context.stop(self)
    case ErlTuple(ErlAtom("kill_me_baby"), pid: ErlPid, reason) =>
      log.info("Kill {}.", pid)
      process.exit(pid, reason)
  }
}
