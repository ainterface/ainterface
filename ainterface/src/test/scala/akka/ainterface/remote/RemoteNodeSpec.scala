package akka.ainterface.remote

import akka.actor.ActorRef
import akka.actor.FSM.StateTimeout
import akka.ainterface.datatype.{ErlAtom, ErlPid}
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.RemoteNode._
import akka.ainterface.remote.RemoteNodeProtocol.Established
import akka.ainterface.remote.RemoteNodeStatusEventBus.NodeDown
import akka.ainterface.remote.handshake.HandshakeCoordinatorProtocol
import akka.ainterface.remote.transport.TcpConnectionProtocol
import akka.ainterface.remote.transport.TcpConnectionProtocol.{ReadFailure, ReadSuccess}
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbNodeConfig
import akka.ainterface.{ControlMessage, NodeName}
import akka.testkit.{TestFSMRef, TestProbe}
import akka.util.ByteString
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.mock.MockitoSugar
import scala.collection.immutable.Queue
import scodec.{Codec, Err}

class RemoteNodeSpec extends ActorSpec with MockitoSugar {
  private[this] val localNodeName = NodeName("local", "ainterface")
  private[this] val remoteNodeName = NodeName("remote", "ainterface")
  implicit private[this] val codec: Codec[RemoteMessage] = {
    RemoteMessage.codec(localNodeName, remoteNodeName)
  }
  private[this] def message(text: String): akka.ainterface.Send = {
    val pid = ErlPid(ErlAtom("remote@ainterface"), 0, 0, 0)
    akka.ainterface.Send(pid, ErlAtom(text))
  }
  private[this] def localNode: LocalNode = {
    val node = mock[LocalNode]
    when(node.nodeName).thenReturn(localNodeName)
    node
  }
  private[this] def setup(localNode: LocalNode = localNode,
                          coordinator: ActorRef = nullRef,
                          nodeStatusEventBus: RemoteNodeStatusEventBus = new RemoteNodeStatusEventBus) = {
    TestFSMRef(new RemoteNode(localNode, remoteNodeName, coordinator, nodeStatusEventBus))
  }

  "RemoteNode" when {
    "Pending" should {
      "initiate a handshake" when {
        "receiving Initiate" in {
          val coordinator = TestProbe()
          val remoteNode = setup(coordinator = coordinator.ref)

          val initiator = TestProbe().ref
          remoteNode ! RemoteNodeProtocol.Initiate(initiator)
          coordinator.expectMsg(HandshakeCoordinatorProtocol.Initiate(initiator, remoteNode))
          assert(remoteNode.stateName === Pending)
        }
      }

      "accept a handshake" when {
        "receiving Accepted" in {
          val coordinator = TestProbe()
          val remoteNode = setup(coordinator = coordinator.ref)

          val acceptor = TestProbe().ref
          remoteNode ! RemoteNodeProtocol.Accepted(acceptor)
          coordinator.expectMsg(HandshakeCoordinatorProtocol.Accepted(acceptor, remoteNode))
          assert(remoteNode.stateName === Pending)
        }
      }

      "consume the buffer and become up" when {
        "a handshake is completed" in {
          forAll { config: NodeConfig =>
            val remoteNode = setup()
            val messages = Queue(message("mofu"), message("poyo"))
            remoteNode.setState(Pending, Buffer(messages))

            val connection = TestProbe()
            remoteNode ! Established(connection.ref, config)
            connection.expectMsgPF() {
              case TcpConnectionProtocol.Read(Some(replyTo), _, _) => assert(replyTo === remoteNode)
            }
            connection.expectMsg(TcpConnectionProtocol.Write(RemoteMessage(Some(message("mofu")))))
            connection.expectMsg(TcpConnectionProtocol.Write(RemoteMessage(Some(message("poyo")))))
            assert(remoteNode.stateName === Up)
            assert(remoteNode.stateData === Peer(connection.ref, config, TickStatus(), SocketStatus()))
            assert(!remoteNode.isStateTimerActive)
          }
        }
      }

      "buffer the control message" when {
        "receiving it" in {
          val remoteNode = setup()

          remoteNode ! RemoteNodeProtocol.Send(message("mofu"))
          assert(remoteNode.stateName === Pending)
          assert(remoteNode.stateData === Buffer(Queue(message("mofu"))))

          remoteNode ! RemoteNodeProtocol.Send(message("poyo"))
          assert(remoteNode.stateName === Pending)
          assert(remoteNode.stateData === Buffer(Queue(message("mofu"), message("poyo"))))
        }
      }

      "ignore tick" in {
        val remoteNode = setup()

        remoteNode ! RemoteNodeProtocol.Tick
        assert(remoteNode.stateName === Pending)
        assert(remoteNode.stateData === Buffer())
      }
    }

    "Up" should {
      "send a message" in {
        forAll { config: NodeConfig =>
          val remoteNode = setup()
          val connection = TestProbe()
          val data = Peer(connection.ref, config)
          remoteNode.setState(Up, data)

          remoteNode ! RemoteNodeProtocol.Send(message("mofu"))
          connection.expectMsg(TcpConnectionProtocol.Write(RemoteMessage(Some(message("mofu")))))
          assert(remoteNode.stateName === Up)
          assert(remoteNode.stateData === data.copy(socketStatus = SocketStatus().doWrite()))
        }
      }

      "send a tick" in {
        forAll { config: NodeConfig =>
          val remoteNode = setup()
          val connection = TestProbe()
          val data = Peer(connection.ref, config)
          remoteNode.setState(Up, data)

          remoteNode ! RemoteNodeProtocol.Tick
          connection.expectMsg(TcpConnectionProtocol.Write(ByteString(0, 0, 0, 0)))
          assert(remoteNode.stateName === Up)
          assert(remoteNode.stateData === data.copy(tickStatus = TickStatus(0, 1, 2, 1)))
        }
      }

      "read a message" in {
        forAll { config: NodeConfig =>
          val local = localNode
          val remoteNode = setup(localNode = local)
          val data = Peer(nullRef, config)
          remoteNode.setState(Up, data)

          remoteNode ! ReadSuccess(RemoteMessage(Some(message("mofu"))))
          assert(remoteNode.stateName === Up)
          assert(remoteNode.stateData === data.copy(socketStatus = SocketStatus().doRead()))
          verify(local).sendEvent(message("mofu"))
        }
      }

      "read a tick" in {
        forAll { config: NodeConfig =>
          val local = localNode
          val remoteNode = setup(localNode = local)
          val data = Peer(nullRef, config)
          remoteNode.setState(Up, data)

          remoteNode ! ReadSuccess(RemoteMessage(None))
          assert(remoteNode.stateName === Up)
          assert(remoteNode.stateData === data.copy(socketStatus = SocketStatus().doRead()))
          verify(local, never()).sendEvent(any[ControlMessage])
        }
      }

      "forward Accepted to the coordinator" in {
        forAll { config: NodeConfig =>
          val coordinator = TestProbe()
          val remoteNode = setup(coordinator = coordinator.ref)
          val data = Peer(nullRef, config)
          remoteNode.setState(Up, data)

          val acceptor = TestProbe().ref
          remoteNode ! RemoteNodeProtocol.Accepted(acceptor)
          coordinator.expectMsg(HandshakeCoordinatorProtocol.Accepted(acceptor, remoteNode))
          assert(remoteNode.stateName === Up)
          assert(remoteNode.stateData === data)
        }
      }

      "go back to pending status" when {
        "the connection terminates" in {
          forAll { config: NodeConfig =>
            val remoteNode = setup()
            val connection = TestProbe()
            val data = Peer(connection.ref, config)
            remoteNode.setState(Up, data)

            system.stop(connection.ref)
            awaitAssert(remoteNode.stateName === Pending)
            awaitAssert(remoteNode.stateData === Buffer())
            assert(remoteNode.isStateTimerActive)
          }
        }
      }

      "stop" when {
        "receiving an unexpected packet" in {
          forAll { config: NodeConfig =>
            val bus = mock[RemoteNodeStatusEventBus]
            val remoteNode = setup(nodeStatusEventBus = bus)
            watch(remoteNode)
            val data = Peer(nullRef, config)
            remoteNode.setState(Up, data)

            remoteNode ! ReadFailure(Err("mofu"))
            expectTerminated(remoteNode)
            verify(bus).publish(NodeDown(remoteNodeName))
          }
        }
      }
    }

    "anytime" should {
      implicit val arbState: Arbitrary[State] = Arbitrary(Gen.oneOf(Pending, Up))

      "stop" when {
        "it times out" in {
          forAll { state: State =>
            val remoteNode = setup()
            watch(remoteNode)
            remoteNode.setState(state)

            remoteNode ! StateTimeout
            expectTerminated(remoteNode)
            ()
          }
        }

        "the coordinator terminates" in {
          forAll { state: State =>
            val coordinator = TestProbe().ref
            val remoteNode = setup(coordinator = coordinator)
            watch(remoteNode)
            remoteNode.setState(state)

            system.stop(coordinator)
            expectTerminated(remoteNode)
            ()
          }
        }
      }
    }
  }
}
