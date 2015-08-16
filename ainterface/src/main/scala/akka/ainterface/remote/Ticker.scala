package akka.ainterface.remote

import akka.actor.{Actor, ActorLogging, ActorRef, Props, ReceiveTimeout}
import scala.concurrent.duration._

/**
 * An actor to send tick event to remote nodes.
 */
private final class Ticker(remoteHub: ActorRef,
                           tickTime: FiniteDuration) extends Actor with ActorLogging {
  override def preStart(): Unit = {
    context.setReceiveTimeout(tickTime)
  }

  override def receive: Receive = {
    case ReceiveTimeout => remoteHub ! RemoteHubProtocol.Tick
  }
}

private[remote] object Ticker {
  private[this] val TickTime = 15.seconds

  def props(remoteHub: ActorRef): Props = Props(classOf[Ticker], remoteHub, TickTime)
}
