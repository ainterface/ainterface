package akka.ainterface.remote.epmd
package client

import scodec.codecs._
import scodec.{Attempt, Codec, Err}

/**
 * PORT2_RESP.
 */
private[client] sealed abstract class Port2Resp
private[client] final case class Port2RespSuccess(port: Int,
                                                  aliveName: String) extends Port2Resp {
  def version: Int = EpmdHighestVersion
}
private[client] final case class Port2RespFailure(result: Int) extends Port2Resp

private[client] object Port2Resp {
  val successCodec: Codec[Port2RespSuccess] = {
    (constant(119) :: constant(0) :: distributedNodeCodec).dropUnits.as[Port2RespSuccess]
  }
  val failureCodec: Codec[Port2RespFailure] = {
    val resultCodec = uint8.narrow[Int](
      { x => if (x > 0) Attempt.successful(x) else Attempt.failure(Err("successful")) },
      { x => x }
    )
    constant(119).dropLeft(resultCodec).as[Port2RespFailure]
  }

  implicit val codec: Codec[Port2Resp] = {
    choice(failureCodec.upcast[Port2Resp], successCodec.upcast[Port2Resp])
  }
}
