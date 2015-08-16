package akka.ainterface.remote.transport

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.ainterface.util.actor.DynamicSupervisorProtocol
import akka.pattern.ask
import akka.util.Timeout
import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * A TCP client generates an outgoing TCP connection.
 */
private final class TcpClient(connectorSupervisor: ActorRef,
                              connectionSupervisor: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher
  implicit private[this] val StartChildTimeout = Timeout(5.seconds)

  override def receive: Receive = {
    case TcpClientProtocol.Connect(listenerOption, remote, timeout, maxBufferSize) =>
      val listener = listenerOption.getOrElse(sender())
      log.debug(
        "Connect with {}. listener = {}, timeout = {}, maxBufferSize = {}",
        remote,listener, timeout, maxBufferSize
      )
      val command = DynamicSupervisorProtocol.StartChild(TcpConnector.props(connectionSupervisor))
      (connectorSupervisor ? command).mapTo[DynamicSupervisorProtocol.ChildRef].onComplete {
        case Success(DynamicSupervisorProtocol.ChildRef(child, _)) =>
          child ! TcpConnectorProtocol.Start(listener, remote, timeout, maxBufferSize)
        case Failure(e) => listener ! akka.actor.Status.Failure(e)
      }
    case TcpClientProtocol.Accept(replyToOption, akkaConnection, timeout, maxBufferSize) =>
      val replyTo = replyToOption.getOrElse(sender())
      val props = TcpConnection.props(akkaConnection, timeout, maxBufferSize)
      val command = DynamicSupervisorProtocol.StartChild(replyTo, props)
      connectionSupervisor ! command
  }
}

private[transport] object TcpClient {
  def props(connectorSupervisor: ActorRef, connectionSupervisor: ActorRef): Props = {
    Props(classOf[TcpClient], connectorSupervisor, connectionSupervisor)
  }
}

private[remote] object TcpClientProtocol {
  private[this] val DefaultMaxBufferSize = Int.MaxValue

  /**
   * Connects with the specified address.
   */
  final case class Connect(listener: Option[ActorRef],
                           remoteAddress: InetSocketAddress,
                           timeout: Option[FiniteDuration],
                           maxBufferSize: Int)

  object Connect {
    def apply(remote: InetSocketAddress, timeout: Option[FiniteDuration]): Connect = {
      Connect(None, remote, timeout, DefaultMaxBufferSize)
    }

    def apply(listener: ActorRef, remote: InetSocketAddress, timeout: FiniteDuration): Connect = {
      Connect(Some(listener), remote, Some(timeout), DefaultMaxBufferSize)
    }
  }

  /**
   * Gets [[TcpConnection]] underlying the akka-io connection.
   */
  final case class Accept(replyTo: Option[ActorRef],
                          connection: ActorRef,
                          timeout: Option[FiniteDuration],
                          maxBufferSize: Int)

  object Accept {
    def apply(akkaConnection: ActorRef, timeout: FiniteDuration): Accept = {
      Accept(None, akkaConnection, Some(timeout), DefaultMaxBufferSize)
    }
  }

  /**
   * The result of [[Connect]].
   */
  final case class Connected(connection: ActorRef)
}
