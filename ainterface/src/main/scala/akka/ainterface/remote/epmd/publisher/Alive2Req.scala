package akka.ainterface.remote.epmd
package publisher

import scodec.Codec
import scodec.codecs._

private[publisher] final case class Alive2Req(port: Int, aliveName: String)

private[publisher] object Alive2Req {
  implicit val codec: Codec[Alive2Req] = {
    constant(120).dropLeft(distributedNodeCodec).as[Alive2Req]
  }
}
