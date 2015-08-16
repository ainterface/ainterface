package akka.ainterface.remote

import akka.actor.{ActorRef, FSM, LoggingFSM, Props}
import akka.ainterface.NodeName
import akka.ainterface.remote.Acceptor.{Binding, Idle, Listening, State}
import akka.ainterface.remote.AcceptorProtocol.Start
import akka.ainterface.remote.epmd.publisher.EpmdPublisherProtocol.Publish
import akka.ainterface.remote.handshake.HandshakeAcceptor
import akka.ainterface.remote.transport.TcpClientProtocol
import akka.ainterface.util.actor.DynamicSupervisorProtocol
import akka.ainterface.util.actor.DynamicSupervisorProtocol.ChildRef
import akka.io.Tcp.{Bind, Bound, CommandFailed, Connected, Register}
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.util.Timeout
import java.net.InetSocketAddress
import scala.concurrent.duration._

/**
 * An acceptor to handle incoming handshakes.
 */
final private class Acceptor(localNodeName: NodeName,
                             auth: Auth,
                             epmdPublisher: ActorRef,
                             tcpClient: ActorRef,
                             handshakeAcceptorSupervisor: ActorRef,
                             remoteHub: ActorRef) extends LoggingFSM[State, Unit] {

  implicit val timeout = Timeout(10.seconds)
  import context.dispatcher

  startWith(Idle, ())

  when(Idle) {
    case Event(Start, _) =>
      val address = new InetSocketAddress(localNodeName.host, 0)
      IO(Tcp)(context.system) ! Bind(self, address)
      goto(Binding)
  }

  when(Binding) {
    case Event(Bound(localAddress), _) =>
      log.debug("The socket was bound to {}.", localAddress)
      epmdPublisher ! Publish(localAddress.getPort)
      goto(Listening)
  }

  when(Listening) {
    case Event(Connected(remote, _), _) =>
      log.debug("Accepted the connection with {}.", remote)
      val akkaConnection = sender()
      (tcpClient ? TcpClientProtocol.Accept(akkaConnection, 10.seconds)).mapTo[ChildRef].onSuccess {
        case ChildRef(connection, _) =>
          akkaConnection ! Register(connection)
          val props = HandshakeAcceptor.props(connection, remoteHub, auth, localNodeName)
          val command = DynamicSupervisorProtocol.StartChild(props)
          handshakeAcceptorSupervisor ! command
      }
      stay()
    case Event(ChildRef(handshakeAcceptor, _), _) =>
      log.debug("HandshakeAcceptor is created.")
      stay()
  }

  whenUnhandled {
    case Event(m: CommandFailed, _) =>
      log.error("An error occurred. {}", m)
      stop(FSM.Failure(m))
  }

  initialize()
}

private[remote] object Acceptor {
  def props(localNodeName: NodeName,
            auth: Auth,
            epmdPublisher: ActorRef,
            tcpClient: ActorRef,
            handshakeAcceptorSupervisor: ActorRef,
            remoteHub: ActorRef): Props = {
    Props(
      classOf[Acceptor],
      localNodeName,
      auth,
      epmdPublisher,
      tcpClient,
      handshakeAcceptorSupervisor,
      remoteHub
    )
  }

  sealed abstract class State
  case object Idle extends State
  case object Binding extends State
  case object Listening extends State
}

private[remote] object AcceptorProtocol {
  case object Start
}
