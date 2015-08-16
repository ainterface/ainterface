package akka.ainterface

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ActorRef, ActorSystem, AllForOneStrategy, Props, SupervisorStrategy}
import akka.ainterface.local.{LocalDeadLetterBox, LocalNode}
import akka.ainterface.remote.{RemoteNodeStatusEventBus, RemoteSupervisor}
import akka.ainterface.util.actor.Supervisor
import scala.util.control.NonFatal

/**
 * The ainterface system.
 */
private final class AinterfaceSystem extends Supervisor {
  private[this] def extension = AinterfaceExtension(context.system)

  override def supervisorStrategy: SupervisorStrategy = {
    AllForOneStrategy() {
      case NonFatal(_) => Restart
    }
  }

  private[this] val localNode = {
    val localDeadLetterBox = new LocalDeadLetterBox(extension)
    LocalNode(extension.localNodeName, localDeadLetterBox, extension.latch)
  }

  extension.localNode = localNode
  extension.remoteNodeStatusEventBus = new RemoteNodeStatusEventBus

  private[this] var remoteSupervisor: ActorRef = {
    val props = RemoteSupervisor.props(localNode, extension.remoteNodeStatusEventBus, extension.auth)
    startChild(props, "remote-supervisor")
  }

  override protected[this] def onTerminate(child: ActorRef): Unit = {
    if (child == remoteSupervisor) {
      val props = RemoteSupervisor.props(
        localNode,
        extension.remoteNodeStatusEventBus,
        extension.auth
      )
      remoteSupervisor = startChild(props, "remote-supervisor")
    }
  }
}

object AinterfaceSystem {
  private[ainterface] val props: Props = Props[AinterfaceSystem]

  def init(system: ActorSystem): Unit = {
    AinterfaceExtension(system).latch.await()
  }
}
