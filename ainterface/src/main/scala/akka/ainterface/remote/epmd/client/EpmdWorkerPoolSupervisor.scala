package akka.ainterface.remote.epmd.client

import akka.actor.{ActorRef, Props, SupervisorStrategy}
import akka.ainterface.util.actor.{ActorPathUtil, EscalateStrategy, ForwardSupervisor}

/**
 * Supervises a worker pool.
 */
private final class EpmdWorkerPoolSupervisor(host: String, tcpClient: ActorRef) extends ForwardSupervisor {
  override def supervisorStrategy: SupervisorStrategy = EscalateStrategy

  override protected[this] val to: ActorRef = {
    val suffix = ActorPathUtil.orUUID(host)
    val props = EpmdWorkerPool.props(host, tcpClient)
    startChild(props, s"worker-pool-$suffix")
  }

  override protected[this] def onTerminate(child: ActorRef): Unit = context.stop(self)
}

private[client] object EpmdWorkerPoolSupervisor {
  def props(host: String, tcpClient: ActorRef): Props = {
    Props(classOf[EpmdWorkerPoolSupervisor], host, tcpClient)
  }
}
