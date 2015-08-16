package akka.ainterface.remote.transport

import akka.actor.ActorRef
import akka.actor.FSM.StateTimeout
import akka.ainterface.remote.transport.TcpConnector.{Connecting, Data, Idle, Starting, State}
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbFiniteDuration
import akka.ainterface.util.actor.DynamicSupervisorProtocol
import akka.io.Tcp.{Connected, Register}
import akka.testkit.{TestFSMRef, TestProbe}
import java.net.InetSocketAddress
import org.scalacheck.{Arbitrary, Gen}
import scala.concurrent.duration.FiniteDuration

class TcpConnectorSpec extends ActorSpec {
  private[this] val local = new InetSocketAddress("localhost", 0)
  private[this] val remote = new InetSocketAddress("localhost", 0)
  private[this] def setup(connectionSupervisor: ActorRef = nullRef): TestFSMRef[State, Data, TcpConnector] = {
    TestFSMRef(new TcpConnector(connectionSupervisor))
  }

  "TcpConnector" when {
    "Connecting" should {
      "start a connection" when {
        "it succeeds in connecting" in {
          forAll { (timeout: Option[FiniteDuration], bufferSize: Int) =>
            val connectionSupervisor = TestProbe()
            val connector = setup(connectionSupervisor.ref)
            connector.setState(Connecting, Data(nullRef, remote, timeout, bufferSize))

            connector ! Connected(remote, local)
            val props = TcpConnection.props(testActor, timeout, bufferSize)
            val expected = DynamicSupervisorProtocol.StartChild(connector, props)
            connectionSupervisor.expectMsg(expected)
            assert(connector.stateName === Starting)
          }
        }
      }
    }

    "Starting" should {
      "send the connection to listener" when {
        "receiving TcpConnection" in {
          forAll { (timeout: Option[FiniteDuration], bufferSize: Int) =>
            val connector = setup()
            watch(connector)
            val listener = TestProbe()
            val akkaConnection = TestProbe()
            val data = Data(listener = Some(listener.ref), akkaConnection = Some(akkaConnection.ref))
            connector.setState(Starting, data)

            val child = TestProbe().ref
            connector ! DynamicSupervisorProtocol.ChildRef(child, None)
            akkaConnection.expectMsg(Register(child))
            listener.expectMsg(TcpClientProtocol.Connected(child))
            expectTerminated(connector)
            ()
          }
        }
      }
    }

    "anytime" should {
      implicit val arbState: Arbitrary[State] = Arbitrary {
        Gen.oneOf(Idle, Connecting)
      }
      implicit val arbData: Arbitrary[Data] = Arbitrary {
        for {
          listener <- Gen.option(nullRef)
          remote <- Gen.option(remote)
          timeout <- Arbitrary.arbitrary[Option[FiniteDuration]]
          maxBufferSize <- Gen.option(Gen.posNum[Int])
          akkaConnection <- Gen.option(nullRef)
        } yield Data(listener, remote, timeout, maxBufferSize, akkaConnection)
      }

      "stop with an error" when {
        "it times out" in {
          forAll { (state: State, data: Data) =>
            val connector = setup()
            watch(connector)
            connector.setState(state, data)

            connector ! StateTimeout
            expectTerminated(connector)
            ()
          }
        }

        "receiving an unknown message" in {
          forAll { (state: State, data: Data) =>
            val connector = setup()
            watch(connector)
            connector.setState(state, data)

            connector ! "unknown"
            expectTerminated(connector)
            ()
          }
        }
      }
    }
  }
}
