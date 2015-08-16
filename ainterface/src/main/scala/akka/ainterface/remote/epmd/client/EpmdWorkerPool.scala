package akka.ainterface.remote.epmd
package client

import akka.actor.{Actor, ActorRef, Props}
import akka.ainterface.remote.epmd.client.EpmdWorkerPoolProtocol.PortPlease
import akka.ainterface.remote.transport.TcpConnectionProtocol.ReadSuccess
import akka.ainterface.remote.transport.{TcpClientProtocol, TcpConnectionProtocol}
import akka.pattern.ask
import akka.util.Timeout
import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * A request worker pool.
 * This actor currently don't pool workers.
 */
private final class EpmdWorkerPool(host: String, tcpClient: ActorRef) extends Actor {
  import context.dispatcher
  implicit def timeout: Timeout = Timeout(EpmdWorkerPool.WorkerTimeout)

  private[this] val epmdAddress = new InetSocketAddress(host, EpmdPort)

  override def receive: Receive = {
    case PortPlease(replyTo, alive) =>
      val connect = TcpClientProtocol.Connect(epmdAddress, Some(EpmdWorkerPool.WorkerTimeout))
      val write = TcpConnectionProtocol.Write(EpmdReq(PortPlease2Req(alive)))
      val read = TcpConnectionProtocol.Read[Port2Resp](keeps = false)

      val portResult = for {
        TcpClientProtocol.Connected(connection) <- tcpClient ? connect
        _ = connection ! write
        (port, version) <- (connection ? read).mapTo[ReadSuccess].map {
          case ReadSuccess(result: Port2RespSuccess) => (result.port, result.version)
          case e => sys.error(s"Receives a failed response from EPMD. $e")
        }.andThen {
          case _ => connection ! TcpConnectionProtocol.Close
        }
      } yield (port, version)
      portResult.onComplete {
        case Success((port, version)) => replyTo ! EpmdClientProtocol.PortResult(port, version)
        case Failure(e) => replyTo ! akka.actor.Status.Failure(e)
      }
  }
}

private[client] object EpmdWorkerPool {
  def props(host: String, tcpClient: ActorRef): Props = {
    Props(classOf[EpmdWorkerPool], host, tcpClient)
  }

  private val WorkerTimeout = 10.seconds

  sealed abstract class State
  case object Idle extends State
  case object PortPleaseRequesting extends State

  case class Data(replyTo: Option[ActorRef])
}

private[client] object EpmdWorkerPoolProtocol {

  /**
   * PORT_PLEASE2_REQ.
   */
  final case class PortPlease(replyTo: ActorRef, alive: String)

  /**
   * Notifies that the worker complete its task.
   */
  final case class Complete(worker: ActorRef)
}
