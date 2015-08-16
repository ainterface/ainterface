package akka.ainterface.remote.transport

import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Terminated}
import akka.ainterface.remote.transport.TcpConnection.{Data, Disconnecting, Handler, MaxSizeExceeded, State, Working}
import akka.ainterface.util.binary.Binary
import akka.io.Tcp._
import akka.util.ByteString
import scala.concurrent.duration._
import scodec.{Attempt, DecodeResult, Decoder, Encoder, Err}

/**
 * A TCP connection with useful interfaces.
 */
private final class TcpConnection(underlying: ActorRef,
                                  timeout: Option[FiniteDuration],
                                  maxBufferSize: Int) extends LoggingFSM[State, Data] {

  private[this] def checkBufferSize(buffer: ByteString)(f: ByteString => State): State = {
    val length = buffer.length
    if (length > maxBufferSize) {
      log.error("The length of buffer is {}. It exceeds the limit {}.", length, maxBufferSize)
      stop(FSM.Failure(MaxSizeExceeded(length, maxBufferSize)))
    } else {
      f(buffer)
    }
  }

  private[this] def decode(bytes: ByteString, handler: Handler): State = {
    val Handler(listener, decoder, keeps) = handler
    Binary.decode(bytes)(decoder) match {
      case Attempt.Successful(DecodeResult(v, remainder)) =>
        listener ! TcpConnectionProtocol.ReadSuccess(v)
        val newHandler = if (keeps) {
          Some(handler)
        } else {
          context.unwatch(listener)
          None
        }
        stay() using Data(newHandler, ByteString(remainder.toByteArray))
      case Attempt.Failure(_: Err.InsufficientBits) =>
        stay() using Data(Some(handler), bytes)
      case Attempt.Failure(error) =>
        log.debug("Failed decoding {}.", bytes)
        stop(FSM.Failure(error))
    }
  }

  startWith(Working, Data())

  when(Working, stateTimeout = timeout.orNull) {
    case Event(Received(bytes), Data(Some(handler), buffer)) =>
      checkBufferSize(buffer ++ bytes)(decode(_, handler))
    case Event(Received(bytes), Data(None, buffer)) =>
      checkBufferSize(buffer ++ bytes) { newBuffer =>
        stay() using Data(buffer = newBuffer)
      }
    case Event(TcpConnectionProtocol.Read(replyTo, decoder, keeps), Data(handler, buffer)) =>
      handler.map(_.listener).foreach(context.unwatch)
      val listener = replyTo.getOrElse(sender())
      log.debug("{} reads by {}. keeps = {}", listener, decoder, keeps)
      context.watch(listener)
      if (keeps) {
        setStateTimeout(Working, None)
      }
      decode(buffer, Handler(listener, decoder, keeps))
    case Event(TcpConnectionProtocol.Write(bytes), _) =>
      underlying ! Write(bytes)
      stay()
    case Event(TcpConnectionProtocol.SetTimeout(duration), _) =>
      setStateTimeout(Working, duration)
      stay()
    case Event(TcpConnectionProtocol.Close, _) =>
      underlying ! Close
      goto(Disconnecting) using Data()
    case Event(Terminated(ref), Data(Some(Handler(listener, _, _)), _)) if ref == listener =>
      underlying ! Close
      goto(Disconnecting) using Data()
  }

  when(Disconnecting, stateTimeout = TcpConnection.Timeout) {
    case Event(Closed, _) => stop()
    case Event(StateTimeout, _) => stop(FSM.Failure(StateTimeout))
  }

  whenUnhandled {
    case Event(StateTimeout, _) =>
      log.error("TCP connection times out.")
      stop(FSM.Failure(StateTimeout))
    case Event(PeerClosed, _) => stop()
    case Event(closed: ConnectionClosed, _) =>
      log.error("TCP connection is disconnected. {}", closed)
      stop(FSM.Failure(closed))
    case Event(CommandFailed(command), _) =>
      log.error("TCP command fails. {}", command)
      stop(FSM.Failure(command))
    case Event(unexpected, _) =>
      log.warning("Received an unexpected event. {}", unexpected)
      stay()
  }

  onTermination {
    case StopEvent(FSM.Normal, _, _) =>
    case StopEvent(reason, state, data) => underlying ! Close
  }

  initialize()
}

private[remote] object TcpConnection {
  def props(underlying: ActorRef, timeout: Option[FiniteDuration], maxBufferSize: Int): Props = {
    Props(classOf[TcpConnection], underlying, timeout, maxBufferSize)
  }

  private val Timeout = 10.seconds

  private[transport] sealed abstract class State
  private[transport] case object Working extends State
  private[transport] case object Disconnecting extends State

  private final case class MaxSizeExceeded(actual: Int, max: Int)

  private[transport] final case class Handler(listener: ActorRef,
                                              decoder: Decoder[Any],
                                              keeps: Boolean = false)
  private[transport] final case class Data(handler: Option[Handler] = None,
                                           buffer: ByteString = ByteString.empty)
}

private[remote] object TcpConnectionProtocol {

  /**
   * Reads and parses bytes.
   */
  final case class Read(replyTo: Option[ActorRef], decoder: Decoder[Any], keeps: Boolean)

  object Read {
    def apply[A](replyTo: ActorRef, keeps: Boolean)(implicit decoder: Decoder[A]): Read = {
      Read(Some(replyTo), decoder, keeps)
    }

    def apply[A](keeps: Boolean)(implicit decoder: Decoder[A]): Read = {
      Read(None, decoder, keeps)
    }
  }

  sealed abstract class ReadResult
  final case class ReadSuccess(obj: Any)
  final case class ReadFailure(error: Err)

  /**
   * Writes bytes.
   */
  final case class Write(bytes: ByteString)

  object Write {
    def apply[A: Encoder](x: A): Write = Write(Binary.encode(x))
  }

  /**
   * Configures timeout.
   */
  final case class SetTimeout(duration: Option[FiniteDuration])

  /**
   * Disconnects.
   */
  case object Close
}
