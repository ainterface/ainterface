package akka.ainterface.test

import akka.actor.{ActorRef, ActorSystem}
import akka.io.Tcp.{Aborted, Closed, ConfirmedClosed, ConnectionClosed, ErrorClosed, PeerClosed}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import java.util.concurrent.TimeUnit
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, WordSpecLike}
import scala.concurrent.duration._

class ActorSpec(_system: ActorSystem = ActorSystem("test"))
  extends TestKit(_system)
  with ImplicitSender
  with WordSpecLike
  with BeforeAndAfterEach
  with BeforeAndAfterAll
  with GeneratorDrivenPropertyChecks {

  implicit override val generatorDrivenConfig = PropertyCheckConfig(
    minSuccessful = 10,
    minSize = 10,
    maxSize = 20
  )

  protected[this] val shortDuration: FiniteDuration = 10.millis

  protected[this] def nullRef: ActorRef = TestProbe().ref

  protected[this] val connectionClosed: Seq[ConnectionClosed] = {
    Seq(Closed, Aborted, ConfirmedClosed, PeerClosed, ErrorClosed("error"))
  }

  override protected[this] def beforeEach(): Unit = {
    super.beforeEach()
    receiveWhile(max = FiniteDuration(0, TimeUnit.SECONDS)) {
      case _ => ()
    }
  }

  override protected[this] def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }
}
