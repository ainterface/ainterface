package akka.ainterface.remote.handshake

import akka.actor.FSM.StateTimeout
import akka.ainterface.NodeName
import akka.ainterface.remote.handshake.HandshakeCoordinator._
import akka.ainterface.remote.handshake.HandshakeCoordinatorProtocol.{Accepted, Initiate, IsPending, StillPending}
import akka.ainterface.remote.transport.TcpConnectionProtocol
import akka.ainterface.remote.{NodeConfig, RemoteNodeProtocol}
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.{arbNodeConfig, arbNodeName}
import akka.testkit.{TestFSMRef, TestProbe}
import org.scalacheck.{Arbitrary, Gen}
import scala.concurrent.duration._

class HandshakeCoordinatorSpec extends ActorSpec {
  private[this] def setup(localNodeName: NodeName = NodeName("local", "ainterface"),
                          remoteNodeName: NodeName = NodeName("remote", "ainterface")) = {
    TestFSMRef(new HandshakeCoordinator(localNodeName, remoteNodeName))
  }

  "HandshakeCoordinator" when {
    "Idle" should {
      "have the timeout setting" in {
        val coordinator = setup()
        assert(coordinator.isStateTimerActive)
      }

      "initiate a handshake" when {
        "receiving Initiate" in {
          val initiator = TestProbe()
          val coordinator = setup()
          watch(coordinator)

          val remoteNode = TestProbe().ref
          coordinator ! Initiate(initiator.ref, remoteNode)
          initiator.expectMsg(HandshakeInitiatorProtocol.Start(coordinator))
          assert(coordinator.stateName === Initiating)
          assert(coordinator.stateData === Data(remoteNode = Some(remoteNode), pending = Some(initiator.ref)))
          assert(coordinator.isStateTimerActive)

          // Test that the initiator is monitored.
          system.stop(initiator.ref)
          expectTerminated(coordinator)
        }
      }

      "accept a handshake" when {
        "receiving Accepted" in {
          val acceptor = TestProbe().ref
          val coordinator = setup()
          watch(coordinator)

          val remoteNode = TestProbe().ref
          coordinator ! Accepted(acceptor, remoteNode)
          assert(coordinator.stateName === Accepting)
          assert(coordinator.stateData === Data(remoteNode = Some(remoteNode), pending = Some(acceptor)))
          assert(coordinator.isStateTimerActive)

          // Test that the acceptor is monitored.
          system.stop(acceptor)
          expectTerminated(coordinator)
        }
      }
    }

    "Initiating" should {
      "send pending status `true`" when {
        "receiving StillPending" in {
          val initiator = TestProbe()
          val coordinator = setup()
          val remoteNode = TestProbe().ref
          val data = Data(remoteNode = Some(remoteNode), pending = Some(initiator.ref))
          coordinator.setState(Initiating, data)

          coordinator ! StillPending(initiator.ref)
          initiator.expectMsg(IsPending(toBeContinued = true))
          assert(coordinator.stateName === Initiating)
          assert(coordinator.stateData === data)
          assert(coordinator.isStateTimerActive)
        }
      }

      "go to Up" when {
        "the handshake has been established" in {
          forAll { config: NodeConfig =>
            val coordinator = setup()
            watch(coordinator)
            val remoteNode = TestProbe()
            val initiator = TestProbe()
            val data = Data(remoteNode = Some(remoteNode.ref), pending = Some(initiator.ref))
            coordinator.setState(Initiating, data)

            val connection = TestProbe().ref
            initiator.send(coordinator, HandshakeCoordinatorProtocol.Established(connection, config))
            remoteNode.expectMsg(RemoteNodeProtocol.Established(connection, config))
            assert(coordinator.stateName === Up)
            assert(coordinator.stateData === data.copy(pending = None, connection = Some(connection)))
            assert(!coordinator.isStateTimerActive)

            // Test that the initiator is not monitored.
            system.stop(initiator.ref)
            expectNoMsg(10.millis)

            // Test that the connection is monitored.
            system.stop(connection)
            expectTerminated(coordinator)
            ()
          }
        }
      }

      "reject an accepted connection" when {
        "accepting a connection from a lower node" in {
          forAll { (localNodeName: NodeName, remoteNodeName: NodeName) =>
            whenever(localNodeName.asString.compareTo(remoteNodeName.asString) > 0) {
              val coordinator = setup(localNodeName = localNodeName, remoteNodeName = remoteNodeName)
              val data = Data(remoteNode = Some(nullRef), pending = Some(nullRef))
              coordinator.setState(Initiating, data)

              val acceptor = TestProbe()
              val remoteNode = TestProbe().ref
              coordinator ! Accepted(acceptor.ref, remoteNode)
              acceptor.expectMsg(HandshakeAcceptorProtocol.NokPending)
              assert(coordinator.stateName === Initiating)
              assert(coordinator.stateData === data)
            }
          }
        }
      }

      "stop initiating and start accepting" when {
        "accepting a connection from a prioritized node" in {
          forAll { (localNodeName: NodeName, remoteNodeName: NodeName) =>
            whenever(localNodeName.asString.compareTo(remoteNodeName.asString) <= 0) {
              val coordinator = setup(localNodeName = localNodeName, remoteNodeName = remoteNodeName)
              watch(coordinator)
              val initiator = TestProbe()
              watch(initiator.ref)
              val data = Data(remoteNode = Some(nullRef), pending = Some(initiator.ref))
              coordinator.setState(Initiating, data)

              val acceptor = TestProbe()
              val remoteNode = TestProbe().ref
              coordinator ! Accepted(acceptor.ref, remoteNode)
              acceptor.expectMsg(HandshakeAcceptorProtocol.OkPending)
              assert(coordinator.stateName === Accepting)
              assert(coordinator.stateData === data.copy(pending = Some(acceptor.ref)))
              assert(coordinator.isStateTimerActive)
              expectTerminated(initiator.ref)

              // Test that the initiator is not monitored.
              expectNoMsg(shortDuration)

              // Test that the acceptor is monitored.
              system.stop(acceptor.ref)
              expectTerminated(coordinator)
            }
          }
        }
      }
    }

    "Accepting" should {
      "go to Up" when {
        "the handshake has been established" in {
          forAll { config: NodeConfig =>
            val coordinator = setup()
            watch(coordinator)
            val remoteNode = TestProbe()
            val acceptor = TestProbe()
            val data = Data(remoteNode = Some(remoteNode.ref), pending = Some(acceptor.ref))
            coordinator.setState(Accepting, data)

            val connection = TestProbe().ref
            acceptor.send(coordinator, HandshakeCoordinatorProtocol.Established(connection, config))
            remoteNode.expectMsg(RemoteNodeProtocol.Established(connection, config))
            assert(coordinator.stateName === Up)
            val expectedData = Data(remoteNode = Some(remoteNode.ref), connection = Some(connection))
            assert(coordinator.stateData === expectedData)
            assert(!coordinator.isStateTimerActive)

            // Test that the acceptor is not monitored.
            system.stop(acceptor.ref)
            expectNoMsg(shortDuration)

            // Test that the connection is monitored.
            system.stop(connection)
            expectTerminated(coordinator)
            ()
          }
        }
      }
    }

    "Up" when {
      "go to UpPending" when {
        "a new handshake is accepted" in {
          val coordinator = setup()
          val connection = TestProbe().ref
          watch(connection)
          val data = Data(remoteNode = Some(nullRef), connection = Some(connection))
          coordinator.setState(Up, data)

          val acceptor = TestProbe().ref
          val remoteNode = TestProbe().ref
          coordinator ! Accepted(acceptor, remoteNode)
          assert(coordinator.stateName === UpPending)
          assert(coordinator.stateData === data.copy(pending = Some(acceptor)))
          assert(coordinator.isStateTimerActive)
        }
      }

      "stop" when {
        "the established connection terminates" in {
          val coordinator = setup()
          watch(coordinator)
          val connection = TestProbe().ref
          val data = Data(remoteNode = Some(nullRef), connection = Some(connection))
          coordinator.setState(Up, data)

          system.stop(connection)
          expectTerminated(coordinator)
        }
      }
    }

    "UpPending" should {
      "go back to Up" when {
        "the acceptor terminates" in {
          val coordinator = setup()
          val acceptor = TestProbe().ref
          val connection = TestProbe().ref
          val data = Data(Some(nullRef), Some(acceptor), Some(connection))
          coordinator.setState(UpPending, data)

          system.stop(acceptor)
          awaitAssert(coordinator.stateName === Up)
          awaitAssert(coordinator.stateData === data.copy(pending = None))
          awaitAssert(!coordinator.isStateTimerActive)
        }
      }

      "start accepting" when {
        "the established connection terminates" in {
          val coordinator = setup()
          watch(coordinator)
          val acceptor = TestProbe()
          val connection = TestProbe().ref
          val data = Data(Some(nullRef), Some(acceptor.ref), Some(connection))
          coordinator.setState(UpPending, data)

          system.stop(connection)
          acceptor.expectMsg(HandshakeAcceptorProtocol.NodeDown)
          awaitAssert(coordinator.stateName === Accepting)
          assert(coordinator.stateData === data.copy(connection = None))

          // Test that the acceptor is monitored.
          system.stop(acceptor.ref)
          expectTerminated(coordinator)
        }
      }
    }

    implicit val arbState: Arbitrary[State] = Arbitrary {
      Gen.oneOf(Idle, Initiating, Accepting, Up, UpPending)
    }
    implicit val arbData: Arbitrary[Data] = Arbitrary {
      for {
        remoteNode <- Gen.option(TestProbe().ref)
        pending <- Gen.option(TestProbe().ref)
        connection <- Gen.option(TestProbe().ref)
      } yield Data(remoteNode, pending, connection)
    }

    "anytime" should {
      "stop the acceptor" when {
        "an extra handshake is accepted" in {
          forAll { (state: State, data: Data) =>
            whenever(state != Idle && state != Initiating && state != Up) {
              val coordinator = setup()
              coordinator.setState(state, data)

              val acceptor = TestProbe().ref
              watch(acceptor)
              val remoteNode = TestProbe().ref
              coordinator ! Accepted(acceptor, remoteNode)
              expectTerminated(acceptor)
              assert(coordinator.stateName === state)
              assert(coordinator.stateData === data)
            }
          }
        }
      }

      "stop the connection" when {
        "a old handshake is completed" in {
          forAll { (state: State, data: Data, config: NodeConfig) =>
            val coordinator = setup()
            coordinator.setState(state, data)

            val connection = TestProbe()
            coordinator ! HandshakeCoordinatorProtocol.Established(connection.ref, config)
            connection.expectMsg(TcpConnectionProtocol.Close)
            assert(coordinator.stateName === state)
            assert(coordinator.stateData === data)
          }
        }
      }

      "send pending status `false`" when {
        "receiving StillPending" in {
          forAll { (state: State, data: Data) =>
            whenever(state != Initiating) {
              val coordinator = setup()
              coordinator.setState(state, data)

              val initiator = TestProbe()
              coordinator ! StillPending(initiator.ref)
              initiator.expectMsg(IsPending(toBeContinued = false))
              assert(coordinator.stateName === state)
              assert(coordinator.stateData === data)
            }
          }
        }
      }

      "stop with an error" when {
        "it times out" in {
          forAll { (state: State, data: Data) =>
            val coordinator = setup()
            watch(coordinator)
            coordinator.setState(state, data)

            coordinator ! StateTimeout
            expectTerminated(coordinator)
            ()
          }
        }

        "the pending actor terminates" in {
          forAll { (state: State, data: Data) =>
            whenever(state == Initiating || state == Accepting) {
              val coordinator = setup()
              watch(coordinator)
              val pending = TestProbe().ref
              coordinator.setState(state, data.copy(pending = Some(pending)))

              system.stop(pending)
              expectTerminated(coordinator)
            }
          }
        }

        "it receives an unexpected message" in {
          forAll { (state: State, data: Data) =>
            val coordinator = setup()
            watch(coordinator)
            coordinator.setState(state, data)

            coordinator ! "unexpected"
            expectTerminated(coordinator)
            ()
          }
        }
      }
    }

    "terminating" should {
      "stop the pending and close the connection" in {
        forAll { (state: State) =>
          val coordinator = setup()
          watch(coordinator)
          val pending = TestProbe().ref
          val pendingProbe = TestProbe()
          pendingProbe.watch(pending)
          val connection = TestProbe()
          val data = Data(Some(nullRef), Some(pending), Some(connection.ref))
          coordinator.setState(state, data)

          system.stop(coordinator)
          expectTerminated(coordinator)
          pendingProbe.expectTerminated(pending)
          connection.expectMsg(TcpConnectionProtocol.Close)
          ()
        }
      }
    }
  }
}
