package akka.ainterface.remote

import akka.actor.ActorRef
import akka.ainterface.NodeName
import akka.ainterface.datatype.{ErlPid, ErlTerm}
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.handshake.HandshakeInitiator
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.{arbErlPid, arbErlTerm, arbNodeName}
import akka.ainterface.util.actor.DynamicSupervisorProtocol
import akka.ainterface.util.actor.DynamicSupervisorProtocol.ChildRef
import akka.ainterface.util.collection.BiMap
import akka.testkit.{TestActorRef, TestProbe}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import scala.collection.immutable.Queue

class RemoteHubSpec extends ActorSpec with MockitoSugar {
  private[this] val localNodeName = NodeName("local", "ainterface")
  private[this] val localNode: LocalNode = {
    val node = mock[LocalNode]
    when(node.nodeName).thenReturn(localNodeName)
    node
  }
  private[this] val nodeStatusEventBus: RemoteNodeStatusEventBus = new RemoteNodeStatusEventBus
  private[this] val tcpClient: ActorRef = nullRef
  private[this] val epmdClient: ActorRef = nullRef
  private[this] def setup(auth: Auth = mock[Auth],
                          nodeSupSup: ActorRef = nullRef,
                          initiatorSupervisor: ActorRef = nullRef) = {
    TestActorRef(new RemoteHub(
      localNode,
      auth,
      nodeStatusEventBus,
      nodeSupSup,
      initiatorSupervisor,
      tcpClient,
      epmdClient
    ))
  }

  "RemoteHub" should {
    "forward a control message" when {
      "the delegate to the target exists" in {
        forAll { (target: ErlPid, message: ErlTerm) =>
          val hub = setup()
          val remoteNode = TestProbe()
          hub.underlyingActor.nodes += NodeName(target.nodeName) -> remoteNode.ref

          val send = akka.ainterface.Send(target, message)
          hub ! RemoteHubProtocol.Send(send)
          remoteNode.expectMsg(RemoteNodeProtocol.Send(send))
          ()
        }
      }
    }

    "buffer a control message" when {
      "starting the delegate to the target" in {
        forAll { (target: ErlPid, message1: ErlTerm, message2: ErlTerm) =>
          val hub = setup()
          val nodeName = NodeName(target.nodeName)
          hub.underlyingActor.pendings += nodeName -> (None, Queue.empty)

          val send1 = akka.ainterface.Send(target, message1)
          hub ! RemoteHubProtocol.Send(send1)
          assert(hub.underlyingActor.pendings === Map(nodeName -> (None, Queue(send1))))

          val send2 = akka.ainterface.Send(target, message2)
          hub ! RemoteHubProtocol.Send(send2)
          assert(hub.underlyingActor.pendings === Map(nodeName -> (None, Queue(send1, send2))))
        }
      }
    }

    "try to start the delegate" when {
      "it receives a control message and it has not started the delegate to the target" in {
        forAll { (target: ErlPid, message: ErlTerm) =>
          val nodeSupSup = TestProbe()
          val nodeName = NodeName(target.nodeName)
          val hub = setup(nodeSupSup = nodeSupSup.ref)

          val send = akka.ainterface.Send(target, message)
          hub ! RemoteHubProtocol.Send(send)
          val props = RemoteNodeSupervisor.props(localNode, nodeName, nodeStatusEventBus)
          nodeSupSup.expectMsgPF() {
            case DynamicSupervisorProtocol.StartChild(Some(`hub`), `props`, _, Some(`nodeName`)) =>
          }
          assert(hub.underlyingActor.pendings === Map(nodeName -> (None, Queue(send))))
        }
      }
    }

    "forward an accepted event" when {
      "the delegate to the source exists" in {
        forAll { nodeName: NodeName =>
          val hub = setup()
          val remoteNode = TestProbe()
          hub.underlyingActor.nodes += nodeName -> remoteNode.ref

          val acceptor = TestProbe().ref
          hub ! RemoteHubProtocol.Accepted(nodeName, acceptor)
          remoteNode.expectMsg(RemoteNodeProtocol.Accepted(acceptor))
          ()
        }
      }
    }

    "put the acceptor" when {
      "starting the delegate to the source and no acceptor exists" in {
        forAll { nodeName: NodeName =>
          val hub = setup()
          hub.underlyingActor.pendings += nodeName -> (None, Queue.empty)

          val acceptor = TestProbe().ref
          hub ! RemoteHubProtocol.Accepted(nodeName, acceptor)
          assert(hub.underlyingActor.pendings === Map(nodeName -> (Some(acceptor), Queue.empty)))
        }
      }
    }

    "replace the acceptor" when {
      "starting the delegate to the source and it already has another acceptor" in {
        forAll { nodeName: NodeName =>
          val hub = setup()
          val old = TestProbe().ref
          watch(old)
          hub.underlyingActor.pendings += nodeName -> (Some(old), Queue.empty)

          val acceptor = TestProbe().ref
          hub ! RemoteHubProtocol.Accepted(nodeName, acceptor)
          expectTerminated(old)
          assert(hub.underlyingActor.pendings === Map(nodeName -> (Some(acceptor), Queue.empty)))
        }
      }
    }

    "try to start the delegate" when {
      "it receives an accepted event and it has not started the delegate to the source" in {
        forAll { nodeName: NodeName =>
          val nodeSupSup = TestProbe()
          val hub = setup(nodeSupSup = nodeSupSup.ref)

          val acceptor = TestProbe().ref
          hub ! RemoteHubProtocol.Accepted(nodeName, acceptor)
          val props = RemoteNodeSupervisor.props(localNode, nodeName, nodeStatusEventBus)
          nodeSupSup.expectMsgPF() {
            case DynamicSupervisorProtocol.StartChild(Some(`hub`), `props`, _, Some(`nodeName`)) =>
          }
          assert(hub.underlyingActor.pendings === Map(nodeName -> (Some(acceptor), Queue.empty)))
        }
      }
    }

    "send an accepted event to the new delegate and consume the buffer" when {
      "it receives it and it has received an acceptor" in {
        forAll { (target: ErlPid, message1: ErlTerm, message2: ErlTerm) =>
          val hub = setup()
          val nodeName = NodeName(target.nodeName)
          val acceptor = TestProbe().ref
          val send1 = akka.ainterface.Send(target, message1)
          val send2 = akka.ainterface.Send(target, message2)
          hub.underlyingActor.pendings += nodeName -> (Some(acceptor), Queue(send1, send2))

          val remoteNode = TestProbe()
          hub ! DynamicSupervisorProtocol.ChildRef(remoteNode.ref, Some(nodeName))
          remoteNode.expectMsg(RemoteNodeProtocol.Send(send1))
          remoteNode.expectMsg(RemoteNodeProtocol.Send(send2))
          remoteNode.expectMsg(RemoteNodeProtocol.Accepted(acceptor))
          assert(hub.underlyingActor.nodes.get(nodeName) === Some(remoteNode.ref))
          assert(hub.underlyingActor.pendings === Map.empty)

          // Test that the remote node is monitored.
          system.stop(remoteNode.ref)
          awaitAssert(hub.underlyingActor.nodes.get(nodeName) === None)
        }
      }
    }

    "start and initiator and consume the buffer" when {
      "it receives it and it has not received an acceptor" in {
        forAll { (target: ErlPid, message1: ErlTerm, message2: ErlTerm) =>
          val nodeName = NodeName(target.nodeName)
          val auth = mock[Auth]
          when(auth.getCookie(nodeName)).thenReturn(ErlCookie("cookie"))
          val initiatorSupervisor = TestProbe()
          val hub = setup(auth = auth, initiatorSupervisor = initiatorSupervisor.ref)
          val send1 = akka.ainterface.Send(target, message1)
          val send2 = akka.ainterface.Send(target, message2)
          hub.underlyingActor.pendings += nodeName ->(None, Queue(send1, send2))

          val remoteNode = TestProbe()
          hub ! DynamicSupervisorProtocol.ChildRef(remoteNode.ref, Some(nodeName))
          remoteNode.expectMsg(RemoteNodeProtocol.Send(send1))
          remoteNode.expectMsg(RemoteNodeProtocol.Send(send2))

          val props = HandshakeInitiator.props(
            localNodeName,
            nodeName,
            ErlCookie("cookie"),
            tcpClient,
            epmdClient,
            isHidden
          )
          initiatorSupervisor.expectMsg(DynamicSupervisorProtocol.StartChild(props))
          val initiator = TestProbe().ref
          initiatorSupervisor.reply(ChildRef(initiator, None))
          remoteNode.expectMsg(RemoteNodeProtocol.Initiate(initiator))

          assert(hub.underlyingActor.nodes.get(nodeName) === Some(remoteNode.ref))
          assert(hub.underlyingActor.pendings === Map.empty)

          // Test that the remote node is monitored.
          system.stop(remoteNode.ref)
          awaitAssert(hub.underlyingActor.nodes.get(nodeName) === None)
        }
      }
    }

    "broadcast the tick message to all nodes" in {
      forAll { _nodeNames: Seq[NodeName] =>
        val nodeNames = _nodeNames.distinct
        val hub = setup()
        val remoteNodes = nodeNames.map(_ => TestProbe())
        hub.underlyingActor.nodes = {
          (nodeNames zip remoteNodes).foldLeft(BiMap.empty[NodeName, ActorRef]) {
            case (acc, (name, node)) => acc + (name -> node.ref)
          }
        }

        hub ! RemoteHubProtocol.Tick
        remoteNodes.foreach { node =>
          node.expectMsg(RemoteNodeProtocol.Tick)
        }
      }
    }

    "remove the node" when {
      "a registered node terminates" in {
        forAll { _nodeNames: Seq[NodeName] =>
          val nodeNames = _nodeNames.distinct
          val hub = setup()
          val nameAndRemoteNodes = nodeNames.map(_ -> TestProbe().ref)
          hub.underlyingActor.nodes = {
            nameAndRemoteNodes.foldLeft(BiMap.empty[NodeName, ActorRef]) {
              case (acc, nameAndNode) => acc + nameAndNode
            }
          }

          nameAndRemoteNodes.foreach {
            case (name, node) =>
              assert(hub.underlyingActor.nodes.get(name) === Some(node))
              system.stop(node)
              awaitAssert(hub.underlyingActor.nodes.get(name) === None)
          }
          awaitAssert(hub.underlyingActor.nodes === BiMap.empty)
        }
      }
    }
  }
}
