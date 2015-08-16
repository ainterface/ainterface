package akka.ainterface.remote.epmd.client

import akka.ainterface.remote.epmd.EpmdReq
import akka.ainterface.remote.epmd.client.EpmdWorkerPoolProtocol.PortPlease
import akka.ainterface.remote.transport.{TcpClientProtocol, TcpConnectionProtocol}
import akka.ainterface.test.ActorSpec
import akka.testkit.TestProbe
import java.net.InetSocketAddress
import scala.concurrent.duration._

class EpmdWorkerPoolSpec extends ActorSpec {
  private[this] val host = "ainterface.akka"
  private[this] val epmdAddress = new InetSocketAddress(host, 4369)
  private[this] val Timeout = 10.seconds
  private[this] val version = 5

  "EpmdWorkerPool" should {
    "send PORT_PLEASE2" when {
      "it receives PortPlease" in {
        forAll { (port: Int, alive: String) =>
          val tcpClient = TestProbe()
          val pool = system.actorOf(EpmdWorkerPool.props(host, tcpClient.ref))

          val replyTo = TestProbe()
          pool ! PortPlease(replyTo.ref, alive)

          val connect = TcpClientProtocol.Connect(epmdAddress, Some(Timeout))
          tcpClient.expectMsg(connect)
          val connection = TestProbe()
          tcpClient.reply(TcpClientProtocol.Connected(connection.ref))

          val write = TcpConnectionProtocol.Write(EpmdReq(PortPlease2Req(alive)))
          connection.expectMsg(write)
          val read = TcpConnectionProtocol.Read[Port2Resp](keeps = false)
          connection.expectMsg(read)

          connection.reply(TcpConnectionProtocol.ReadSuccess(Port2RespSuccess(port, alive)))
          connection.expectMsg(TcpConnectionProtocol.Close)

          replyTo.expectMsg(EpmdClientProtocol.PortResult(port, version))
          ()
        }
      }
    }

    "send a failure message" when {
      "EPMD returns a failed response" in {
        forAll { (port: Int, alive: String) =>
          val tcpClient = TestProbe()
          val pool = system.actorOf(EpmdWorkerPool.props(host, tcpClient.ref))

          val replyTo = TestProbe()
          pool ! PortPlease(replyTo.ref, alive)

          val connect = TcpClientProtocol.Connect(epmdAddress, Some(Timeout))
          tcpClient.expectMsg(connect)
          val connection = TestProbe()
          tcpClient.reply(TcpClientProtocol.Connected(connection.ref))

          val write = TcpConnectionProtocol.Write(EpmdReq(PortPlease2Req(alive)))
          connection.expectMsg(write)
          val read = TcpConnectionProtocol.Read[Port2Resp](keeps = false)
          connection.expectMsg(read)

          val failure = Port2RespFailure(1)
          connection.reply(failure)
          connection.expectMsg(TcpConnectionProtocol.Close)

          replyTo.expectMsgPF() {
            case akka.actor.Status.Failure(e) => ()
          }
        }
      }
    }
  }
}
