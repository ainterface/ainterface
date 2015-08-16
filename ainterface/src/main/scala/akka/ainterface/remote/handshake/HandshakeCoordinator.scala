package akka.ainterface.remote.handshake

import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Terminated}
import akka.ainterface.NodeName
import akka.ainterface.remote.handshake.HandshakeCoordinator._
import akka.ainterface.remote.handshake.HandshakeCoordinatorProtocol.{Accepted, Established, Initiate, IsPending, StillPending}
import akka.ainterface.remote.transport.TcpConnectionProtocol
import akka.ainterface.remote.{NodeConfig, RemoteNodeProtocol}
import scala.Ordering.Implicits._
import scala.concurrent.duration._

/**
 * An actor to coordinate handshakes.
 */
private final class HandshakeCoordinator(localNodeName: NodeName,
                                         remoteNodeName: NodeName) extends LoggingFSM[State, Data] {
  startWith(Idle, Data())

  when(Idle, stateTimeout = Timeout) {
    case Event(Initiate(initiator, remoteNode), _) =>
      initiator ! HandshakeInitiatorProtocol.Start(self)
      goto(Initiating) using Data(remoteNode = Some(remoteNode), pending = Some(initiator))
    case Event(Accepted(acceptor, remoteNode), _) =>
      acceptor ! HandshakeAcceptorProtocol.OkStatus
      goto(Accepting) using Data(remoteNode = Some(remoteNode), pending = Some(acceptor))
  }

  when(Initiating, stateTimeout = Timeout) {
    case Event(StillPending(initiator), _) =>
      initiator ! IsPending(toBeContinued = true)
      stay()
    case Event(Established(connection, config), Data(Some(remoteNode), Some(initiator), _)) if sender() == initiator =>
      remoteNode ! RemoteNodeProtocol.Established(connection, config)
      goto(Up) using stateData.copy(pending = None, connection = Some(connection))
    case Event(Accepted(acceptor, _), _) if localNodeName > remoteNodeName =>
      acceptor ! HandshakeAcceptorProtocol.NokPending
      stay()
    case Event(Accepted(acceptor, _), Data(_, Some(initiator), _)) =>
      acceptor ! HandshakeAcceptorProtocol.OkPending
      goto(Accepting) using stateData.copy(pending = Some(acceptor))
  }

  when(Accepting, stateTimeout = Timeout) {
    case Event(Established(connection, config), Data(Some(remoteNode), Some(acceptor), _)) if sender() == acceptor =>
      remoteNode ! RemoteNodeProtocol.Established(connection, config)
      goto(Up) using stateData.copy(pending = None, connection = Some(connection))
  }

  when(Up) {
    case Event(Accepted(acceptor, _), _) =>
      acceptor ! HandshakeAcceptorProtocol.UpPending
      goto(UpPending) using stateData.copy(pending = Some(acceptor))
    case Event(Terminated(ref), Data(_, _, Some(connection))) if ref == connection => stop()
  }

  when(UpPending, stateTimeout = Timeout) {
    case Event(Terminated(ref), Data(_, Some(acceptor), _)) if ref == acceptor =>
      goto(Up) using stateData.copy(pending = None)
    case Event(Terminated(ref), Data(_, Some(acceptor), Some(connection))) if ref == connection =>
      acceptor ! HandshakeAcceptorProtocol.NodeDown
      goto(Accepting) using stateData.copy(connection = None)
  }

  whenUnhandled {
    case Event(StateTimeout, _) =>
      log.error("Failed the handshake with {} since it timed out.", remoteNodeName)
      stop(FSM.Failure(StateTimeout))
    case Event(Accepted(acceptor, _), _) =>
      // When another acceptor is still alive but a new connection is accepted.
      context.stop(acceptor)
      stay()
    case Event(Established(connection, _), _) =>
      // When it tries to stop the initiator since a new acceptor is accepted
      // but the handshake finishes ahead.
      connection ! TcpConnectionProtocol.Close
      stay()
    case Event(StillPending(initiator), _) =>
      initiator ! IsPending(toBeContinued = false)
      stay()
    case Event(Terminated(ref), Data(_, Some(pending), _)) if ref == pending =>
      log.error("Failed the handshake with {}.", remoteNodeName)
      stop(FSM.Failure("HandshakeFailure"))
    case x =>
      log.error("HandshakeCoordinator received an unexpected message. {}", x)
      stop(FSM.Failure(x))
  }

  onTransition {
    case _ -> Initiating => nextStateData.pending.foreach(context.watch)
    case Initiating -> Accepting =>
      stateData.pending.foreach { pending =>
        context.unwatch(pending)
        context.stop(pending)
      }
      nextStateData.pending.foreach(context.watch)
    case _ -> Accepting => nextStateData.pending.foreach(context.watch)
    case _ -> Up =>
      stateData.pending.foreach(context.unwatch)
      nextStateData.connection.foreach(context.watch)
    case _ -> UpPending =>
      nextStateData.pending.foreach(context.watch)
      nextStateData.connection.foreach(context.watch)
  }

  onTermination {
    case StopEvent(_, _, Data(_, pending, connection)) =>
      pending.foreach(context.stop)
      connection.foreach(_ ! TcpConnectionProtocol.Close)
  }

  initialize()
}

private[remote] object HandshakeCoordinator {
  def props(localNodeName: NodeName, remoteNodeName: NodeName): Props = {
    Props(classOf[HandshakeCoordinator], localNodeName, remoteNodeName)
  }

  private val Timeout = 10.seconds

  private[handshake] sealed abstract class State
  private[handshake] case object Idle extends State
  private[handshake] case object Initiating extends State
  private[handshake] case object Accepting extends State
  private[handshake] case object Up extends State
  private[handshake] case object UpPending extends State

  private[handshake] final case class Data(remoteNode: Option[ActorRef] = None,
                                           pending: Option[ActorRef] = None,
                                           connection: Option[ActorRef] = None)
}

private[remote] object HandshakeCoordinatorProtocol {

  /**
   * Initiates a handshake with the remote node.
   */
  final case class Initiate(handshakeInitiator: ActorRef, remoteNode: ActorRef)

  /**
   * Accepts a handshake with the remote node.
   */
  final case class Accepted(handshakeAcceptor: ActorRef, remoteNode: ActorRef)

  /**
   * Completes handshake.
   */
  private[handshake] final case class Established(connection: ActorRef, config: NodeConfig)

  /**
   * Requests for mediation when the initiator is informed that the peer has an alive connection.
   */
  private[handshake] final case class StillPending(initiator: ActorRef)

  /**
   * Response to [[StillPending]].
   */
  private[handshake] final case class IsPending(toBeContinued: Boolean)
}
