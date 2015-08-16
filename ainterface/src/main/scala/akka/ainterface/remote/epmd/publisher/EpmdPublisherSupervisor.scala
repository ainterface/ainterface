package akka.ainterface.remote.epmd.publisher

import akka.actor.{ActorRef, Props, SupervisorStrategy}
import akka.ainterface.local.LocalNode
import akka.ainterface.util.actor.{EscalateStrategy, ForwardSupervisor}

/**
 * Supervises an epmd publisher.
 */
private final class EpmdPublisherSupervisor(localNode: LocalNode,
                                            tcpClient: ActorRef) extends ForwardSupervisor {
  override def supervisorStrategy: SupervisorStrategy = EscalateStrategy

  override protected[this] val to: ActorRef = {
    val publisherProps = EpmdPublisher.props(localNode, tcpClient)
    startChild(publisherProps, "epmd-publisher")
  }

  override protected[this] def onTerminate(child: ActorRef): Unit = context.stop(self)
}

private[remote] object EpmdPublisherSupervisor {
  def props(localNode: LocalNode, tcpClient: ActorRef): Props = {
    Props(classOf[EpmdPublisherSupervisor], localNode, tcpClient)
  }
}
