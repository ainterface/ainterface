package akka.ainterface.util.actor

import akka.actor.{ActorRef, Props, SupervisorStrategy}
import akka.ainterface.util.actor.DynamicSupervisorProtocol.{ChildRef, StartChild}

/**
 * A supervisor that has ability to add children dynamically.
 */
private[ainterface] trait DynamicSupervisor extends Supervisor {
  private[this] val startChild: Receive = {
    case StartChild(replyToOption, props, name, identifier) =>
      val child = startChild(props, name)
      val replyTo = replyToOption.getOrElse(sender())
      replyTo ! ChildRef(child, identifier)
  }

  override def receive: Receive = startChild orElse super.receive
}

private[ainterface] object DynamicSupervisor {
  private[this] final class DynamicStoppingSupervisor extends DynamicSupervisor {
    override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

    override protected[this] def onTerminate(child: ActorRef): Unit = ()
  }

  def stoppingSupervisorProps: Props = Props[DynamicStoppingSupervisor]
}

private[ainterface] object DynamicSupervisorProtocol {

  /**
   * Starts a child.
   */
  final case class StartChild(replyTo: Option[ActorRef],
                              props: Props,
                              name: Option[String],
                              identifier: Option[Any])

  object StartChild {
    def apply(replyTo: ActorRef, props: Props): StartChild = {
      StartChild(Some(replyTo), props, None, None)
    }

    def apply(replyTo: ActorRef, props: Props, name: String): StartChild = {
      StartChild(Some(replyTo), props, Some(name), None)
    }

    def apply(replyTo: ActorRef, props: Props, name: String, identifier: Any): StartChild = {
      StartChild(Some(replyTo), props, Some(name), Some(identifier))
    }

    def apply(replyTo: ActorRef, props: Props, identifier: Any): StartChild = {
      StartChild(Some(replyTo), props, None, Some(identifier))
    }

    def apply(props: Props): StartChild = StartChild(None, props, None, None)

    def apply(props: Props, name: String): StartChild = StartChild(None, props, Some(name), None)
  }

  /**
   * The result of [[StartChild]].
   */
  final case class ChildRef(ref: ActorRef, identifier: Option[Any])
}
