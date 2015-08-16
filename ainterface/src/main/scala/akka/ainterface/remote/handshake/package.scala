package akka.ainterface.remote

import akka.actor.ActorRef
import akka.ainterface.NodeName
import akka.ainterface.remote.transport.TcpConnectionProtocol
import akka.ainterface.remote.transport.TcpConnectionProtocol.ReadSuccess
import akka.util.ByteString
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scodec.{Decoder, Encoder}

package object handshake {
  private[handshake] val DigestLength = 16

  private[handshake] def write[A](connection: ActorRef, x: A)
                                 (implicit encoder: Encoder[HandshakeMessage[A]]): Unit = {
    connection ! TcpConnectionProtocol.Write(HandshakeMessage(x))
  }
  private[handshake] def read[A](connection: ActorRef, self: ActorRef)
                                (implicit decoder: Decoder[HandshakeMessage[A]]): Unit = {
    connection ! TcpConnectionProtocol.Read[HandshakeMessage[A]](self, keeps = false)
  }
  private[handshake] object ReadHandshake {
    def unapply(result: ReadSuccess): Option[Any] = result.obj match {
      case HandshakeMessage(x) => Some(x)
      case _ => None
    }
  }

  /**
   * Generates a digest.
   */
  def genDigest(challenge: Int, cookie: String): ByteString = {
    val challengeNumber: Long = challenge & 0xffffffffL
    val challengeString = challengeNumber.toString
    val md5 = MessageDigest.getInstance("MD5")
    md5.update(cookie.getBytes(StandardCharsets.ISO_8859_1))
    md5.update(challengeString.getBytes(StandardCharsets.ISO_8859_1))
    val digest = md5.digest()
    assert(digest.length == DigestLength)
    ByteString(digest)
  }

  /**
   * Currently always hidden.
   */
  private[this] def publishOnNode(node: NodeName): DFlags = DFlags.hidden

  private[handshake] def makeThisFlags(node: NodeName): DFlags = {
    makeThisFlags(isHidden = false, node)
  }

  private[handshake] def makeThisFlags(isHidden: Boolean, remoteNode: NodeName): DFlags = {
    isHidden match {
      case true => DFlags.hidden
      case false => publishOnNode(remoteNode)
    }
  }

  private[handshake] def adjustFlags(thisFlags: DFlags,
                                     otherFlags: DFlags): (DFlags, DFlags) = {
    if (thisFlags.published && otherFlags.published) {
      (thisFlags, otherFlags)
    } else {
      (thisFlags.hide, otherFlags.hide)
    }
  }

  private[handshake] def checkDFlagXnc(otherFlags: DFlags): Boolean = {
    otherFlags.acceptsExtendedReferences && otherFlags.acceptsExtendedPidsPorts
  }

  private[handshake] def isAllowed(otherNodeName: NodeName,
                                   allowed: Option[Set[NodeName]]): Boolean = {
    allowed match {
      case None => true
      case Some(as) => as.contains(otherNodeName)
    }
  }
}
