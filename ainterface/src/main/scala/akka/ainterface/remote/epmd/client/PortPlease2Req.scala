package akka.ainterface.remote.epmd.client

import java.nio.charset.StandardCharsets
import scodec.Codec
import scodec.codecs._

/**
 * PORT_PLEASE2_REQ.
 */
private[client] final case class PortPlease2Req(aliveName: String)

private[client] object PortPlease2Req {
  implicit val codec: Codec[PortPlease2Req] = {
    constant(122).dropLeft(string(StandardCharsets.UTF_8)).as[PortPlease2Req]
  }
}
