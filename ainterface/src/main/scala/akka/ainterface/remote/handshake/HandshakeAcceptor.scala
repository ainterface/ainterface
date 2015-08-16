package akka.ainterface.remote.handshake

import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Terminated}
import akka.ainterface.NodeName
import akka.ainterface.remote.handshake.HandshakeAcceptor._
import akka.ainterface.remote.handshake.HandshakeAcceptorProtocol._
import akka.ainterface.remote.transport.TcpConnectionProtocol
import akka.ainterface.remote.transport.TcpConnectionProtocol.ReadFailure
import akka.ainterface.remote.{Auth, DFlags, NodeConfig, RemoteHubProtocol}
import scala.concurrent.duration._

/**
 * An acceptor of handshake with another Erlang node.
 * @see [[http://www.erlang.org/doc/apps/erts/erl_dist_protocol.html#id92374]]
 */
private final class HandshakeAcceptor(connection: ActorRef,
                                      remoteHub: ActorRef,
                                      auth: Auth,
                                      localNodeName: NodeName,
                                      challengeGenerator: ChallengeGenerator) extends LoggingFSM[State, Data] {

  private[this] def sendChallenge(otherVersion: Int,
                                  thisFlags: DFlags,
                                  coordinator: ActorRef): FSM.State[HandshakeAcceptor.State, Data] = {
    val myChallenge = challengeGenerator.genChallenge()
    val thisNodeName = localNodeName.asString
    val challenge = Challenge(otherVersion, thisFlags.value, myChallenge, thisNodeName)
    write(connection, challenge)
    read[ChallengeReply](connection, self)
    val newData = stateData.copy(myChallenge = Some(myChallenge), coordinator = Some(coordinator))
    goto(ReceivingChallengeReply) using newData
  }

  startWith(ReceivingName, Data())
  context.watch(connection)

  override def preStart(): Unit = {
    read[Name](connection, self)
  }

  /**
   * 2) receive_name
   */
  when(ReceivingName, stateTimeout = Timeout) {
    case Event(ReadHandshake(message: Name), _) =>
      log.debug("Received name. {}", message)
      val Name(otherVersion, preOtherFlags, _name) = message
      val name = NodeName(_name)
      val preThisFlags = makeThisFlags(name)
      val (thisFlags, otherFlags) = adjustFlags(preThisFlags, DFlags(preOtherFlags))
      if (!checkDFlagXnc(otherFlags)) {
        write[Status](connection, NotAllowed)
        log.error("Required extended flags are not set. {}", message)
        stop(FSM.Failure(message))
      } else if (!isAllowed(name, None)) {
        write[Status](connection, NotAllowed)
        log.error("Connection attempt from disallowed node. {}", name)
        stop(FSM.Failure(message))
      } else {
        remoteHub ! RemoteHubProtocol.Accepted(name, self)
        val newData = Data(Some(thisFlags), Some(NodeConfig(name, otherVersion, otherFlags)))
        goto(CheckingPending) using newData
      }
  }

  /**
   * 3) send_status
   * 4) send_challenge
   */
  when(CheckingPending, stateTimeout = Timeout) {
    case Event(status: PendingStatus, Data(Some(thisFlags), Some(other), _, _)) =>
      log.debug("PendingStatus is {}.", status)
      val coordinator = sender()
      status match {
        case OkStatus =>
          write[Status](connection, Ok)
          sendChallenge(other.version, thisFlags, coordinator)
        case OkPending =>
          write[Status](connection, OkSimultaneous)
          sendChallenge(other.version, thisFlags, coordinator)
        case NokPending =>
          write[Status](connection, Nok)
          stop(FSM.Failure(NokPending))
        case UpPending =>
          write[Status](connection, Alive)
          read[AliveAnswer](connection, self)
          goto(ReceivingStatus)
      }
  }

  /**
   * 3B) recv_status
   */
  when(ReceivingStatus, stateTimeout = Timeout) {
    case Event(ReadHandshake(True), _) =>
      log.debug("The answer for alive is True.")
      goto(AwaitingNodeDown)
    case Event(ReadHandshake(False), _) =>
      log.debug("The answer for alive is False.")
      stop(FSM.Failure(False))
    case Event(NodeDown, _) =>
      log.debug("Pending node was down.")
      goto(ReceivingStatusAfterNodeDown)
  }

  /**
   * 3B) recv_status
   */
  when(ReceivingStatusAfterNodeDown, stateTimeout = Timeout) {
    case Event(ReadHandshake(True), Data(Some(thisFlags), Some(other), _, _)) =>
      log.debug("The answer for alive is True.")
      sendChallenge(other.version, thisFlags, sender())
    case Event(ReadHandshake(False), _) => stop(FSM.Failure(False))
  }

  /**
   * 4) send_challenge
   */
  when(AwaitingNodeDown, stateTimeout = Timeout) {
    case Event(NodeDown, Data(Some(thisFlags), Some(other), _, _)) =>
      log.debug("Pending node was down.")
      sendChallenge(other.version, thisFlags, sender())
  }

  /**
   * 5) recv_challenge_reply
   * 6) send_challenge_ack
   */
  when(ReceivingChallengeReply, stateTimeout = Timeout) {
    case Event(ReadHandshake(ChallengeReply(hisChallenge, hisSum)), Data(Some(thisFlags), Some(other), Some(myChallenge), Some(coordinator))) =>
      log.debug("Received challenge_reply.")
      val cookie = auth.getCookie(other.nodeName)
      val mySum = genDigest(myChallenge, cookie.value)
      if (mySum != hisSum) {
        log.error("Digest is NG.")
        stop(FSM.Failure("digest did not match."))
      } else {
        log.debug("Digest is OK and the handshake is Established.")
        val challengeAck = ChallengeAck(genDigest(hisChallenge, cookie.value))
        write(connection, challengeAck)
        coordinator ! HandshakeCoordinatorProtocol.Established(connection, other)
        stop()
      }
  }

  whenUnhandled {
    case Event(ReadFailure(err), _) =>
      log.error("Received an unexpected response. {}", err)
      stop(FSM.Failure(ReadFailure(err)))
    case Event(t @ Terminated(`connection`), _) =>
      log.error("Accepted connection was closed.")
      stop(FSM.Failure(t))
    case Event(StateTimeout, _) =>
      log.error("Timed out. {}, {}", stateName, stateData)
      stop(FSM.Failure(StateTimeout))
    case Event(unexpected, _) =>
      log.warning("Received an unexpected message. {}", unexpected)
      stop(FSM.Failure(unexpected))
  }

  onTermination {
    case StopEvent(FSM.Failure(_), _, _) =>
      connection ! TcpConnectionProtocol.Close
  }

  initialize()
}

private[remote] object HandshakeAcceptor {
  def props(connection: ActorRef,
            remoteHub: ActorRef,
            auth: Auth,
            localNodeName: NodeName): Props = {
    Props(
      classOf[HandshakeAcceptor],
      connection,
      remoteHub,
      auth,
      localNodeName,
      ChallengeGenerator
    )
  }

  private val Timeout = 10.seconds

  private[handshake] sealed abstract class State
  private[handshake] case object ReceivingName extends State
  private[handshake] case object CheckingPending extends State
  private[handshake] case object ReceivingStatus extends State
  private[handshake] case object ReceivingStatusAfterNodeDown extends State
  private[handshake] case object AwaitingNodeDown extends State
  private[handshake] case object ReceivingChallengeReply extends State

  private[handshake] final case class Data(thisFlags: Option[DFlags] = None,
                                           otherConfig: Option[NodeConfig] = None,
                                           myChallenge: Option[Int] = None,
                                           coordinator: Option[ActorRef] = None)
}

private[handshake] object HandshakeAcceptorProtocol {
  /**
   * The pending alive connection is disconnected.
   */
  case object NodeDown

  sealed abstract class PendingStatus
  case object OkStatus extends PendingStatus
  case object OkPending extends PendingStatus
  case object NokPending extends PendingStatus
  case object UpPending extends PendingStatus
}

