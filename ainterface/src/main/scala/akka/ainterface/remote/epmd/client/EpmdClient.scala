package akka.ainterface.remote.epmd.client

import akka.actor.{ActorRef, LoggingFSM, Props, Stash, Terminated}
import akka.ainterface.NodeName
import akka.ainterface.remote.epmd.client.EpmdClient.{Data, Dropping, Normal, PoolInitializing, State}
import akka.ainterface.remote.epmd.client.EpmdClientProtocol.GetPort
import akka.ainterface.util.actor.ActorPathUtil
import akka.ainterface.util.actor.DynamicSupervisorProtocol.{ChildRef, StartChild}
import akka.ainterface.util.collection.BiMap
import scala.concurrent.duration._

/**
 * An EpmdClient to request commands to EPMD.
 */
private final class EpmdClient(workerPoolSupervisorSupervisor: ActorRef,
                               tcpClient: ActorRef,
                               timeout: FiniteDuration)
  extends LoggingFSM[State, Data]
  with Stash {

  startWith(Normal, Data())

  when(Normal) {
    case Event(GetPort(replyTo, NodeName(alive, host)), Data(workers, _)) =>
      workers.get(host) match {
        case Some(pool) =>
          pool ! EpmdWorkerPoolProtocol.PortPlease(replyTo, alive)
          stay()
        case None =>
          val props = EpmdWorkerPoolSupervisor.props(host, tcpClient)
          val suffix = ActorPathUtil.orUUID(host)
          val name = s"worker-pool-supervisor-$suffix"
          workerPoolSupervisorSupervisor ! StartChild(self, props, name)
          stash()
          goto(PoolInitializing) using Data(workers, Some(host))
      }
  }
  
  when(PoolInitializing, stateTimeout = timeout) {
    case Event(ChildRef(ref, None), Data(workers, Some(host))) if sender() == workerPoolSupervisorSupervisor =>
      context.watch(ref)
      unstashAll()
      goto(Normal) using Data(workers + (host -> ref), None)
    case Event(StateTimeout, data) =>
      unstashAll()
      goto(Dropping) using data.copy(initializingHost = None)
    case _ =>
      stash()
      stay()
  }

  // If it times out on starting a child, the message is dropped.
  when(Dropping) {
    case Event(a, _) => goto(Normal)
  }

  whenUnhandled {
    case Event(Terminated(ref), Data(workers, _)) if workers.inverse.contains(ref) =>
      stay() using stateData.copy(workers = (workers.inverse - ref).inverse)
    case Event(event, _) =>
      val message = "Received an unexpected message. event = {} state = {} data = {}"
      log.warning(message, event, stateName, stateData)
      stay()
  }

  initialize()
}

private[client] object EpmdClient {
  def props(workerPoolSupervisorSupervisor: ActorRef, tcpClient: ActorRef): Props = {
    Props(classOf[EpmdClient], workerPoolSupervisorSupervisor, tcpClient, 10.seconds)
  }

  sealed abstract class State
  case object Normal extends State
  case object PoolInitializing extends State
  case object Dropping extends State

  final case class Data(workers: BiMap[String, ActorRef] = BiMap.empty,
                        initializingHost: Option[String] = None)
}

private[remote] object EpmdClientProtocol {

  /**
   * Ask about the specified node.
   */
  final case class GetPort(replyTo: ActorRef, nodeName: NodeName)

  /**
   * The result of [[GetPort]].
   */
  final case class PortResult(port: Int, version: Int)
}
