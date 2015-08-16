package akka.ainterface.remote

import akka.actor.ActorRef
import akka.ainterface.NodeName
import akka.ainterface.remote.RemoteNodeStatusEventBus.NodeDown
import akka.event.{EventBus, LookupClassification}

/**
 * An event bus to monitor statuses of remote nodes.
 */
private[ainterface] class RemoteNodeStatusEventBus extends EventBus with LookupClassification {
  override type Event = NodeDown
  override type Classifier = NodeName
  override type Subscriber = ActorRef

  override protected def mapSize(): Int = 128

  override protected def compareSubscribers(a: Subscriber, b: Subscriber): Int = a.compareTo(b)

  override protected def classify(event: Event): Classifier = event.nodeName

  override protected def publish(event: Event, subscriber: Subscriber): Unit = subscriber ! event
}

private[ainterface] object RemoteNodeStatusEventBus {
  case class NodeDown(nodeName: NodeName)
}
