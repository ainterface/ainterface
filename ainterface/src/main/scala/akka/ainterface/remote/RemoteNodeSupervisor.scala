package akka.ainterface.remote

import akka.actor.{ActorRef, Props, SupervisorStrategy}
import akka.ainterface.NodeName
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.handshake.HandshakeCoordinator
import akka.ainterface.util.actor.{ActorPathUtil, EscalateStrategy, ForwardSupervisor}

/**
 * Supervises a remote node.
 */
private final class RemoteNodeSupervisor(localNode: LocalNode,
                                         remoteNodeName: NodeName,
                                         nodeStatusEventBus: RemoteNodeStatusEventBus)
  extends ForwardSupervisor {

  override def supervisorStrategy: SupervisorStrategy = EscalateStrategy

  override protected[this] val to: ActorRef = {
    val coordinator = {
      val props = HandshakeCoordinator.props(localNode.nodeName, remoteNodeName)
      startChild(props, "handshake-coordinator")
    }
    val remoteNodeProps = RemoteNode.props(
      localNode,
      remoteNodeName,
      coordinator,
      nodeStatusEventBus
    )
    val suffix = ActorPathUtil.orUUID(remoteNodeName.asString)
    startChild(remoteNodeProps, s"remote-node-$suffix")
  }

  override protected[this] def onTerminate(child: ActorRef): Unit = context.stop(self)
}

private[remote] object RemoteNodeSupervisor {
  def props(localNode: LocalNode,
            remoteNodeName: NodeName,
            nodeStatusEventBus: RemoteNodeStatusEventBus): Props = {
    Props(classOf[RemoteNodeSupervisor], localNode, remoteNodeName, nodeStatusEventBus)
  }
}
