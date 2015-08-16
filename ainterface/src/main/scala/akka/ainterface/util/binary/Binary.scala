package akka.ainterface.util.binary

import akka.util.ByteString
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult, Decoder, Encoder}

private[ainterface] object Binary {
  def encode[A: Encoder](v: A): ByteString = Encoder.encode(v).fold(
    { error => sys.error(s"Encoding fails, $error") }, // Encodings should always succeed.
    { bits => ByteString(bits.toByteArray)}
  )

  def decode[A: Decoder](bytes: ByteString): Attempt[DecodeResult[A]] = {
    Decoder.decode(BitVector(bytes))
  }
}
