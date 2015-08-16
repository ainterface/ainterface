package akka.ainterface.remote

import akka.actor.Props
import akka.ainterface.test.ActorSpec
import akka.testkit.TestProbe
import scala.concurrent.duration._

class TickerSpec extends ActorSpec {
  "Ticker" should {
    "send Tick event" when {
      "it times out" in {
        val hub = TestProbe()
        system.actorOf(Props(classOf[Ticker], hub.ref, 400.millis))
        hub.expectMsgAllOf(1.second, RemoteHubProtocol.Tick, RemoteHubProtocol.Tick)
      }
    }
  }
}
