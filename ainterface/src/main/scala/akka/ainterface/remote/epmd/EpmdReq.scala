package akka.ainterface.remote.epmd

import scodec.Codec
import scodec.codecs._

/**
 * A request for EPMD.
 */
private[epmd] final case class EpmdReq[A: Codec](request: A)

private[epmd] object EpmdReq {
  implicit def codec[A: Codec]: Codec[EpmdReq[A]] = {
    variableSizeBytes[A](uint16, implicitly[Codec[A]]).as[EpmdReq[A]]
  }
}
