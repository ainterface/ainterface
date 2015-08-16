package akka.ainterface

import akka.actor.{ActorRef, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.{Auth, ErlCookie, RemoteHubProtocol, RemoteNodeStatusEventBus}
import com.typesafe.config.Config
import java.net.InetAddress
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import scala.util.Try
import scala.util.control.NonFatal

private[ainterface] object AinterfaceExtension
  extends ExtensionId[AinterfaceExtensionImpl]
  with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = AinterfaceExtension

  override def createExtension(system: ExtendedActorSystem): AinterfaceExtensionImpl = {
    new AinterfaceExtensionImpl(system)
  }
}

private[ainterface] class AinterfaceExtensionImpl(val system: ExtendedActorSystem)
  extends Extension {

  val latch = new CountDownLatch(1)

  private[this] def config = system.settings.config
  val localNodeName: NodeName = AinterfaceExtensionImpl.createName(config)
  system.log.debug("Local node name is {}.", localNodeName)

  val auth: Auth = AinterfaceExtensionImpl.setCookie(config) match {
    case Some(cookie) => Auth(localNodeName, cookie)
    case None => Auth(localNodeName, Paths.get(System.getProperty("user.home")))
  }

  @volatile var localNode: LocalNode = _
  @volatile var epmdClient: ActorRef = _
  @volatile var remoteHub: ActorRef = _
  @volatile var remoteNodeStatusEventBus: RemoteNodeStatusEventBus = _

  def sendEvent(event: ControlMessage): Unit = {
    val toLocal = event.target match {
      case Target.Pid(dest) => dest.nodeName == localNodeName.asErlAtom
      case Target.Name(_, dest) => dest == localNodeName
    }
    if (toLocal) {
      localNode.sendEvent(event)
    } else {
      remoteHub ! RemoteHubProtocol.Send(event)
    }
  }

  system.actorOf(AinterfaceSystem.props, AinterfaceExtensionImpl.rootName(config))
}

private object AinterfaceExtensionImpl {
  def rootName(config: Config): String = config.getString("akka.ainterface.root-name")

  def createName(config: Config): NodeName = {
    try {
      val name = config.getString("akka.ainterface.init.name")
      val host = InetAddress.getLocalHost.getHostName
      NodeName(name, host)
    } catch {
      case NonFatal(_) => NodeName.NoNode
    }
  }

  def setCookie(config: Config): Option[ErlCookie] = {
    Try(ErlCookie(config.getString("akka.ainterface.init.setcookie"))).toOption
  }
}
