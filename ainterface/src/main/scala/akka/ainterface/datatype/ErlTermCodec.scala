package akka.ainterface.datatype

import akka.ainterface.util.binary.ByteStringCodec.byteString
import akka.util.ByteString
import java.nio.charset.StandardCharsets
import scodec.Attempt.{failure, successful}
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Codec, DecodeResult, Err}
import shapeless.{HNil, ::}

/**
 * Defines external term formats for ErlTerms.
 * @see [[http://erlang.org/doc/apps/erts/erl_ext_dist.html]]
 */
private[ainterface] object ErlTermCodec {
  // 2 bits
  private[this] val creationCodec: Codec[Byte] = byte.xmap[Byte](x => (x & 0x03).toByte, x => x)

  // Some external formats contains unsigned 4 bytes integers that means the size of following terms.
  // However, it is unrealistic to build collections that has size more than `Int.MaxValue`.
  // `_uint32` is the Codec to express such integers.
  private[this] val _uint32: Codec[Int] = int32

  private[this] val smallIntCodec: Codec[Int] = (constant(97) :: uint8).dropUnits.xmap(
    { case x :: HNil => x },
    { case x => x :: HNil }
  )
  private[this] val intCodec: Codec[Int] = constant(98).dropLeft(int32)

  implicit private[this] val integerCodec: Codec[ErlInteger] = {
    val smallIntegerCodec: Codec[ErlInteger] = smallIntCodec.widenOpt[ErlInteger](
      { x => ErlInteger(x) },
      { case ErlInteger(x) => if (x.isValidInt) Some(x.toInt) else None }
    )
    val integerCodec: Codec[ErlInteger] = intCodec.widenOpt[ErlInteger](
      { x => ErlInteger(x) },
      { case ErlInteger(x) => if (x.isValidInt) Some(x.toInt) else None }
    )
    val sign: Codec[Boolean] = uint8.narrow(
      {
        case 0 => successful(true)
        case 1 => successful(false)
        case x => failure(Err(s"$x is invalid for sign."))
      },
      { x => if (x) 0 else 1 }
    )
    def toBigInt: Int :: Boolean :: ByteVector :: HNil => BigInt = {
      case n :: isPositive :: d :: HNil =>
        val value = BigInt(signum = 1, magnitude = d.toArray.reverse)
        if (isPositive) value else - value
    }
    def toBytes: BigInt => Int :: Boolean :: ByteVector :: HNil = { int =>
      val d = {
        val bytes = ByteVector(int.abs.toByteArray.reverse)
        // A BigInt has one sign bit, but the external format does not requires it.
        if (bytes.last == 0) bytes.init else bytes
      }
      val n = d.length
      n :: (int >= 0) :: d :: HNil
    }
    val smallBigCodec: Codec[ErlInteger] = {
      constant(110)
        .dropLeft(uint8)
        .flatPrepend(n => sign :: bytes(n))
        .xmap[BigInt](toBigInt, toBytes)
        .as[ErlInteger]
    }
    val largeBigCodec: Codec[ErlInteger] = {
      constant(111)
        .dropLeft(_uint32)
        .flatPrepend(n => sign :: bytes(n))
        .xmap[BigInt](toBigInt, toBytes)
        .as[ErlInteger]
    }
    choice(smallIntegerCodec, integerCodec, smallBigCodec, largeBigCodec)
  }

  implicit private[this] val floatCodec: Codec[ErlFloat] = {
    (constant(70) :: double).dropUnits.as[ErlFloat]
  }

  implicit private[this] val atomCodec: Codec[ErlAtom] = {
    constant(100).dropLeft(variableSizeBytes(uint16, string(StandardCharsets.ISO_8859_1))).as[ErlAtom]
  }

  implicit private[this] val bitStringCodec: Codec[ErlBitStringImpl] = {
    val sizeWithBits: Codec[Int] = _uint32.xmap[Int](x => x + 1, x => x - 1)
    constant(77).dropLeft(variableSizeBytes(sizeWithBits, int8 :: byteString)).xmap[ErlBitStringImpl](
      {
        case bits :: data :: HNil =>
          ErlBitStringImpl(data, (data.size - 1) * java.lang.Byte.SIZE + bits)
      },
      {
        case ErlBitStringImpl(data, bitLength) =>
          (bitLength - (data.size - 1) * java.lang.Byte.SIZE) :: data :: HNil
      }
    )
  }

  implicit private[this] val binaryCodec: Codec[ErlBinary] = {
    constant(109).dropLeft(variableSizeBytes(_uint32, byteString)).as[ErlBinary]
  }

  implicit private[this] val newReferenceCodec: Codec[ErlNewReference] = {
    (constant(114) :: uint16).dropUnits.narrow[Unit](
      {
        case 3 :: HNil => successful(())
        case x :: HNil => failure(Err(s"The latest version accepts only 3 ids, but actual is $x."))
      },
      { _ => 3 :: HNil }
    ).dropLeft[ErlNewReference] {
      (atomCodec :: creationCodec :: int32 :: int32 :: int32).xmap[ErlNewReference](
        {
          case nodeName :: creation :: id1 :: id2 :: id3 :: HNil =>
            ErlNewReference(nodeName, (id1 & 0x3ffff, id2, id3), creation) // id1 is 18 bits
        },
        {
          case ErlNewReference(nodeName, (id1, id2, id3), creation) =>
            nodeName :: creation :: id1 :: id2 :: id3 :: HNil
        }
      )
    }
  }

  implicit private[this] val newFunCodec: Codec[ErlNewFun] = lazily {
    type Payload = Int :: ByteString :: Int :: Int :: ErlAtom :: Int :: Int :: ErlPid :: List[ErlTerm] :: HNil
    val oldInt: Codec[Int] = choice(smallIntCodec, intCodec)
    val payloadCodec: Codec[Payload] = {
      (uint8 :: byteString(16) :: int32 :: _uint32 :: atomCodec :: oldInt :: oldInt :: pidCodec).flatAppend {
        case arity :: uniq :: index :: numFree :: module :: oldIndex :: oldUniq :: pid :: HNil =>
          listOfN(provide(numFree), codec)
      }
    }

    (constant(112).dropLeft(_uint32) >>~ { size => bits((size - 4) * java.lang.Byte.SIZE) }).exmap[Payload](
      {
        case (size, bitVector) =>
          payloadCodec.decode(bitVector).flatMap {
            case DecodeResult(payload, BitVector.empty) => successful(payload)
            case DecodeResult(_, _) => failure(Err("Extra bits remains."))
          }
      },
      payloadCodec.encode(_).map { bitVector =>
        ((bitVector.size / java.lang.Byte.SIZE + 4).toInt, bitVector)
      }
    ).xmap[ErlNewFun](
      {
        case arity :: uniq :: index :: numFree :: module :: oldIndex :: oldUniq :: pid :: freeVars :: HNil =>
          ErlNewFun(arity, uniq, index, module, oldIndex, oldUniq, pid, freeVars)
      },
      {
        case ErlNewFun(arity, uniq, index, module, oldIndex, oldUniq, pid, freeVars) =>
          arity :: uniq :: index :: freeVars.size :: module :: oldIndex :: oldUniq :: pid :: freeVars :: HNil
      }
    )
  }

  implicit private[this] val externalFunCodec: Codec[ErlExternalFun] = {
    (constant(113) :: atomCodec :: atomCodec :: smallIntCodec).dropUnits.as[ErlExternalFun]
  }

  implicit private[this] val portCodec: Codec[ErlPort] = {
    (constant(102) :: atomCodec :: int32 :: creationCodec).dropUnits.as[ErlPort]
  }

  implicit private[this] val pidCodec: Codec[ErlPid] = {
    (constant(103) :: atomCodec :: int32 :: int32 :: creationCodec).dropUnits.xmap[ErlPid](
      {
        case nodeName :: id :: serial :: creation :: HNil =>
          ErlPid(nodeName, id & ErlPid.MaxId, serial & ErlPid.MaxSerial, creation)
      },
      { case ErlPid(nodeName, id, serial, creation) => nodeName :: id :: serial :: creation :: HNil }
    )
  }

  implicit private[ainterface] val tupleCodec: Codec[ErlTuple] = lazily {
    val encode: ErlTuple => List[ErlTerm] = tuple => tuple.elements.toList
    val decode: List[ErlTerm] => ErlTuple = xs => ErlTuple(xs: _*)
    val smallCodec: Codec[ErlTuple] = listOfN(constant(104).dropLeft(uint8), codec).xmap(decode, encode)
    val largeCodec: Codec[ErlTuple] = listOfN(constant(105).dropLeft(_uint32), codec).xmap(decode, encode)
    choice(smallCodec, largeCodec)
  }

  implicit private[this] val mapCodec: Codec[ErlMap] = lazily {
    listOfN(constant(116).dropLeft(_uint32), codec.pairedWith(codec)).xmap[Map[ErlTerm, ErlTerm]](
      kvs => Map(kvs: _*),
      x => x.toList
    ).as[ErlMap]
  }

  implicit private[this] val listCodec: Codec[ErlList] = lazily {
    val nilCodec: Codec[ErlList] = constant(106).widenOpt(
      { _ => ErlList.empty },
      {
        case ErlList(Nil) => Some(())
        case _ => None
      }
    )
    val stringCodec: Codec[ErlList] = listOfN(constant(107).dropLeft(uint16), uint8.widenOpt[ErlTerm](
      { x => ErlInteger(x) },
      {
        case ErlInteger(v) if v >= 0 && v <= 255 => Some(v.toInt)
        case _ => None
      }
    )).as[ErlList]
    val listCodec: Codec[ErlList] = (listOfN(constant(108).dropLeft(_uint32), codec) :: codec).narrow[ErlList](
      {
        case elements :: ErlList(Nil) :: HNil => successful(ErlList(elements))
        case _ :: _ :: HNil => failure(Err("This is an improper list."))
      },
      { case ErlList(elements) => elements :: ErlList.empty :: HNil }
    )
    choice(nilCodec, stringCodec, listCodec)
  }

  implicit private[this] val improperListCodec: Codec[ErlImproperList] = lazily {
    (listOfN(constant(108).dropLeft(_uint32), codec) :: codec).narrow[ErlImproperList](
      {
        case _ :: ErlList(Nil) :: HNil => failure(Err("This is a proper list."))
        case elements :: tail :: HNil => successful(ErlImproperList(elements, tail))
      },
      { case ErlImproperList(elements, tail) => elements :: tail :: HNil }
    )
  }

  implicit val codec: Codec[ErlTerm] = lazily(Codec.coproduct[ErlTerm].choice)
}
