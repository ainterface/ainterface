package akka.ainterface.remote

import akka.actor.ActorRef
import akka.ainterface.NodeName
import akka.ainterface.remote.Acceptor.{Binding, Idle, Listening, State}
import akka.ainterface.remote.epmd.publisher.EpmdPublisherProtocol
import akka.ainterface.remote.handshake.HandshakeAcceptor
import akka.ainterface.remote.transport.TcpClientProtocol
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbNodeName
import akka.ainterface.util.actor.DynamicSupervisorProtocol
import akka.io.Tcp.{Bind, Bound, CommandFailed, Connected}
import akka.testkit.{TestFSMRef, TestProbe}
import java.net.InetSocketAddress
import org.scalacheck.{Arbitrary, Gen}
import scala.concurrent.duration._

class AcceptorSpec extends ActorSpec {
  private[this] def setup(localNodeName: NodeName = NodeName("local", "ainterface"),
                          auth: Auth = new Auth(NodeName("local", "ainterface"), ErlCookie("cookie")),
                          epmdPublisher: ActorRef = TestProbe().ref,
                          tcpClient: ActorRef = TestProbe().ref,
                          handshakeAcceptorSupervisor: ActorRef = TestProbe().ref,
                          remoteHub: ActorRef = TestProbe().ref) = {
    TestFSMRef(new Acceptor(localNodeName, auth, epmdPublisher, tcpClient, handshakeAcceptorSupervisor, remoteHub))
  }

  "Acceptor" when {
    "Binding" should {
      "publish the port" when {
        "being bound" in {
          forAll(Gen.choose(1, 0xffff)) { port: Int =>
            val epmdPublisher = TestProbe()
            val acceptor = setup(epmdPublisher = epmdPublisher.ref)
            acceptor.setState(Binding)

            acceptor ! Bound(new InetSocketAddress("ainterface", port))
            epmdPublisher.expectMsg(EpmdPublisherProtocol.Publish(port))
            assert(acceptor.stateName === Listening)
          }
        }
      }
    }

    "Listening" should {
      "start HandshakeAcceptor" when {
        "accepting an incoming connection" in {
          forAll { localNodeName: NodeName =>
            val auth = new Auth(localNodeName, ErlCookie("cookie"))
            val tcpClient = TestProbe()
            val handshakeAcceptorSupervisor = TestProbe()
            val remoteHub = TestProbe().ref
            val acceptor = setup(
              localNodeName = localNodeName,
              auth = auth,
              tcpClient = tcpClient.ref,
              handshakeAcceptorSupervisor = handshakeAcceptorSupervisor.ref,
              remoteHub = remoteHub
            )
            acceptor.setState(Listening)

            val remote = new InetSocketAddress("ainterface", 11111)
            val local = new InetSocketAddress("ainterface", 22222)
            acceptor ! Connected(remote, local)

            tcpClient.expectMsg(TcpClientProtocol.Accept(testActor, 10.seconds))
            val connection = TestProbe().ref
            tcpClient.reply(DynamicSupervisorProtocol.ChildRef(connection, None))
            expectMsg(akka.io.Tcp.Register(connection))

            val props = HandshakeAcceptor.props(connection, remoteHub, auth, localNodeName)
            handshakeAcceptorSupervisor.expectMsg(DynamicSupervisorProtocol.StartChild(props))
            assert(acceptor.stateName === Listening)
          }
        }
      }

      "do nothing" when {
        "receiving ChildRef" in {
          val acceptor = setup()
          acceptor.setState(Listening)

          acceptor ! DynamicSupervisorProtocol.ChildRef(TestProbe().ref, None)
          assert(acceptor.stateName === Listening)
        }
      }
    }

    "anytime" should {
      implicit val arbState: Arbitrary[State] = Arbitrary {
        Gen.oneOf(Idle, Binding, Listening)
      }

      "stop" when {
        "failing command" in {
          forAll { state: State =>
            val acceptor = setup()
            watch(acceptor)
            acceptor.setState(state)

            acceptor ! CommandFailed(Bind(TestProbe().ref, new InetSocketAddress("ainterface", 334)))
            expectTerminated(acceptor)
            ()
          }
        }
      }
    }
  }
}
