package akka.ainterface.remote.transport

import akka.actor.FSM.StateTimeout
import akka.ainterface.remote.transport.TcpConnection.{Data, Disconnecting, Handler, State, Working}
import akka.ainterface.remote.transport.TcpConnectionProtocol.ReadSuccess
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbByteString
import akka.ainterface.util.binary.Binary
import akka.io.Tcp._
import akka.testkit.{TestFSMRef, TestProbe}
import akka.util.ByteString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import scala.concurrent.duration._
import scodec.Codec
import scodec.bits.ByteVector

class TcpConnectionSpec extends ActorSpec {
  private[this] case class Payload(data: Seq[Byte])
  implicit private[this] val codec: Codec[Payload] = {
    import scodec.codecs._
    constant(0)
      .dropLeft(variableSizeBytes(int32, bytes.xmap[Seq[Byte]](_.toSeq, ByteVector(_))))
      .as[Payload]
  }
  implicit private[this] val arbPayload: Arbitrary[Payload] = Arbitrary {
    Arbitrary.arbitrary[Seq[Byte]].map(Payload)
  }
  private[this] val genHandler = arbitrary[Boolean].map(Handler(testActor, codec, _))
  implicit private[this] def arbData: Arbitrary[Data] = Arbitrary {
    for {
      handler <- Gen.option(genHandler)
      buffer <- arbitrary[ByteString]
    } yield Data(handler, buffer)
  }

  private[this] def setup(underlying: TestProbe = TestProbe(),
                          timeout: Option[FiniteDuration] = None,
                          maxBufferSize: Int = Int.MaxValue): TestFSMRef[State, Data, TcpConnection] = {
    TestFSMRef(new TcpConnection(underlying.ref, None, maxBufferSize))
  }

  "TcpConnection" when {
    "starting" should {
      "set working timeout" when {
        "timeout is set" in {
          val underlying = TestProbe()
          val props = TcpConnection.props(underlying.ref, Some(shortDuration), maxBufferSize = 0)
          val connection = system.actorOf(props)
          watch(connection)
          underlying.expectMsg(Close)
          expectTerminated(connection)
        }
      }

      "keep timeout" in {
        val underlying = TestProbe()
        val props = TcpConnection.props(underlying.ref, Some(500.millis), maxBufferSize = 0)
        val connection = system.actorOf(props)
        watch(connection)

        connection ! TcpConnectionProtocol.Write[Byte](1)(scodec.codecs.byte)
        underlying.expectMsg(Write(ByteString(1)))

        underlying.expectMsg(Close)
        expectTerminated(connection)
      }
    }

    "Working" should {
      "send a result to the listener" when {
        "it receives bytes and succeeds in decoding" in {
          forAll { (payload: Payload, extra: ByteString) =>
            val connection = setup()
            connection.setState(stateData = Data(Some(Handler(testActor, codec))))

            connection ! Received(Binary.encode(payload) ++ extra)
            expectMsg(ReadSuccess(payload))
            assert(connection.stateName === Working)
            assert(connection.stateData === Data(buffer = extra))
          }
        }
      }

      "send a result to its listener and keep the current handler" when {
        "it receives bytes and the handler is keeping mode" in {
          forAll { (payload: Payload, extra: ByteString) =>
            val connection = setup()
            val handler = Handler(testActor, codec, keeps = true)
            connection.setState(stateData = Data(Some(handler)))

            connection ! Received(Binary.encode(payload) ++ extra)
            expectMsg(ReadSuccess(payload))
            assert(connection.stateName === Working)
            assert(connection.stateData === Data(Some(handler), extra))
          }
        }
      }

      "continue to decode" when {
        "it receives bytes and needs more bits" in {
          forAll(arbitrary[Payload], arbitrary[ByteString], Gen.posNum[Int]) {
            (payload: Payload, extra: ByteString, groupSize: Int) =>
              val connection = setup()
              val handler = Handler(testActor, codec)
              connection.setState(stateData = Data(Some(handler)))

              val chunks = Binary.encode(payload).grouped(groupSize).toSeq
              chunks.init.foreach { chunk =>
                connection ! Received(chunk)
                assert(connection.stateName === Working)
              }

              connection ! Received(chunks.last ++ extra)
              expectMsg(ReadSuccess(payload))
              assert(connection.stateName === Working)
              assert(connection.stateData === Data(buffer = extra))
          }
        }
      }

      "append bytes to buffer" when {
        "it receives bytes and has no handler" in {
          forAll { (buffer: ByteString, bytes: ByteString) =>
            val connection = setup()
            connection.setState(stateData = Data(buffer = buffer))

            connection ! Received(bytes)
            assert(connection.stateName === Working)
            assert(connection.stateData === Data(buffer = buffer ++ bytes))
          }
        }
      }

      "send a result to its listener" when {
        "it receives a Read with a new listener and succeeds in decoding" in {
          forAll { (payload: Payload, extra: ByteString) =>
            val connection = setup()
            connection.setState(stateData = Data(buffer = Binary.encode(payload) ++ extra))

            val replyTo = TestProbe()
            connection ! TcpConnectionProtocol.Read(replyTo.ref, keeps = false)
            replyTo.expectMsg(ReadSuccess(payload))
            assert(connection.stateName === Working)
            assert(connection.stateData === Data(buffer = extra))
          }
        }
      }

      "send a result to the sender" when {
        "it receives a Read without listener and succeeds in decoding" in {
          forAll { (payload: Payload, extra: ByteString) =>
            val connection = setup()
            connection.setState(stateData = Data(buffer = Binary.encode(payload) ++ extra))

            connection ! TcpConnectionProtocol.Read(keeps = false)
            expectMsg(ReadSuccess(payload))
            assert(connection.stateName === Working)
            assert(connection.stateData === Data(buffer = extra))
          }
        }
      }

      "send a result to its listener and keep the current handler" when {
        "it receives a Read with keeping configuration and succeed in decoding" in {
          forAll { (payload: Payload, extra: ByteString) =>
            val connection = setup()
            connection.setState(stateData = Data(buffer = Binary.encode(payload) ++ extra))

            val replyTo = TestProbe()
            connection ! TcpConnectionProtocol.Read(replyTo.ref, keeps = true)
            replyTo.expectMsg(ReadSuccess(payload))
            assert(connection.stateName === Working)
            val handler = Handler(replyTo.ref, codec, keeps = true)
            assert(connection.stateData === Data(Some(handler), extra))
          }
        }
      }

      "continue to decode" when {
        "it receives a Read and needs more bits" in {
          forAll { payload: Payload =>
            val connection = setup()
            val bytes = Binary.encode(payload).init
            connection.setState(stateData = Data(buffer = bytes))

            connection ! TcpConnectionProtocol.Read(keeps = false)
            expectNoMsg(shortDuration)
            assert(connection.stateName === Working)
            val handler = Handler(testActor, codec, keeps = false)
            assert(connection.stateData === Data(Some(handler), bytes))
          }
        }
      }

      "write data to the underlying connection" when {
        "it receives a Write" in {
          forAll { bytes: ByteString =>
            val underlying = TestProbe()
            val connection = setup(underlying)

            connection ! TcpConnectionProtocol.Write(bytes)
            underlying.expectMsg(Write(bytes))
            assert(connection.stateName === Working)
          }
        }
      }

      "try to disconnect" when {
        "it receives Close" in {
          val underlying = TestProbe()
          val connection = setup(underlying)

          connection ! TcpConnectionProtocol.Close
          underlying.expectMsg(Close)
          assert(connection.stateName === Disconnecting)
        }

        "its listener terminates" in {
          val underlying = TestProbe()
          val listener = TestProbe()
          val connection = setup(underlying)
          connection ! TcpConnectionProtocol.Read[Payload](listener.ref, keeps = true)

          system.stop(listener.ref)
          underlying.expectMsg(Close)
          awaitAssert(connection.stateName === Disconnecting)
        }
      }

      "set timeout" when {
        "receiving SetTimeout" in {
          val underlying = TestProbe()
          val props = TcpConnection.props(underlying.ref, None, maxBufferSize = 0)
          val connection = system.actorOf(props)
          watch(connection)

          connection ! TcpConnectionProtocol.SetTimeout(Some(shortDuration))
          underlying.expectMsg(Close)
          expectTerminated(connection)
        }
      }

      "keep timeout configuration" in {
        val underlying = TestProbe()
        val props = TcpConnection.props(underlying.ref, None, maxBufferSize = 0)
        val connection = system.actorOf(props)
        watch(connection)

        connection ! TcpConnectionProtocol.SetTimeout(Some(shortDuration))

        connection ! TcpConnectionProtocol.Write[Byte](1)(scodec.codecs.byte)
        underlying.expectMsg(Write(ByteString(1)))

        underlying.expectMsg(Close)
        expectTerminated(connection)
      }

      "set infinite timeout" when {
        "receiving SetTimeout without FiniteDuration" in {
          val underlying = TestProbe()
          val props = TcpConnection.props(underlying.ref, Some(shortDuration), maxBufferSize = 0)
          val connection = system.actorOf(props)
          watch(connection)

          connection ! TcpConnectionProtocol.SetTimeout(None)
          expectNoMsg(shortDuration)
        }
      }

      "stop with an error" when {
        "it receives bytes and the new buffer exceeds the limit" in {
          forAll { (data: Data, bytes: ByteString) =>
            val underlying = TestProbe()
            val connection = setup(underlying, maxBufferSize = data.buffer.size + bytes.size - 1)
            watch(connection)
            connection.setState(stateData = data)

            connection ! Received(bytes)
            underlying.expectMsg(Close)
            expectTerminated(connection)
            ()
          }
        }

        "it receives bytes and fails in decoding" in {
          forAll { (payload: Payload) =>
            val underlying = TestProbe()
            val connection = setup(underlying)
            watch(connection)
            connection.setState(stateData = Data(Some(Handler(underlying.ref, codec))))

            val bytes = ByteString(1) ++ Binary.encode(payload)
            connection ! Received(bytes)
            underlying.expectMsg(Close)
            expectTerminated(connection)
            ()
          }
        }

        "it receives a Read and fails in decoding" in {
          forAll { (payload: Payload) =>
            val underlying = TestProbe()
            val connection = setup(underlying)
            watch(connection)
            val bytes = ByteString(1) ++ Binary.encode(payload)
            connection.setState(stateData = Data(buffer = bytes))

            connection ! TcpConnectionProtocol.Read(keeps = false)
            underlying.expectMsg(Close)
            expectTerminated(connection)
            ()
          }
        }
      }
    }

    "Disconnecting" should {
      "stop normally" when {
        "it receives Closed" in {
          val connection = setup()
          connection.setState(Disconnecting)
          watch(connection)

          connection ! Closed
          expectTerminated(connection)
        }
      }

      "stop with an error" when {
        "times out" in {
          val underlying = TestProbe()
          val connection = setup(underlying)
          connection.setState(Disconnecting)
          watch(connection)

          connection ! StateTimeout
          underlying.expectMsg(Close)
          expectTerminated(connection)
        }
      }
    }

    "anytime" should {
      implicit val arbState: Arbitrary[State] = Arbitrary {
        Gen.oneOf(Working, Disconnecting)
      }

      "stop normally" when {
        "its peer closes" in {
          forAll { (state: State, data: Data) =>
            val connection = setup()
            connection.setState(state, data)
            watch(connection)

            connection ! PeerClosed
            expectTerminated(connection)
            ()
          }
        }
      }

      "stop with an error" when {
        "it times out" in {
          forAll { (state: State, data: Data) =>
            val underlying = TestProbe()
            val connection = setup(underlying)
            connection.setState(state, data)
            watch(connection)

            connection ! StateTimeout
            underlying.expectMsg(Close)
            expectTerminated(connection)
            ()
          }
        }

        "the underlying connection closes due to an unexpected reason" in {
          implicit val arbClosed: Arbitrary[ConnectionClosed] = Arbitrary {
            Gen.oneOf(Closed, Aborted, ConfirmedClosed, ErrorClosed("reason"))
          }
          forAll { (state: State, data: Data, closed: ConnectionClosed) =>
            whenever(state != Disconnecting || closed != Closed) {
              val underlying = TestProbe()
              val connection = setup(underlying)
              connection.setState(state, data)
              watch(connection)

              connection ! closed
              underlying.expectMsg(Close)
              expectTerminated(connection)
              ()
            }
          }
        }

        "TCP command fails" in {
          forAll { (state: State, data: Data) =>
            val underlying = TestProbe()
            val connection = setup(underlying)
            connection.setState(state, data)
            watch(connection)

            connection ! CommandFailed(Write.empty)
            underlying.expectMsg(Close)
            expectTerminated(connection)
            ()
          }
        }
      }
    }
  }
}
