package akka.ainterface.util.actor

import akka.actor.ActorRef

/**
 * A supervisor that forward messages to the specified actor.
 */
private[ainterface] trait ForwardSupervisor extends Supervisor {
  /**
   * The destination of messages.
   */
  protected[this] val to: ActorRef

  /**
   * Forward unhandled messages.
   * If a message is handled in `receive` method, the message is not forwarded.
   * So control message for supervisor can be used in it
   * without worrying about which this trait is mixed in or not.
   */
  final override def unhandled(message: Any): Unit = to forward message
}
