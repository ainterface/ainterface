package akka.ainterface.remote

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.RemoteHubProtocol.{Accepted, Tick}
import akka.ainterface.remote.handshake.HandshakeInitiator
import akka.ainterface.util.actor.DynamicSupervisorProtocol.ChildRef
import akka.ainterface.util.actor.{ActorPathUtil, DynamicSupervisorProtocol}
import akka.ainterface.util.collection.BiMap
import akka.ainterface.{ControlMessage, NodeName, Target}
import akka.pattern.ask
import akka.util.Timeout
import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * A hub of remote nodes.
 * Currently, all messages to remote nodes pass over this actor.
 */
private final class RemoteHub(localNode: LocalNode,
                              auth: Auth,
                              nodeStatusEventBus: RemoteNodeStatusEventBus,
                              nodeSupSup: ActorRef,
                              initiatorSupervisor: ActorRef,
                              tcpClient: ActorRef,
                              epmdClient: ActorRef) extends Actor with ActorLogging {
  private[remote] var nodes: BiMap[NodeName, ActorRef] = BiMap.empty
  private[remote] var pendings: Map[NodeName, (Option[ActorRef], Queue[ControlMessage])] = Map.empty

  implicit private[this] val timeout = Timeout(10.seconds)

  private[this] def initiatorProps(remoteNodeName: NodeName): Props = {
    HandshakeInitiator.props(
      localNode.nodeName,
      remoteNodeName,
      auth.getCookie(remoteNodeName),
      tcpClient,
      epmdClient,
      isHidden
    )
  }
  private[this] def startNode(nodeName: NodeName): Unit = {
    val suffix = ActorPathUtil.orUUID(nodeName.asString)
    val name = s"remote-node-supervisor-$suffix"
    val props = RemoteNodeSupervisor.props(localNode, nodeName, nodeStatusEventBus)
    nodeSupSup ! DynamicSupervisorProtocol.StartChild(self, props, name, nodeName)
  }

  override def receive: Receive = {
    case RemoteHubProtocol.Send(event) =>
      val target = event.target match {
        case Target.Pid(pid) => NodeName(pid.nodeName)
        case Target.Name(_, host) => host
      }
      nodes.get(target) match {
        case Some(node) => node ! RemoteNodeProtocol.Send(event)
        case None =>
          pendings.get(target) match {
            case Some((acceptor, buffer)) =>
              pendings = pendings.updated(target, (acceptor, buffer.enqueue(event)))
            case None =>
              startNode(target)
              pendings = pendings.updated(target, (None, Queue(event)))
          }
      }
    case Accepted(nodeName, acceptor) =>
      nodes.get(nodeName) match {
        case Some(node) => node ! RemoteNodeProtocol.Accepted(acceptor)
        case None =>
          pendings.get(nodeName) match {
            case Some((None, buffer)) =>
              pendings = pendings.updated(nodeName, (Some(acceptor), buffer))
            case Some((Some(old), buffer)) =>
              log.debug("Accepted multiple connections from the same node. {}", nodeName)
              context.stop(old)
              pendings = pendings.updated(nodeName, (Some(acceptor), buffer))
            case None =>
              startNode(nodeName)
              pendings = pendings.updated(nodeName, (Some(acceptor), Queue.empty))
          }
      }
    case DynamicSupervisorProtocol.ChildRef(node, Some(id: NodeName)) =>
      pendings.get(id) match {
        case None => log.error("Received an unexpected child. {}", id)
        case Some((acceptor, buffer)) =>
          context.watch(node)
          buffer.foreach { message =>
            node ! RemoteNodeProtocol.Send(message)
          }
          acceptor match {
            case Some(a) => node ! RemoteNodeProtocol.Accepted(a)
            case None =>
              import context.dispatcher
              val start = DynamicSupervisorProtocol.StartChild(initiatorProps(id))
              (initiatorSupervisor ? start).mapTo[ChildRef].onComplete {
                case Success(ChildRef(initiator, _)) =>
                  node ! RemoteNodeProtocol.Initiate(initiator)
                case Failure(_) => context.stop(node)
              }
          }
          pendings = pendings - id
          nodes = nodes + (id -> node)
      }
    case Tick =>
      nodes.values.foreach(_ ! RemoteNodeProtocol.Tick)
    case Terminated(ref) =>
      nodes = (nodes.inverse - ref).inverse
  }
}

private[remote] object RemoteHub {
  def props(localNode: LocalNode,
            auth: Auth,
            nodeStatusEventBus: RemoteNodeStatusEventBus,
            nodeSupSup: ActorRef,
            initiatorSupervisor: ActorRef,
            tcpClient: ActorRef,
            epmdClient: ActorRef): Props = {
    Props(
      classOf[RemoteHub],
      localNode,
      auth,
      nodeStatusEventBus,
      nodeSupSup,
      initiatorSupervisor,
      tcpClient,
      epmdClient
    )
  }
}

private[ainterface] object RemoteHubProtocol {
  private[remote] final case class Accepted(name: NodeName, handshakeAcceptor: ActorRef)

  private[remote] case object Tick

  final case class Send(event: ControlMessage)
}
