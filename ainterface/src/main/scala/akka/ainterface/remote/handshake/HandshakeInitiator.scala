package akka.ainterface.remote.handshake

import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Terminated}
import akka.ainterface.NodeName
import akka.ainterface.remote.epmd.client.EpmdClientProtocol
import akka.ainterface.remote.handshake.HandshakeCoordinatorProtocol.IsPending
import akka.ainterface.remote.handshake.HandshakeInitiator._
import akka.ainterface.remote.handshake.HandshakeInitiatorProtocol.Start
import akka.ainterface.remote.transport.TcpConnectionProtocol.ReadFailure
import akka.ainterface.remote.transport.{TcpClientProtocol, TcpConnectionProtocol}
import akka.ainterface.remote.{DFlags, ErlCookie, NodeConfig}
import java.net.InetSocketAddress
import scala.concurrent.duration._

/**
 * An initiator of handshake with another Erlang node.
 * @see [[http://www.erlang.org/doc/apps/erts/erl_dist_protocol.html#id92374]]
 */
private final class HandshakeInitiator(localNodeName: NodeName,
                                       remoteNodeName: NodeName,
                                       cookie: ErlCookie,
                                       challengeGenerator: ChallengeGenerator,
                                       tcpClient: ActorRef,
                                       epmdClient: ActorRef,
                                       isHidden: Boolean) extends LoggingFSM[State, Data] {

  startWith(Idle, Data())

  private[this] val preThisFlags = makeThisFlags(isHidden, remoteNodeName)

  when(Idle, stateTimeout = Timeout) {
    case Event(Start(coordinator), _) =>
      context.watch(coordinator)
      epmdClient ! EpmdClientProtocol.GetPort(self, remoteNodeName)
      goto(ResolvingPort) using Data(coordinator = Some(coordinator))
  }

  /**
   * 1) connect
   */
  when(ResolvingPort, stateTimeout = Timeout)  {
    case Event(EpmdClientProtocol.PortResult(port, version), _) =>
      val remoteAddress = new InetSocketAddress(remoteNodeName.host, port)
      tcpClient ! TcpClientProtocol.Connect(self, remoteAddress, Timeout)
      goto(TcpConnecting) using stateData.copy(otherVersion = Some(version))
  }

  /**
   * 2) send_name
   */
  when(TcpConnecting, stateTimeout = Timeout) {
    case Event(TcpClientProtocol.Connected(connection), Data(_, Some(version), _, _, _)) =>
      log.info("Established a connection to {}.", remoteNodeName)
      context.watch(connection)
      val name = Name(version, preThisFlags.value, localNodeName.asString)
      write(connection, name)
      read[Status](connection, self)
      goto(ReceivingStatus) using stateData.copy(connection = Some(connection))
  }

  /**
   * 3) recv_status
   */
  when(ReceivingStatus, stateTimeout = Timeout) {
    case Event(ReadHandshake(status: Status), Data(Some(coordinator), _, Some(connection), _, _)) =>
      status match {
        case Ok | OkSimultaneous =>
          read[Challenge](connection, self)
          goto(ReceivingChallenge)
        case Alive =>
          coordinator ! HandshakeCoordinatorProtocol.StillPending
          goto(CheckingPending)
        case Nok =>
          log.info("The other connection exists.")
          stop(FSM.Failure(Nok))
        case NotAllowed =>
          log.error("Handshake with {} is not allowed.", remoteNodeName)
          stop(FSM.Failure(NotAllowed))
      }
  }

  /**
   * 3B) send_status
   */
  when(CheckingPending, stateTimeout = Timeout) {
    case Event(IsPending(true), Data(_, _, Some(connection), _, _)) =>
      write[AliveAnswer](connection, True)
      read[Challenge](connection, self)
      goto(ReceivingChallenge)
    case Event(IsPending(false), Data(_, _, Some(connection), _, _)) =>
      write[AliveAnswer](connection, False)
      stop(FSM.Failure(IsPending(toBeContinued = false)))
  }

  /**
   * 4) recv_challenge
   * 5) send_challenge_reply
   */
  when(ReceivingChallenge, stateTimeout = Timeout) {
    case Event(ReadHandshake(challenge: Challenge), Data(_, Some(version), Some(connection), _, _)) if challenge.version == version && challenge.name == remoteNodeName.asString =>
      val (_, otherFlags) = adjustFlags(preThisFlags, DFlags(challenge.flags))
      val otherConfig = NodeConfig(
        nodeName = remoteNodeName,
        version = challenge.version,
        flags = otherFlags
      )
      if (!checkDFlagXnc(otherFlags)) {
        log.error("Required extended flags are not set. {}", challenge)
        stop(FSM.Failure(challenge))
      } else {
        val myChallenge = challengeGenerator.genChallenge()
        val digest = genDigest(challenge.challenge, cookie.value)
        val reply = ChallengeReply(myChallenge, digest)
        write(connection, reply)
        read[ChallengeAck](connection, self)
        val newData = stateData.copy(
          otherVersion = None,
          connection = Some(connection),
          remoteConfig = Some(otherConfig),
          myChallenge = Some(myChallenge)
        )
        goto(ReceivingChallengeAck) using newData
      }
  }

  /**
   * 6) recv_challenge_ack
   * 7) checks the digest
   */
  when(ReceivingChallengeAck, stateTimeout = Timeout) {
    case Event(ReadHandshake(ChallengeAck(digest)), Data(Some(coordinator), _, Some(connection), Some(config), Some(myChallenge))) =>
      if (digest == genDigest(myChallenge, cookie.value)) {
        coordinator ! HandshakeCoordinatorProtocol.Established(connection, config)
        stop()
      } else {
        log.error("ChallengeAck has an invalid digest.")
        stop(FSM.Failure("Invalid digest"))
      }
  }

  whenUnhandled {
    case Event(StateTimeout, _) =>
      log.error("HandshakeInitiator timed out.")
      stop(FSM.Failure(StateTimeout))
    case Event(ReadFailure(err), _) =>
      log.error("Received an unexpected response from {}. {}", remoteNodeName, err)
      stop(FSM.Failure(ReadFailure(err)))
    case Event(message @ Terminated(ref), Data(_, _, Some(connection), _, _)) if ref == connection =>
      log.error("Disconnected with {}.", remoteNodeName)
      stop(FSM.Failure(message))
    case Event(message @ Terminated(ref), Data(Some(coordinator), _, _, _, _)) if ref == coordinator =>
      log.error("The coordinator stopped.")
      stop(FSM.Failure(message))
    case Event(unexpected, _) =>
      log.warning("Received an unexpected message. {}", unexpected)
      stop(FSM.Failure(unexpected))
  }

  onTermination {
    case StopEvent(FSM.Failure(e), _, Data(_, _, Some(connection), _, _)) =>
      connection ! TcpConnectionProtocol.Close
    case StopEvent(FSM.Shutdown, _, Data(_, _, Some(connection), _, _)) =>
      connection ! TcpConnectionProtocol.Close
  }

  initialize()
}

private[remote] object HandshakeInitiator {
  def props(localNodeName: NodeName,
            remoteNodeName: NodeName,
            cookie: ErlCookie,
            tcpClient: ActorRef,
            epmdClient: ActorRef,
            isHidden: Boolean): Props = {
    Props(
      classOf[HandshakeInitiator],
      localNodeName,
      remoteNodeName,
      cookie,
      ChallengeGenerator,
      tcpClient,
      epmdClient,
      isHidden
    )
  }

  private val Timeout = 10.seconds

  private[handshake] sealed abstract class State
  private[handshake] case object Idle extends State
  private[handshake] case object ResolvingPort extends State
  private[handshake] case object TcpConnecting extends State
  private[handshake] case object ReceivingStatus extends State
  private[handshake] case object CheckingPending extends State
  private[handshake] case object ReceivingChallenge extends State
  private[handshake] case object ReceivingChallengeAck extends State

  private[handshake] final case class Data(coordinator: Option[ActorRef] = None,
                                           otherVersion: Option[Int] = None,
                                           connection: Option[ActorRef] = None,
                                           remoteConfig: Option[NodeConfig] = None,
                                           myChallenge: Option[Int] = None)
}

private[handshake] object HandshakeInitiatorProtocol {
  final case class Start(coordinator: ActorRef)
}
