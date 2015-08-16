package ainterface

import akka.actor.{Actor, ActorLogging}

trait SampleActor extends Actor with ActorLogging {
  override def unhandled(message: Any): Unit = log.info("Received {}.", message)

  override def postStop(): Unit = {
    log.info("{} is stopping.", getClass)
    super.postStop()
  }
}
