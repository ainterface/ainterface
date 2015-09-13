package akka.ainterface.remote

import akka.actor.{ActorSystem, Props}
import akka.ainterface.AinterfaceSystem
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterAll, WordSpec}

abstract class BaseSpec
  extends WordSpec
  with GeneratorDrivenPropertyChecks
  with BeforeAndAfterAll {

  protected[this] val system = ActorSystem("test-system")
  AinterfaceSystem.init(system)

  protected[this] val process: ProcessAdapter = new ProcessAdapter(system.actorOf(Props[ProcessActor]))

  override protected[this] def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
    system.awaitTermination()
  }
}
