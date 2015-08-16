package akka.ainterface.remote.transport

import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbFiniteDuration
import akka.ainterface.util.actor.DynamicSupervisorProtocol
import akka.testkit.TestProbe
import java.net.InetSocketAddress
import scala.concurrent.duration.FiniteDuration

class TcpClientSpec extends ActorSpec {
  private[this] val remote = new InetSocketAddress("localhost", 0)

  "TcpClient" should {
    "start a TCP connection" when {
      "receiving Connect" in {
        forAll { (timeout: Option[FiniteDuration], bufferSize: Int) =>
          val connectorSupervisor = TestProbe()
          val connectionSupervisor = TestProbe()
          val props = TcpClient.props(connectorSupervisor.ref, connectionSupervisor.ref)
          val client = system.actorOf(props)

          val listener = TestProbe()
          client ! TcpClientProtocol.Connect(Some(listener.ref), remote, timeout, bufferSize)
          val connectorProps = TcpConnector.props(connectionSupervisor.ref)
          val startChildCommand = DynamicSupervisorProtocol.StartChild(connectorProps)
          connectorSupervisor.expectMsg(startChildCommand)

          val connector = TestProbe()
          connectorSupervisor.reply(DynamicSupervisorProtocol.ChildRef(connector.ref, None))
          connector.expectMsg(TcpConnectorProtocol.Start(listener.ref, remote, timeout, bufferSize))
          ()
        }
      }

      "receiving Accept" in {
        forAll { (timeout: Option[FiniteDuration], bufferSize: Int) =>
          val connectionSupervisor = TestProbe()
          val props = TcpClient.props(TestProbe().ref, connectionSupervisor.ref)
          val client = system.actorOf(props)

          val replyTo = TestProbe().ref
          val connection = TestProbe().ref
          client ! TcpClientProtocol.Accept(Some(replyTo), connection, timeout, bufferSize)
          val connectionProps = TcpConnection.props(connection, timeout, bufferSize)
          val startChildCommand = DynamicSupervisorProtocol.StartChild(replyTo, connectionProps)
          connectionSupervisor.expectMsg(startChildCommand)
          ()
        }
      }
    }

    "start a TCP connection and set sender as the listener" when {
      "receiving Connect and no listener is specified" in {
        forAll { (timeout: Option[FiniteDuration], bufferSize: Int) =>
          val connectorSupervisor = TestProbe()
          val connectionSupervisor = TestProbe()
          val props = TcpClient.props(connectorSupervisor.ref, connectionSupervisor.ref)
          val client = system.actorOf(props)

          client ! TcpClientProtocol.Connect(None, remote, timeout, bufferSize)
          val connectorProps = TcpConnector.props(connectionSupervisor.ref)
          val startChildCommand = DynamicSupervisorProtocol.StartChild(connectorProps)
          connectorSupervisor.expectMsg(startChildCommand)

          val connector = TestProbe()
          connectorSupervisor.reply(DynamicSupervisorProtocol.ChildRef(connector.ref, None))
          connector.expectMsg(TcpConnectorProtocol.Start(testActor, remote, timeout, bufferSize))
          ()
        }
      }

      "receiving Accept and no replyTo is specified" in {
        forAll { (timeout: Option[FiniteDuration], bufferSize: Int) =>
          val connectionSupervisor = TestProbe()
          val props = TcpClient.props(TestProbe().ref, connectionSupervisor.ref)
          val client = system.actorOf(props)

          val connection = TestProbe().ref
          client ! TcpClientProtocol.Accept(None, connection, timeout, bufferSize)
          val connectionProps = TcpConnection.props(connection, timeout, bufferSize)
          val startChildCommand = DynamicSupervisorProtocol.StartChild(testActor, connectionProps)
          connectionSupervisor.expectMsg(startChildCommand)
          ()
        }
      }
    }
  }
}
