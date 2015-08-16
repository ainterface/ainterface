package ainterface

import akka.actor.ActorLogging
import akka.ainterface.ErlProcessActor
import akka.ainterface.datatype.{ErlAtom, ErlPid, ErlTuple}

class EchoActor extends ErlProcessActor with ActorLogging {
  override def preStart(): Unit = {
    log.debug("EchoActor starts. pid = {}", process.self)
    process.register(ErlAtom("echo"), process.self)
  }

  override def receive: Receive = {
    case ErlTuple(from: ErlPid, message) =>
      log.info("Received {} from {}.", message, from)
      process.send(from, ErlTuple(process.self, message))
    case ErlTuple(from: ErlAtom, message) =>
      log.info("Received {} from {}.", message, from)
      process.send(from, message)
    case ErlTuple(ErlTuple(name: ErlAtom, node: ErlAtom), message) =>
      log.info("Received {} from {} in {}.", message, name, node)
      process.send(name, node, message)
  }

  override def postStop(): Unit = {
    log.info("EchoActor is stopping.")
    super.postStop()
  }
}
