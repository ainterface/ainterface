package akka.ainterface.remote.epmd.client

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ActorRef, AllForOneStrategy, Props, SupervisorStrategy}
import akka.ainterface.util.actor.{DynamicSupervisor, ForwardSupervisor}
import scala.util.control.NonFatal

/**
 * Supervises an epmd client.
 */
private final class EpmdClientSupervisor(tcpClient: ActorRef) extends ForwardSupervisor {
  override def supervisorStrategy: SupervisorStrategy = AllForOneStrategy() {
    case NonFatal(_) => Restart
    case e => super.supervisorStrategy.decider(e)
  }

  override protected[this] val to: ActorRef = {
    val supervisorProps = DynamicSupervisor.stoppingSupervisorProps
    val workerPoolSupSup = startChild(supervisorProps, "worker-pool-supervisors")
    startChild(EpmdClient.props(workerPoolSupSup, tcpClient))
  }

  override protected[this] def onTerminate(child: ActorRef): Unit = context.stop(self)
}

private[remote] object EpmdClientSupervisor {
  def props(tcpClient: ActorRef): Props = Props(classOf[EpmdClientSupervisor], tcpClient)
}
