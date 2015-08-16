package akka.ainterface.remote.epmd.publisher

import akka.actor.ActorRef
import akka.actor.FSM.StateTimeout
import akka.ainterface.NodeName
import akka.ainterface.local.LocalNode
import akka.ainterface.remote.epmd.EpmdReq
import akka.ainterface.remote.epmd.publisher.EpmdPublisher.{Data, Establishing, Idle, ReceivingResult, Registered, State}
import akka.ainterface.remote.epmd.publisher.EpmdPublisherProtocol.Publish
import akka.ainterface.remote.transport.{TcpClientProtocol, TcpConnectionProtocol}
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbNodeName
import akka.testkit.{TestFSMRef, TestProbe}
import java.net.{InetAddress, InetSocketAddress}
import org.mockito.Mockito._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.mock.MockitoSugar
import scala.concurrent.duration._
import scodec.Err

class EpmdPublisherSpec extends ActorSpec with MockitoSugar {
  private[this] val Timeout = 10.seconds
  private[this] val EpmdAddress = new InetSocketAddress(InetAddress.getLoopbackAddress, 4369)
  private[this] def setup(localNode: LocalNode = null, tcpClient: ActorRef = nullRef) = {
    TestFSMRef(new EpmdPublisher(localNode, tcpClient))
  }

  "EpmdPublisher" when {
    "Idle" should {
      "start a handshake" when {
        "Publish event is accepted" in {
          forAll { acceptorPort: Int =>
            val tcpClient = TestProbe()
            val publisher = setup(tcpClient = tcpClient.ref)

            publisher ! Publish(acceptorPort)
            tcpClient.expectMsg(TcpClientProtocol.Connect(publisher, EpmdAddress, Timeout))
            assert(publisher.stateName === Establishing)
            assert(publisher.stateData === Data(Some(acceptorPort)))
            assert(publisher.isStateTimerActive)
          }
        }
      }
    }

    "Establishing" should {
      "send ALIVE2_REQ" when {
        "being connected" in {
          forAll(Gen.chooseNum(0, 0xffff), Arbitrary.arbitrary[NodeName]) {
            (acceptorPort: Int, nodeName: NodeName) =>
              val localNode = mock[LocalNode]
              when(localNode.nodeName).thenReturn(nodeName)
              val publisher = setup(localNode = localNode)
              publisher.setState(Establishing, Data(Some(acceptorPort)))

              val connection = TestProbe()
              publisher ! TcpClientProtocol.Connected(connection.ref)
              val alive = nodeName.alive
              val request = EpmdReq(Alive2Req(acceptorPort, alive))
              connection.expectMsg(TcpConnectionProtocol.Write(request))
              val read = TcpConnectionProtocol.Read[Alive2Resp](publisher, keeps = false)
              connection.expectMsg(read)

              assert(publisher.stateName === ReceivingResult)
              assert(publisher.stateData === Data(Some(acceptorPort), Some(connection.ref)))
              assert(publisher.isStateTimerActive)
          }
        }
      }

      "change the acceptor port" when {
        "being republished" in {
          forAll { (oldPort: Int, newPort: Int) =>
            val publisher = setup()
            publisher.setState(Establishing, Data(Some(oldPort)))

            publisher ! Publish(newPort)
            assert(publisher.stateName === Establishing)
            assert(publisher.stateData === Data(Some(newPort)))
            assert(publisher.isStateTimerActive)
          }
        }
      }
    }

    "ReceivingResult" should {
      "update the local node" when {
        "PublishSuccessful event is accepted" in {
          forAll { creation: Int =>
            val localNode = mock[LocalNode]
            val publisher = setup(localNode = localNode)
            val connection = TestProbe()
            publisher.setState(ReceivingResult, Data(connection = Some(connection.ref)))

            val readSuccess = TcpConnectionProtocol.ReadSuccess(Alive2RespSuccess(creation))
            connection.send(publisher, readSuccess)
            verify(localNode).updateCreation((creation & 0x03).toByte)
            connection.expectMsg(TcpConnectionProtocol.SetTimeout(None))
            assert(publisher.stateName === Registered)
            assert(!publisher.isStateTimerActive)
          }
        }
      }

      "stop with an error" when {
        "EPMD sends a failure response" in {
          val publisher = setup()
          watch(publisher)
          publisher.setState(ReceivingResult, Data(connection = Some(nullRef)))

          publisher ! TcpConnectionProtocol.ReadSuccess(Alive2RespFailure(1, 1))
          expectTerminated(publisher)
        }

        "it fails receiving a response" in {
          val publisher = setup()
          watch(publisher)
          publisher.setState(ReceivingResult, Data(connection = Some(nullRef)))

          publisher ! TcpConnectionProtocol.ReadFailure(Err(""))
          expectTerminated(publisher)
        }
      }
    }

    "anytime" should {
      implicit val arbState: Arbitrary[State] = Arbitrary {
        Gen.oneOf(Idle, Establishing, ReceivingResult, Registered)
      }
      implicit val arbData: Arbitrary[Data] = Arbitrary {
        for {
          acceptorPort <- Gen.option(Gen.chooseNum(0, 0xffff))
          connection <- Gen.option(Gen.const(nullRef))
        } yield Data(acceptorPort, connection)
      }

      "republish" when {
        "it already has a connection and receives Publish" in {
          forAll { (state: State, newPort: Int) =>
            whenever(state != Idle && state != Establishing) {
              val tcpClient = TestProbe()
              val publisher = setup(tcpClient = tcpClient.ref)
              val connection = TestProbe()
              publisher.setState(state, Data(connection = Some(connection.ref)))

              publisher ! Publish(newPort)
              connection.expectMsg(TcpConnectionProtocol.Close)
              val connect = TcpClientProtocol.Connect(publisher, EpmdAddress, Timeout)
              tcpClient.expectMsg(connect)
              assert(publisher.stateName === Establishing)
              assert(publisher.stateData === Data(Some(newPort)))
              assert(publisher.isStateTimerActive)
            }
          }
        }
      }

      "stop with an error" when {
        "it times out" in {
          forAll { (state: State, data: Data) =>
            val publisher = setup()
            watch(publisher)
            publisher.setState(state, data)

            publisher ! StateTimeout
            expectTerminated(publisher)
            ()
          }
        }
      }
    }
  }
}
