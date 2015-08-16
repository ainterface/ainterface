package akka.ainterface.remote.epmd
package publisher

import akka.actor.{ActorRef, FSM, LoggingFSM, Props}
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.epmd.publisher.EpmdPublisher._
import akka.ainterface.remote.epmd.publisher.EpmdPublisherProtocol.Publish
import akka.ainterface.remote.transport.{TcpClientProtocol, TcpConnectionProtocol}
import java.net.{InetAddress, InetSocketAddress}
import scala.concurrent.duration._

/**
 * An actor that registers the local node in EPMD on the localhost.
 */
private final class EpmdPublisher(localNode: LocalNode,
                                  tcpClient: ActorRef) extends LoggingFSM[State, Data] {
  private[this] val EpmdAddress = new InetSocketAddress(InetAddress.getLoopbackAddress, EpmdPort)

  startWith(Idle, Data())

  when(Idle, stateTimeout = Timeout) {
    case Event(Publish(acceptorPort), _) =>
      log.debug("Publish the port {}.", acceptorPort)
      tcpClient ! TcpClientProtocol.Connect(self, EpmdAddress, Timeout)
      goto(Establishing) using Data(Some(acceptorPort))
  }

  when(Establishing, stateTimeout = Timeout) {
    case Event(TcpClientProtocol.Connected(connection), Data(Some(acceptorPort), _)) =>
      log.debug("Established the connection with EPMD.")
      val alive = localNode.nodeName.alive
      val request = EpmdReq(Alive2Req(acceptorPort, alive))
      connection ! TcpConnectionProtocol.Write(request)
      connection ! TcpConnectionProtocol.Read[Alive2Resp](self, keeps = false)
      goto(ReceivingResult) using stateData.copy(connection = Some(connection))
    case Event(Publish(acceptorPort), _) => stay() using Data(Some(acceptorPort))
  }

  when(ReceivingResult, stateTimeout = Timeout) {
    case Event(TcpConnectionProtocol.ReadSuccess(Alive2RespSuccess(creation)), Data(_, Some(connection))) if sender() == connection =>
      log.debug("Success in registering. creation = {}", creation)
      localNode.updateCreation((creation & 0x03).toByte)
      connection ! TcpConnectionProtocol.SetTimeout(None)
      goto(Registered)
    case Event(TcpConnectionProtocol.ReadSuccess(e), _) =>
      log.error("Failed registering in EPMD. {}", e)
      stop(FSM.Failure(e))
    case Event(e: TcpConnectionProtocol.ReadFailure, _) =>
      log.error("Failed registering in EPMD. {}", e)
      stop(FSM.Failure(e))
  }

  when(Registered)(FSM.NullFunction)

  whenUnhandled {
    case Event(StateTimeout, _) =>
      log.error("EpmdPublisher timed out. state = {}, data = {}", stateName, stateData)
      stop(FSM.Failure(StateTimeout))
    case Event(Publish(acceptorPort), Data(Some(port), _)) if acceptorPort == port => stay()
    case Event(message: Publish, Data(_, Some(connection))) =>
      self ! message
      connection ! TcpConnectionProtocol.Close
      goto(Idle) using Data()
    case Event(event, data) =>
      log.warning("EpmdPublisher handled an unexpected message. error = {} data = {}", event, data)
      stay()
  }

  initialize()
}

private[publisher] object EpmdPublisher {
  def props(localNode: LocalNode, tcpClient: ActorRef): Props = {
    Props(classOf[EpmdPublisher], localNode, tcpClient)
  }

  private val Timeout = 10.seconds

  sealed abstract class State
  case object Idle extends State
  case object Establishing extends State
  case object ReceivingResult extends State
  case object Registered extends State

  final case class Data(acceptorPort: Option[Int] = None, connection: Option[ActorRef] = None)
}

private[remote] object EpmdPublisherProtocol {

  /**
   * Registers the local node in EPMD.
   */
  final case class Publish(acceptorPort: Int)
}
