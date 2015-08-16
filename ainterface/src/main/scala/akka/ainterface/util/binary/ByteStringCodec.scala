package akka.ainterface.util.binary

import akka.util.ByteString
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

private[ainterface] object ByteStringCodec {
  implicit val byteString: Codec[ByteString] = bytes.xmap[ByteString](
    x => ByteString(x.toArray),
    x => ByteVector(x.toArray)
  )

  def byteString(size: Int): Codec[ByteString] = bytes(size).xmap[ByteString](
    x => ByteString(x.toArray),
    x => ByteVector(x.toArray)
  )
}
