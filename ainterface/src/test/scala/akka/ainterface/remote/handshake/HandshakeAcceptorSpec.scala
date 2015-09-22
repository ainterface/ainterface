package akka.ainterface.remote.handshake

import akka.actor.FSM.StateTimeout
import akka.actor.{ActorRef, FSM}
import akka.ainterface.NodeName
import akka.ainterface.remote.handshake.HandshakeAcceptor._
import akka.ainterface.remote.handshake.HandshakeAcceptorProtocol.{NodeDown, NokPending, OkPending, OkStatus, UpPending}
import akka.ainterface.remote.handshake.HandshakeMessageArbitrary._
import akka.ainterface.remote.transport.TcpConnectionProtocol
import akka.ainterface.remote.transport.TcpConnectionProtocol.{Read, ReadFailure, ReadSuccess, Write}
import akka.ainterface.remote.{Auth, DFlags, ErlCookie, NodeConfig, RemoteHubProtocol}
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.{arbNodeConfig, genByteString}
import akka.testkit.{TestFSMRef, TestProbe}
import akka.util.ByteString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import scodec.Err

class HandshakeAcceptorSpec extends ActorSpec {
  private[this] val localNodeName = NodeName("local", "node")
  private[this] val localFlags = DFlags.hidden
  private[this] val cookie = ErlCookie("cookie")
  private[this] val myChallenge = 1
  private[this] def setup(connection: TestProbe = TestProbe(),
                          remoteHub: ActorRef = TestProbe().ref) = {
    val auth = Auth(localNodeName, cookie)
    val generator = new ChallengeGenerator {
      override def genChallenge(): Int = myChallenge
    }
    val ref = TestFSMRef(new HandshakeAcceptor(connection.ref, remoteHub, auth, localNodeName, generator))
    connection.expectMsg(TcpConnectionProtocol.Read[HandshakeMessage[Name]](ref, keeps = false))
    ref
  }

  "HandshakeAcceptor" when {
    "ReceivingName" should {
      "request for mediation" in {
        forAll { name: Name =>
          val flags = DFlags(name.flags).hide
          whenever(flags.acceptsExtendedReferences && flags.acceptsExtendedPidsPorts) {
            val hub = TestProbe()
            val acceptor = setup(remoteHub = hub.ref)
            acceptor.setState(ReceivingName)

            acceptor ! ReadSuccess(HandshakeMessage(name))
            val nodeName = NodeName(name.name)
            hub.expectMsg(RemoteHubProtocol.Accepted(nodeName, acceptor))
            assert(acceptor.stateName === CheckingPending)
            val configuration = NodeConfig(nodeName, name.version, flags)
            val data = Data(Some(localFlags), Some(configuration))
            assert(acceptor.stateData === data)
          }
        }
      }
    }

    "CheckingPending" should {
      "send challenge" when {
        "status is ok" in {
          forAll { config: NodeConfig =>
            val connection = TestProbe()
            val acceptor = setup(connection = connection)
            val data = Data(Some(localFlags), Some(config))
            acceptor.setState(CheckingPending, data)

            acceptor ! OkStatus
            connection.expectMsg(Write(HandshakeMessage[Status](Ok)))
            val challenge = Challenge(config.version, localFlags.value, myChallenge, localNodeName.asString)
            connection.expectMsg(Write(HandshakeMessage(challenge)))
            connection.expectMsg(Read[HandshakeMessage[ChallengeReply]](acceptor, keeps = false))
            assert(acceptor.stateName === ReceivingChallengeReply)
            val newData = Data(Some(localFlags), Some(config), Some(myChallenge), Some(testActor))
            assert(acceptor.stateData === newData)
          }
        }

        "status is ok_simultaneous" in {
          forAll { config: NodeConfig =>
            val connection = TestProbe()
            val acceptor = setup(connection = connection)
            val data = Data(Some(localFlags), Some(config))
            acceptor.setState(CheckingPending, data)

            acceptor ! OkPending
            connection.expectMsg(Write(HandshakeMessage[Status](OkSimultaneous)))
            val challenge = Challenge(config.version, localFlags.value, myChallenge, localNodeName.asString)
            connection.expectMsg(Write(HandshakeMessage(challenge)))
            connection.expectMsg(Read[HandshakeMessage[ChallengeReply]](acceptor, keeps = false))
            assert(acceptor.stateName === ReceivingChallengeReply)
            val newData = Data(Some(localFlags), Some(config), Some(myChallenge), Some(testActor))
            assert(acceptor.stateData === newData)
          }
        }
      }

      "send alive" when {
        "another connection exists" in {
          forAll { config: NodeConfig =>
            val connection = TestProbe()
            val acceptor = setup(connection = connection)
            val data = Data(Some(localFlags), Some(config))
            acceptor.setState(CheckingPending, data)

            acceptor ! UpPending
            connection.expectMsg(Write(HandshakeMessage[Status](Alive)))
            connection.expectMsg(Read[HandshakeMessage[AliveAnswer]](acceptor, keeps = false))
            assert(acceptor.stateName === ReceivingStatus)
          }
        }
      }

      "stop with an error" when {
        "status is nok" in {
          forAll { config: NodeConfig =>
            val connection = TestProbe()
            val acceptor = setup(connection = connection)
            watch(acceptor)
            val data = Data(Some(localFlags), Some(config))
            acceptor.setState(CheckingPending, data)

            acceptor ! NokPending
            connection.expectMsg(Write(HandshakeMessage[Status](Nok)))
            expectTerminated(acceptor)
            ()
          }
        }
      }
    }

    "ReceivingStatus" should {
      "await closing pending node" when {
        "status is true" in {
          val acceptor = setup()
          acceptor.setState(ReceivingStatus)

          acceptor ! ReadSuccess(HandshakeMessage(True))
          assert(acceptor.stateName === AwaitingNodeDown)
        }
      }

      "await receiving status" when {
        "node is down" in {
          val acceptor = setup()
          acceptor.setState(ReceivingStatus)

          acceptor ! NodeDown
          assert(acceptor.stateName === ReceivingStatusAfterNodeDown)
        }
      }

      "stop with an error" when {
        "status is false" in {
          val acceptor = setup()
          watch(acceptor)
          acceptor.setState(ReceivingStatus)

          acceptor ! ReadSuccess(HandshakeMessage(False))
          expectTerminated(acceptor)
        }
      }
    }

    "ReceivingStatusAfterNodeDown" should {
      "send challenge" when {
        "status is true" in {
          forAll { config: NodeConfig =>
            val connection = TestProbe()
            val acceptor = setup(connection = connection)
            val data = Data(Some(localFlags), Some(config))
            acceptor.setState(ReceivingStatusAfterNodeDown, data)

            acceptor ! ReadSuccess(HandshakeMessage(True))
            val challenge = Challenge(config.version, localFlags.value, myChallenge, localNodeName.asString)
            connection.expectMsg(Write(HandshakeMessage(challenge)))
            connection.expectMsg(Read[HandshakeMessage[ChallengeReply]](acceptor, keeps = false))
            assert(acceptor.stateName === ReceivingChallengeReply)
            val newData = Data(Some(localFlags), Some(config), Some(myChallenge), Some(testActor))
            assert(acceptor.stateData === newData)
          }
        }
      }

      "stop with an error" when {
        "status is false" in {
          val acceptor = setup()
          watch(acceptor)
          acceptor.setState(ReceivingStatusAfterNodeDown)

          acceptor ! ReadSuccess(HandshakeMessage(False))
          expectTerminated(acceptor)
        }
      }
    }

    "AwaitingNodeDown" should {
      "send challenge" when {
        "node is down" in {
          forAll { config: NodeConfig =>
            val connection = TestProbe()
            val acceptor = setup(connection = connection)
            val data = Data(Some(localFlags), Some(config))
            acceptor.setState(AwaitingNodeDown, data)

            acceptor ! NodeDown
            val challenge = Challenge(config.version, localFlags.value, myChallenge, localNodeName.asString)
            connection.expectMsg(Write(HandshakeMessage(challenge)))
            connection.expectMsg(Read[HandshakeMessage[ChallengeReply]](acceptor, keeps = false))
            assert(acceptor.stateName === ReceivingChallengeReply)
            val newData = Data(Some(localFlags), Some(config), Some(myChallenge), Some(testActor))
            assert(acceptor.stateData === newData)
          }
        }
      }
    }

    "ReceivingChallengeReply" should {
      "complete the handshake" when {
        "the digest is ok" in {
          forAll { (config: NodeConfig, hisChallenge: Int) =>
            val connection = TestProbe()
            val connectionWatcher = TestProbe()
            connectionWatcher.watch(connection.ref)
            val acceptor = setup(connection = connection)
            watch(acceptor)
            val coordinator = TestProbe()
            val data = Data(Some(localFlags), Some(config), Some(myChallenge), Some(coordinator.ref))
            acceptor.setState(ReceivingChallengeReply, data)

            val reply = ChallengeReply(hisChallenge, genDigest(myChallenge, cookie.value))
            acceptor ! ReadSuccess(HandshakeMessage(reply))
            val ack = ChallengeAck(genDigest(hisChallenge, cookie.value))
            connection.expectMsg(Write(HandshakeMessage(ack)))
            coordinator.expectMsg(HandshakeCoordinatorProtocol.Established(connection.ref, config))
            expectTerminated(acceptor)
            connectionWatcher.expectNoMsg(shortDuration)
          }
        }
      }

      "stop with an error" when {
        "the digest is wrong" in {
          forAll(arbitrary[NodeConfig], arbitrary[Int], genByteString(16)) {
            (config: NodeConfig, hisChallenge: Int, digest: ByteString) =>
              whenever(digest != genDigest(myChallenge, cookie.value)) {
                val acceptor = setup()
                watch(acceptor)
                val coordinator = TestProbe()
                val data = Data(Some(localFlags), Some(config), Some(myChallenge), Some(coordinator.ref))
                acceptor.setState(ReceivingChallengeReply, data)

                val reply = ChallengeReply(hisChallenge, digest)
                acceptor ! ReadSuccess(HandshakeMessage(reply))
                expectTerminated(acceptor)
                ()
              }
          }
        }
      }
    }

    implicit val arbState: Arbitrary[State] = Arbitrary {
      Gen.oneOf(
        ReceivingName,
        CheckingPending,
        ReceivingStatus,
        ReceivingStatusAfterNodeDown,
        AwaitingNodeDown,
        ReceivingChallengeReply
      )
    }
    implicit val arbData: Arbitrary[Data] = Arbitrary {
      for {
        thisFlags <- Gen.option(Gen.const(localFlags))
        otherConfig <- Gen.option(arbitrary[NodeConfig])
        myChallenge <- Gen.option(arbitrary[Int])
        coordinator <- Gen.option(Gen.const(TestProbe().ref))
      } yield Data(thisFlags, otherConfig, myChallenge, coordinator)
    }

    "anytime" should {
      "stop with an error" when {
        "receiving error message through TCP" in {
          forAll { (state: State, data: Data) =>
            val acceptor = setup()
            watch(acceptor)
            acceptor.setState(state, data)

            acceptor ! ReadFailure(Err("error"))
            expectTerminated(acceptor)
            ()
          }
        }

        "receiving unexpected message" in {
          forAll { (state: State, data: Data) =>
            val acceptor = setup()
            watch(acceptor)
            acceptor.setState(state, data)

            acceptor ! "unhandled"
            expectTerminated(acceptor)
            ()
          }
        }

        "connection is closed" in {
          forAll { (state: State, data: Data) =>
            val connection = TestProbe()
            val acceptor = setup(connection = connection)
            watch(acceptor)
            acceptor.setState(state, data)

            system.stop(connection.ref)
            expectTerminated(acceptor)
            ()
          }
        }

        "it times out" in {
          forAll { (state: State, data: Data) =>
            val acceptor = setup()
            watch(acceptor)
            acceptor.setState(state, data)

            acceptor ! StateTimeout
            expectTerminated(acceptor)
            ()
          }
        }
      }
    }

    "terminating" should {
      "close the connection" when {
        "stop by an error" in {
          forAll { (state: State, data: Data) =>
            val connection = TestProbe()
            val acceptor = setup(connection = connection)
            watch(acceptor)
            acceptor.setState(state, data)

            acceptor ! FSM.Failure(())
            expectTerminated(acceptor)
            connection.expectMsg(TcpConnectionProtocol.Close)
            ()
          }
        }
      }
    }
  }
}
