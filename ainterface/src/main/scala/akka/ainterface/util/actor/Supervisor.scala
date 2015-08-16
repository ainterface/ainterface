package akka.ainterface.util.actor

import akka.actor.{Actor, ActorRef, Props, Terminated}

/**
 * A basic supervisor.
 */
private[ainterface] trait Supervisor extends Actor {
  /**
   * @throws akka.actor.InvalidActorNameException if the given name is
   *   invalid or already in use
   * @throws akka.ConfigurationException if deployment, dispatcher
   *   or mailbox configuration is wrong
   */
  final protected[this] def startChild(props: Props, name: Option[String]): ActorRef = {
    val child = name match {
      case Some(v) => context.actorOf(props, name = v)
      case None => context.actorOf(props)
    }
    context.watch(child)
  }

  /**
   * @throws akka.ConfigurationException if deployment, dispatcher
   *   or mailbox configuration is wrong
   */
  final protected[this] def startChild(props: Props): ActorRef = {
    startChild(props, None)
  }

  /**
   * @throws akka.actor.InvalidActorNameException if the given name is
   *   invalid or already in use
   * @throws akka.ConfigurationException if deployment, dispatcher
   *   or mailbox configuration is wrong
   */
  final protected[this] def startChild(props: Props, name: String): ActorRef = {
    startChild(props, Some(name))
  }

  protected[this] def onTerminate(child: ActorRef): Unit

  override def receive: Receive = {
    case Terminated(ref) => onTerminate(ref)
  }
}
