package akka.ainterface.remote.handshake

import akka.actor.ActorRef
import akka.actor.FSM.StateTimeout
import akka.ainterface.NodeName
import akka.ainterface.remote.epmd.client.EpmdClientProtocol
import akka.ainterface.remote.handshake.HandshakeCoordinatorProtocol.{IsPending, StillPending}
import akka.ainterface.remote.handshake.HandshakeInitiator._
import akka.ainterface.remote.handshake.HandshakeInitiatorProtocol.Start
import akka.ainterface.remote.transport.TcpConnectionProtocol.{Read, ReadFailure, ReadSuccess, Write}
import akka.ainterface.remote.transport.{TcpClientProtocol, TcpConnectionProtocol}
import akka.ainterface.remote.{DFlags, ErlCookie, NodeConfig}
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.{arbByteString, arbDFlags, arbNodeConfig}
import akka.testkit.{TestFSMRef, TestProbe}
import akka.util.ByteString
import java.net.InetSocketAddress
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import scala.concurrent.duration._
import scodec.Err

class HandshakeInitiatorSpec extends ActorSpec {
  private[this] val localNodeName = NodeName("local", "ainterface")
  private[this] val cookie = ErlCookie("cookie")
  private[this] val myChallenge = 10
  private[this] val remoteNodeName = NodeName("remote", "somewhere")
  private[this] def setup(isHidden: Boolean = false,
                          tcpClient: ActorRef = TestProbe().ref,
                          epmdClient: ActorRef = TestProbe().ref) = {
    val generator = new ChallengeGenerator {
      override def genChallenge(): Int = myChallenge
    }
    TestFSMRef(new HandshakeInitiator(
      localNodeName,
      remoteNodeName,
      cookie,
      generator,
      tcpClient,
      epmdClient,
      isHidden
    ))
  }

  "HandshakeInitiator" when {
    "Idle" should {
      "request for port resolving" when {
        "starting" in {
          val epmdClient = TestProbe()
          val initiator = setup(epmdClient = epmdClient.ref)
          watch(initiator)

          val coordinator = TestProbe().ref
          initiator ! Start(coordinator)
          epmdClient.expectMsg(EpmdClientProtocol.GetPort(initiator, remoteNodeName))
          assert(initiator.stateName === ResolvingPort)
          assert(initiator.stateData === Data(coordinator = Some(coordinator)))

          // Test that the coordinator is monitored.
          system.stop(coordinator)
          expectTerminated(initiator)
        }
      }
    }

    "ResolvingPort" should {
      "connect by TCP" in {
        forAll(Gen.choose(1, 0xffff), arbitrary[Int]) { (port: Int, version: Int) =>
          val tcpClient = TestProbe()
          val initiator = setup(tcpClient = tcpClient.ref)
          val data = Data(coordinator = Some(nullRef))
          initiator.setState(ResolvingPort, data)

          initiator ! EpmdClientProtocol.PortResult(port, version)
          val address = new InetSocketAddress(remoteNodeName.host, port)
          tcpClient.expectMsg(TcpClientProtocol.Connect(initiator, address, 10.seconds))
          assert(initiator.stateName === TcpConnecting)
          assert(initiator.stateData === data.copy(otherVersion = Some(version)))
        }
      }
    }

    "TcpConnecting" should {
      "send name" in {
        forAll(arbitrary[Boolean], Gen.choose(0, 0xffff)) { (isHidden: Boolean, version: Int) =>
          val initiator = setup(isHidden = isHidden)
          val data = Data(coordinator = Some(nullRef), otherVersion = Some(version))
          initiator.setState(TcpConnecting, data)

          val connection = TestProbe()
          initiator ! TcpClientProtocol.Connected(connection.ref)
          val name = Name(
            version,
            makeThisFlags(isHidden, remoteNodeName).value,
            localNodeName.asString
          )
          connection.expectMsg(Write(HandshakeMessage(name)))
          connection.expectMsg(Read[HandshakeMessage[Status]](initiator, keeps = false))
          assert(initiator.stateName === ReceivingStatus)
          assert(initiator.stateData === data.copy(connection = Some(connection.ref)))
        }
      }
    }

    "ReceivingStatus" should {
      "await receiving challenge" when {
        "receiving ok or ok_simultaneous" in {
          Seq(Ok, OkSimultaneous).foreach { status =>
            val connection = TestProbe()
            val initiator = setup()
            val data = Data(
              coordinator = Some(nullRef),
              otherVersion = Some(5),
              connection = Some(connection.ref)
            )
            initiator.setState(ReceivingStatus, data)

            initiator ! ReadSuccess(HandshakeMessage(status))
            connection.expectMsg(Read[HandshakeMessage[Challenge]](initiator, keeps = false))
            assert(initiator.stateName === ReceivingChallenge)
            assert(initiator.stateData === data)
          }
        }
      }

      "request for mediation" when {
        "receiving alive" in {
          val connection = TestProbe()
          val coordinator = TestProbe()
          val initiator = setup()
          val data = Data(
            coordinator = Some(coordinator.ref),
            otherVersion = Some(5),
            connection = Some(connection.ref)
          )
          initiator.setState(ReceivingStatus, data)

          initiator ! ReadSuccess(HandshakeMessage(Alive))
          coordinator.expectMsg(StillPending)
          assert(initiator.stateName === CheckingPending)
          assert(initiator.stateData === data)
        }
      }

      "stop the handshake" when {
        "receiving nok" in {
          val connection = TestProbe()
          val initiator = setup()
          watch(initiator)
          val data = Data(
            coordinator = Some(nullRef),
            otherVersion = Some(5),
            connection = Some(connection.ref)
          )
          initiator.setState(ReceivingStatus, data)

          initiator ! ReadSuccess(HandshakeMessage(Nok))
          connection.expectMsg(TcpConnectionProtocol.Close)
          expectTerminated(initiator)
        }
      }

      "stop with an error" when {
        "receiving not_allowed" in {
          val connection = TestProbe()
          val initiator = setup()
          watch(initiator)
          val data = Data(
            coordinator = Some(nullRef),
            otherVersion = Some(5),
            connection = Some(connection.ref)
          )
          initiator.setState(ReceivingStatus, data)

          initiator ! ReadSuccess(HandshakeMessage(NotAllowed))
          connection.expectMsg(TcpConnectionProtocol.Close)
          expectTerminated(initiator)
        }
      }
    }
  }

  "CheckingPending" should {
    "answer true and read challenge" when {
      "no alive connection" in {
        val initiator = setup()
        val connection = TestProbe()
        val data = Data(
          coordinator = Some(nullRef),
          otherVersion = Some(5),
          connection = Some(connection.ref)
        )
        initiator.setState(CheckingPending, data)

        initiator ! IsPending(toBeContinued = true)
        connection.expectMsg(Write[HandshakeMessage[AliveAnswer]](HandshakeMessage(True)))
        connection.expectMsg(Read[HandshakeMessage[Challenge]](initiator, keeps = false))
        assert(initiator.stateName === ReceivingChallenge)
        assert(initiator.stateData === data)
      }
    }

    "answer false and stop" when {
      "alive connection exists" in {
        val initiator = setup()
        watch(initiator)
        val connection = TestProbe()
        val data = Data(
          coordinator = Some(nullRef),
          otherVersion = Some(5),
          connection = Some(connection.ref)
        )
        initiator.setState(CheckingPending, data)

        initiator ! IsPending(toBeContinued = false)
        connection.expectMsg(Write[HandshakeMessage[AliveAnswer]](HandshakeMessage(False)))
        connection.expectMsg(TcpConnectionProtocol.Close)
        expectTerminated(initiator)
      }
    }
  }

  "ReceivingChallenge" should {
    "send challenge_reply and read challenge_ack" in {
      forAll(Gen.choose(0, 0xffff), arbitrary[DFlags], arbitrary[Int]) {
        (version: Int, otherPreFlags: DFlags, challenge: Int) =>
          whenever(otherPreFlags.acceptsExtendedReferences && otherPreFlags.acceptsExtendedPidsPorts) {
            val initiator = setup()
            val connection = TestProbe()
            val data = Data(
              coordinator = Some(nullRef),
              otherVersion = Some(version),
              connection = Some(connection.ref)
            )
            initiator.setState(ReceivingChallenge, data)

            val challengeMessage = Challenge(
              version,
              otherPreFlags.value,
              challenge,
              remoteNodeName.asString
            )
            initiator ! ReadSuccess(HandshakeMessage(challengeMessage))
            val digest = genDigest(challenge, cookie.value)
            val reply = ChallengeReply(myChallenge, digest)
            connection.expectMsg(Write(HandshakeMessage(reply)))
            connection.expectMsg(Read[HandshakeMessage[ChallengeAck]](initiator, keeps = false))
            assert(initiator.stateName === ReceivingChallengeAck)
            val config = NodeConfig(remoteNodeName, version, otherPreFlags.hide)
            val expectedData = data.copy(
              otherVersion = None,
              remoteConfig = Some(config),
              myChallenge = Some(myChallenge)
            )
            assert(initiator.stateData === expectedData)
          }
      }
    }

    "stop with an error" when {
      "version is invalid" in {
        forAll(Gen.choose(0, 0xffff), arbitrary[Int], arbitrary[Int]) {
          (version: Int, flags: Int, challenge: Int) =>
            whenever(version != 5) {
              val initiator = setup()
              watch(initiator)
              val connection = TestProbe()
              val data = Data(
                coordinator = Some(nullRef),
                otherVersion = Some(5),
                connection = Some(connection.ref)
              )
              initiator.setState(ReceivingChallenge, data)

              val challengeMessage = Challenge(
                version,
                flags,
                challenge,
                remoteNodeName.asString
              )
              initiator ! ReadSuccess(HandshakeMessage(challengeMessage))
              connection.expectMsg(TcpConnectionProtocol.Close)
              expectTerminated(initiator)
              ()
            }
        }
      }

      "name is invalid" in {
        forAll(Gen.choose(0, 0xffff), arbitrary[Int], arbitrary[String]) {
          (flags: Int, challenge: Int, name: String) =>
            whenever(name != remoteNodeName.asString) {
              val initiator = setup()
              watch(initiator)
              val connection = TestProbe()
              val data = Data(
                coordinator = Some(nullRef),
                otherVersion = Some(5),
                connection = Some(connection.ref)
              )
              initiator.setState(ReceivingChallenge, data)

              val challengeMessage = Challenge(
                5,
                flags,
                challenge,
                name
              )
              initiator ! ReadSuccess(HandshakeMessage(challengeMessage))
              connection.expectMsg(TcpConnectionProtocol.Close)
              expectTerminated(initiator)
              ()
            }
        }
      }

      "received flag is not allowed" in {
        forAll(Gen.choose(0, 0xffff), arbitrary[DFlags], arbitrary[Int]) {
          (version: Int, otherPreFlags: DFlags, challenge: Int) =>
            whenever(!otherPreFlags.acceptsExtendedReferences || !otherPreFlags.acceptsExtendedPidsPorts) {
              val initiator = setup()
              watch(initiator)
              val connection = TestProbe()
              val data = Data(
                coordinator = Some(nullRef),
                otherVersion = Some(version),
                connection = Some(connection.ref)
              )
              initiator.setState(ReceivingChallenge, data)

              val challengeMessage = Challenge(
                version,
                otherPreFlags.value,
                challenge,
                remoteNodeName.asString
              )
              initiator ! ReadSuccess(HandshakeMessage(challengeMessage))
              connection.expectMsg(TcpConnectionProtocol.Close)
              expectTerminated(initiator)
              ()
            }
        }
      }
    }
  }

  "ReceivingChallengeAck" should {
    "complete handshake" when {
      "received digest is ok" in {
        forAll { config: NodeConfig =>
          val initiator = setup()
          watch(initiator)
          val coordinator = TestProbe()
          val connection = TestProbe()
          val data = Data(
            coordinator = Some(coordinator.ref),
            connection = Some(connection.ref),
            remoteConfig = Some(config),
            myChallenge = Some(myChallenge)
          )
          initiator.setState(ReceivingChallengeAck, data)

          val digest = genDigest(myChallenge, cookie.value)
          initiator ! ReadSuccess(HandshakeMessage(ChallengeAck(digest)))
          coordinator.expectMsg(HandshakeCoordinatorProtocol.Established(connection.ref, config))
          expectTerminated(initiator)
          connection.expectNoMsg(shortDuration)
        }
      }
    }

    "stop with an error" when {
      "received digest is invalid" in {
        forAll { (config: NodeConfig, digest: ByteString) =>
          val initiator = setup()
          watch(initiator)
          val connection = TestProbe()
          val data = Data(
            coordinator = Some(nullRef),
            connection = Some(connection.ref),
            remoteConfig = Some(config),
            myChallenge = Some(myChallenge)
          )
          initiator.setState(ReceivingChallengeAck, data)

          initiator ! ReadSuccess(HandshakeMessage(ChallengeAck(digest)))
          connection.expectMsg(TcpConnectionProtocol.Close)
          expectTerminated(initiator)
          ()
        }
      }
    }
  }

  implicit private[this] val arbState: Arbitrary[State] = Arbitrary {
    Gen.oneOf(
      ResolvingPort,
      TcpConnecting,
      ReceivingStatus,
      CheckingPending,
      ReceivingChallenge,
      ReceivingChallengeAck
    )
  }
  implicit private[this] val arbConnectionAndData: Arbitrary[(Option[TestProbe], Data)] = Arbitrary {
    for {
      coordinator <- Gen.option(Gen.const(TestProbe().ref))
      version <- Gen.option(arbitrary[Int])
      connection <- Gen.option(Gen.const(TestProbe()))
      config <- Gen.option(arbitrary[NodeConfig])
      challenge <- Gen.option(arbitrary[Int])
    } yield (connection, Data(coordinator, version, connection.map(_.ref), config, challenge))
  }

  "anytime" should {
    "close connection" when {
      "shutdown" in {
        forAll { (state: State, connectionAndData: (Option[TestProbe], Data)) =>
          whenever(connectionAndData._1.isDefined) {
            val (connectionOption, data) = connectionAndData
            val connection = connectionOption.get
            val initiator = setup()
            watch(initiator)
            initiator.setState(state, data)

            system.stop(initiator)
            connection.expectMsg(TcpConnectionProtocol.Close)
            expectTerminated(initiator)
          }
        }
      }
    }

    "stop with an error" when {
      "receiving unexpected packet" in {
        forAll { (state: State, connectionAndData: (Option[TestProbe], Data)) =>
          val (connection, data) = connectionAndData
          val initiator = setup()
          watch(initiator)
          initiator.setState(state, data)

          initiator ! ReadFailure(Err("error"))
          connection.foreach(_.expectMsg(TcpConnectionProtocol.Close))
          expectTerminated(initiator)
          ()
        }
      }

      "coordinator terminates" in {
        val gen = for {
          version <- Gen.option(arbitrary[Int])
          coordinator <- Gen.const(TestProbe())
          connection <- Gen.option(Gen.const(TestProbe()))
          config <- Gen.option(arbitrary[NodeConfig])
          challenge <- Gen.option(arbitrary[Int])
        } yield (
            coordinator,
            connection,
            Data(Some(coordinator.ref), version, connection.map(_.ref), config, challenge)
        )
        forAll(arbitrary[State], gen) {
          (state: State, coordinatorAndConnectionAndData: (TestProbe, Option[TestProbe], Data)) =>
            val (coordinator, connection, data) = coordinatorAndConnectionAndData
            val initiator = setup()
            watch(initiator)
            initiator.setState(state, data)
            initiator.watch(coordinator.ref)

            system.stop(coordinator.ref)
            connection.foreach(_.expectMsg(TcpConnectionProtocol.Close))
            expectTerminated(initiator)
            ()
        }
      }

      "connection terminates" in {
        forAll { (state: State, connectionAndData: (Option[TestProbe], Data)) =>
          whenever(connectionAndData._1.isDefined) {
            val (connectionOption, data) = connectionAndData
            val initiator = setup()
            val connection = connectionOption.get
            initiator.watch(connection.ref)
            watch(initiator)
            initiator.setState(state, data)

            system.stop(connection.ref)
            expectTerminated(initiator)
            ()
          }
        }
      }

      "it times out" in {
        forAll { (state: State, connectionAndData: (Option[TestProbe], Data)) =>
          val (connection, data) = connectionAndData
          val initiator = setup()
          watch(initiator)
          initiator.setState(state, data)

          initiator ! StateTimeout
          connection.foreach(_.expectMsg(TcpConnectionProtocol.Close))
          expectTerminated(initiator)
          ()
        }
      }

      "receiving an unexpected message" in {
        forAll { (state: State, connectionAndData: (Option[TestProbe], Data)) =>
          val (connection, data) = connectionAndData
          val initiator = setup()
          watch(initiator)
          initiator.setState(state, data)

          initiator ! "unexpected"
          connection.foreach(_.expectMsg(TcpConnectionProtocol.Close))
          expectTerminated(initiator)
          ()
        }
      }
    }
  }
}
