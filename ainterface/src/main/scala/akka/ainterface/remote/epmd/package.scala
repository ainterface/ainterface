package akka.ainterface.remote

import scodec.codecs._
import scodec.{Attempt, Codec, Err}
import shapeless._

package object epmd {
  private[epmd] val EpmdPort = 4369
  private[epmd] val EpmdHighestVersion = 5
  private[epmd] val EpmdLowestVersion = 5

  private[this] val EpmdProtocol = 0 // tcp/ip-v4
  private[this] val protocol: Codec[Unit] = int8.narrow(
    {
      case EpmdProtocol => Attempt.successful(())
      case x => Attempt.failure(Err(s"Protocol allows only 0, but $x."))
    },
    { _ => EpmdProtocol }
  )

  private[this] val highestVersion: Codec[Unit] = int16.narrow(
    {
      case EpmdHighestVersion => Attempt.successful(())
      case x => Attempt.failure(Err(s"HighestVersion allows only 5, but $x."))
    },
    { _ => EpmdHighestVersion }
  )

  private[this] val lowestVersion: Codec[Unit] = int16.narrow(
    {
      case EpmdLowestVersion => Attempt.successful(())
      case x => Attempt.failure(Err(s"LowestVersion allows only 5, but $x."))
    },
    { _ => EpmdLowestVersion }
  )

  private[this] val nodeType: Codec[Unit] = int8.xmap(_ => (), _ => 77)

  private[this] val aliveName: Codec[String] = variableSizeBytes(uint16, utf8)

  private[this] val extra: Codec[Unit] = listOfN(uint16, byte).unit(Nil)

  // port :: aliveName :: HNil
  private[epmd] val distributedNodeCodec: Codec[Int :: String :: HNil] = {
    (uint16 :: nodeType :: protocol :: highestVersion :: lowestVersion :: aliveName :: extra).dropUnits
  }
}
