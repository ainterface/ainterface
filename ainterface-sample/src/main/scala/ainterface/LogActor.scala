package ainterface

import akka.actor.ActorLogging
import akka.ainterface.ErlProcessActor
import akka.ainterface.datatype.ErlAtom

class LogActor extends SampleActor with ErlProcessActor with ActorLogging {
  override def preStart(): Unit = {
    log.debug("LogActor starts. pid = {}", process.self)
    process.register(ErlAtom("log"), process.self)
  }

  override def receive: Receive = {
    case x => log.info("Received {}.", x)
  }
}
