package akka.ainterface.remote.epmd.publisher

import scodec.Codec
import scodec.codecs._

private[publisher] sealed abstract class Alive2Resp
private[publisher] case class Alive2RespSuccess(creation: Int) extends Alive2Resp
private[publisher] case class Alive2RespFailure(result: Int, creation: Int) extends Alive2Resp

private[publisher] object Alive2Resp {
  val successCodec: Codec[Alive2RespSuccess] = {
    (constant(121) :: constant(0) :: uint16).dropUnits.as[Alive2RespSuccess]
  }
  val failureCodec: Codec[Alive2RespFailure] = {
    constant(121).dropLeft(uint8 :: uint16).as[Alive2RespFailure]
  }

  implicit val codec: Codec[Alive2Resp] = {
    choice(successCodec.upcast[Alive2Resp], failureCodec.upcast[Alive2Resp])
  }
}
