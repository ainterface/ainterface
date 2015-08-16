package akka.ainterface.remote

import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Terminated}
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.RemoteNode._
import akka.ainterface.remote.RemoteNodeProtocol.{Accepted, Established, Initiate, Send, Tick}
import akka.ainterface.remote.RemoteNodeStatusEventBus.NodeDown
import akka.ainterface.remote.handshake.HandshakeCoordinatorProtocol
import akka.ainterface.remote.transport.TcpConnectionProtocol
import akka.ainterface.remote.transport.TcpConnectionProtocol.{ReadFailure, ReadSuccess}
import akka.ainterface.{ControlMessage, NodeName}
import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scodec.Codec

/**
 * An actor to represent a remote node.
 */
private final class RemoteNode(localNode: LocalNode,
                               remoteNodeName: NodeName,
                               coordinator: ActorRef,
                               nodeStatusEventBus: RemoteNodeStatusEventBus)
  extends LoggingFSM[State, Data] {

  implicit private[this] val codec: Codec[RemoteMessage] = {
    RemoteMessage.codec(localNode.nodeName, remoteNodeName)
  }

  private[this] def send(connection: ActorRef, message: ControlMessage): Unit = {
    log.debug("Send {} to {}.", message, remoteNodeName)
    connection ! TcpConnectionProtocol.Write(RemoteMessage(Some(message)))
  }
  private[this] def sendTick(data: Peer): FSM.State[RemoteNode.State, Data] = {
    val Peer(connection, config, tickStatus, socketStatus) = data
    val TickStatus(read, write, t0, ticked) = tickStatus
    val t = t0 + 1
    val t1 = t % 4
    socketStatus match {
      case SocketStatus(`read`, _) if ticked == t =>
        log.error("The remote node {} has not responded ticks.", remoteNodeName)
        stop(FSM.Failure("The remote node has not responded."))
      case SocketStatus(`read`, w) if config.flags.isHidden =>
        connection ! TcpConnectionProtocol.Write(RemoteMessage(None))
        stay() using data.copy(tickStatus = tickStatus.copy(write = w + 1, tick = t1))
      case SocketStatus(`read`, `write`) =>
        connection ! TcpConnectionProtocol.Write(RemoteMessage(None))
        stay() using data.copy(tickStatus = tickStatus.copy(write = write + 1, tick = t1))
      case SocketStatus(r, `write`) =>
        connection ! TcpConnectionProtocol.Write(RemoteMessage(None))
        val newTick = TickStatus(read = r, write = write + 1, tick = t1, ticked = t)
        stay() using data.copy(tickStatus = newTick)
      case SocketStatus(`read`, w) =>
        stay() using data.copy(tickStatus = tickStatus.copy(write = w, tick = t1))
      case SocketStatus(r, w) =>
        stay() using data.copy(tickStatus = TickStatus(read = r, write = w, tick = t1, ticked = t))
    }
  }

  startWith(Pending, Buffer())

  override def preStart(): Unit = context.watch(coordinator)

  when(Pending, stateTimeout = Timeout) {
    case Event(Initiate(initiator), _) =>
      coordinator ! HandshakeCoordinatorProtocol.Initiate(initiator, self)
      stay()
    case Event(Accepted(acceptor), _) =>
      coordinator ! HandshakeCoordinatorProtocol.Accepted(acceptor, self)
      stay()
    case Event(Established(connection, config), Buffer(buffer)) =>
      context.watch(connection)
      connection ! TcpConnectionProtocol.Read[RemoteMessage](self, keeps = true)
      buffer.foreach { message =>
        send(connection, message)
      }
      goto(Up) using Peer(connection, config, TickStatus(), SocketStatus())
    case Event(Send(message), Buffer(buffer)) =>
      stay() using Buffer(buffer.enqueue(message))
    case Event(Tick, _) => stay()
  }

  when(Up) {
    case Event(Send(message), data @ Peer(connection, _, _, socketStatus)) =>
      send(connection, message)
      stay() using data.copy(socketStatus = socketStatus.doWrite())
    case Event(ReadSuccess(RemoteMessage(msg)), data @ Peer(connection, _, _, socketStatus)) =>
      log.debug("Received {} from {}.", msg, remoteNodeName)
      msg.foreach(localNode.sendEvent)
      stay() using data.copy(socketStatus = socketStatus.doRead())
    case Event(ReadFailure(error), _) =>
      log.error("RemoteNode received an unexpected message. {}", error)
      stop(FSM.Failure(error))
    case Event(Tick, data: Peer) => sendTick(data)
    case Event(Accepted(acceptor), _) =>
      coordinator ! HandshakeCoordinatorProtocol.Accepted(acceptor, self)
      stay()
    case Event(Terminated(ref), Peer(connection, _, _, _)) if ref == connection =>
      log.debug("The connection with {} closed.", remoteNodeName)
      goto(Pending) using Buffer()
  }

  whenUnhandled {
    case Event(StateTimeout, _) =>
      log.debug("RemoteNode for {} timed out.", remoteNodeName)
      stop(FSM.Failure(StateTimeout))
    case Event(Terminated(`coordinator`), _) =>
      log.debug("Cannot continue linking with {}.", remoteNodeName)
      stop()
  }

  onTermination {
    case StopEvent(_, _, _) => nodeStatusEventBus.publish(NodeDown(remoteNodeName))
  }

  initialize()
}

private[remote] object RemoteNode {
  def props(localNode: LocalNode,
            remoteNodeName: NodeName,
            coordinator: ActorRef,
            nodeStatusEventBus: RemoteNodeStatusEventBus): Props = {
    Props(
      classOf[RemoteNode],
      localNode,
      remoteNodeName,
      coordinator,
      nodeStatusEventBus
    )
  }

  private val Timeout = 10.seconds

  sealed abstract class State
  case object Pending extends State
  case object Up extends State

  final case class TickStatus(read: Int, write: Int, tick: Int, ticked: Int)
  object TickStatus {
    def apply(): TickStatus = TickStatus(0, 0, 1, 1)
  }
  final case class SocketStatus(read: Int, write: Int) {
    def doRead(): SocketStatus = SocketStatus(read + 1, write)
    def doWrite(): SocketStatus = SocketStatus(read, write + 1)
  }
  object SocketStatus {
    def apply(): SocketStatus = SocketStatus(0, 0)
  }

  sealed abstract class Data
  final case class Buffer(buffer: Queue[ControlMessage] = Queue.empty) extends Data
  final case class Peer(connection: ActorRef,
                        config: NodeConfig,
                        tickStatus: TickStatus = TickStatus(),
                        socketStatus: SocketStatus = SocketStatus()) extends Data
}

private[remote] object RemoteNodeProtocol {

  /**
   * Initiates a handshake with the remote node.
   */
  final case class Initiate(handshakeInitiator: ActorRef)

  /**
   * Accepts a handshake with the remote node.
   */
  final case class Accepted(handshakeAcceptor: ActorRef)

  /**
   * Completes handshake.
   */
  final case class Established(connection: ActorRef, config: NodeConfig)

  /**
   * What the ticker kicks.
   */
  case object Tick

  /**
   * Sends a control message.
   */
  final case class Send(event: ControlMessage)
}
