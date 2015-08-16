package akka.ainterface.remote.transport

import akka.actor.{ActorRef, Props, SupervisorStrategy}
import akka.ainterface.util.actor.{DynamicSupervisor, EscalateStrategy, ForwardSupervisor}

/**
 * Supervises [[TcpClient]].
 */
private final class TcpClientSupervisor extends ForwardSupervisor {

  override def supervisorStrategy: SupervisorStrategy = EscalateStrategy

  override protected[this] val to: ActorRef = {
    val supProps = DynamicSupervisor.stoppingSupervisorProps
    val connectorSupervisor = startChild(supProps, "tcp-connector-supervisor")
    val connectionSupervisor = startChild(supProps, "tcp-connection-supervisor")
    val clientProps = TcpClient.props(connectorSupervisor, connectionSupervisor)
    startChild(clientProps, "tcp-client")
  }

  override protected[this] def onTerminate(child: ActorRef): Unit = context.stop(self)
}

private[remote] object TcpClientSupervisor {
  val props: Props = Props[TcpClientSupervisor]
}
