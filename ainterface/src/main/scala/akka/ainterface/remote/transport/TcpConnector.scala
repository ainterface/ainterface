package akka.ainterface.remote.transport

import akka.actor.{ActorRef, FSM, LoggingFSM, Props}
import akka.ainterface.remote.transport.TcpConnector.{Connecting, Data, Idle, Starting, State, Timeout}
import akka.ainterface.util.actor.DynamicSupervisorProtocol
import akka.io.Tcp.{Connect, Connected, Register}
import akka.io.{IO, Tcp}
import java.net.InetSocketAddress
import scala.concurrent.duration._

/**
 * Starts a TCP handshake and creates a connection.
 * This returns a props of [[TcpConnection]].
 */
private final class TcpConnector(connectionSupervisor: ActorRef) extends LoggingFSM[State, Data] {

  startWith(Idle, Data())

  when(Idle, stateTimeout = Timeout) {
    case Event(TcpConnectorProtocol.Start(listener, remote, timeout, maxBufferSize), _) =>
      IO(Tcp)(context.system) ! Connect(remote)
      goto(Connecting) using Data(listener, remote, timeout, maxBufferSize)
  }

  when(Connecting, stateTimeout = Timeout) {
    case Event(Connected(r, _), Data(Some(listener), Some(remote), timeout, Some(maxBufferSize), _)) if r == remote =>
      val connection = sender()
      val props = TcpConnection.props(connection, timeout, maxBufferSize)
      connectionSupervisor ! DynamicSupervisorProtocol.StartChild(self, props)
      goto(Starting) using stateData.copy(akkaConnection = Some(connection))
  }

  when(Starting, stateTimeout = Timeout) {
    case Event(DynamicSupervisorProtocol.ChildRef(connection, _), Data(Some(listener), _, _, _, Some(akkaConnection))) =>
      akkaConnection ! Register(connection)
      listener ! TcpClientProtocol.Connected(connection)
      stop()
  }

  whenUnhandled {
    case Event(StateTimeout, _) =>
      log.error("TcpClient times out. state = {} data = {}", stateName, stateData)
      stop(FSM.Failure(StateTimeout))
    case Event(unknown, _) =>
      log.error("Received an unknown event. {}", unknown)
      stop(FSM.Failure(unknown))
  }

  initialize()
}

private[transport] object TcpConnector {
  def props(connectionSupervisor: ActorRef): Props = {
    Props(classOf[TcpConnector], connectionSupervisor)
  }

  private val Timeout = 10.seconds

  sealed abstract class State
  case object Idle extends State
  case object Connecting extends State
  case object Starting extends State

  final case class Data(listener: Option[ActorRef] = None,
                        remote: Option[InetSocketAddress] = None,
                        timeout: Option[FiniteDuration] = None,
                        maxBufferSize: Option[Int] = None,
                        akkaConnection: Option[ActorRef] = None)
  object Data {
    def apply(listener: ActorRef,
              remote: InetSocketAddress,
              timeout: Option[FiniteDuration],
              maxBufferSize: Int): Data = {
      Data(Some(listener), Some(remote), timeout, Some(maxBufferSize))
    }
  }
}

private[transport] object TcpConnectorProtocol {
  final case class Start(listener: ActorRef,
                         remoteAddress: InetSocketAddress,
                         timeout: Option[FiniteDuration],
                         maxBufferSize: Int)
}
