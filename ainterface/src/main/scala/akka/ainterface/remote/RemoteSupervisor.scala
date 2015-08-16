package akka.ainterface.remote

import akka.actor.{ActorRef, Props, SupervisorStrategy}
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.epmd.client.EpmdClientSupervisor
import akka.ainterface.remote.epmd.publisher.EpmdPublisherSupervisor
import akka.ainterface.remote.transport.TcpClientSupervisor
import akka.ainterface.util.actor.{DynamicSupervisor, EscalateStrategy, Supervisor}
import akka.ainterface.{AinterfaceExtension, AinterfaceExtensionImpl}

/**
 * Supervises remote modules.
 */
private final class RemoteSupervisor(localNode: LocalNode,
                                     nodeStatusEventBus: RemoteNodeStatusEventBus,
                                     auth: Auth) extends Supervisor {
  private[this] val extension: AinterfaceExtensionImpl = AinterfaceExtension(context.system)

  override def supervisorStrategy: SupervisorStrategy = EscalateStrategy

  {
    val tcpClient = startChild(TcpClientSupervisor.props, "tcp-client-supervisor")
    val epmdPublisher = {
      val props = EpmdPublisherSupervisor.props(localNode, tcpClient)
      startChild(props, "epmd-publisher-supervisor")
    }
    val epmdClient = startChild(EpmdClientSupervisor.props(tcpClient), "epmd-client-supervisor")
    extension.epmdClient = epmdClient
    val handshakeInitiatorSupervisor = {
      startChild(DynamicSupervisor.stoppingSupervisorProps, "handshake-initiator-supervisor")
    }
    val handshakeAcceptorSupervisor = {
      startChild(DynamicSupervisor.stoppingSupervisorProps, "handshake-acceptor-supervisor")
    }
    val remoteNodeSupSup = {
      startChild(DynamicSupervisor.stoppingSupervisorProps, "remote-node-supervisor-supervisor")
    }
    val remoteHub = {
      val props = RemoteHub.props(
        localNode,
        auth,
        nodeStatusEventBus,
        remoteNodeSupSup,
        handshakeInitiatorSupervisor,
        tcpClient,
        epmdClient
      )
      startChild(props, "remote-hub")
    }
    extension.remoteHub = remoteHub
    val acceptor = {
      val props = Acceptor.props(
        localNode.nodeName,
        auth,
        epmdPublisher,
        tcpClient,
        handshakeAcceptorSupervisor,
        remoteHub
      )
      startChild(props, "acceptor")
    }
    acceptor ! AcceptorProtocol.Start
    startChild(Ticker.props(remoteHub), "ticker")
    remoteHub
  }

  override protected[this] def onTerminate(child: ActorRef): Unit = context.stop(self)
}

private[ainterface] object RemoteSupervisor {
  def props(localNode: LocalNode,
            nodeStatusEventBus: RemoteNodeStatusEventBus,
            auth: Auth): Props = {
    Props(classOf[RemoteSupervisor], localNode, nodeStatusEventBus, auth)
  }
}
