package akka.ainterface.datatype

import akka.ainterface.test.arbitrary.AinterfaceArbitrary._
import akka.util.ByteString
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scala.util.Random
import scodec.Attempt.Successful
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult}

class ErlTermCodecSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  implicit private[this] val config = PropertyCheckConfig(
    minSuccessful = 20,
    maxSize = 5
  )

  private[this] def genSignAndPositiveNum(numBytes: Int, min: BigInt): Gen[(Boolean, BigInt, BigInt)] = {
    def genPositiveNum(numBytes: Int, min: BigInt): Gen[BigInt] = {
      Gen.frequency(
        10 -> Gen.wrap(BigInt(numBytes * 8, Random)).filter(_ >= min),
        1 -> Gen.const(BigInt(signum = 1, magnitude = Array.fill[Byte](numBytes)(-1))),
        1 -> Gen.const(min)
      )
    }
    for {
      isPositive <- Arbitrary.arbBool.arbitrary
      positiveNum <- genPositiveNum(numBytes, min)
      num = if (isPositive) positiveNum else - positiveNum
      if num != BigInt(Int.MinValue)
    } yield (isPositive, positiveNum, num)
  }
  private[this] def bitsOf(list: List[ErlTerm]): BitVector = {
    list.foldLeft(BitVector.empty) { (acc, elem) => acc ++ ErlTermCodec.codec.encode(elem).require }
  }

  "ErlTermCodec.codec" when {
    "anytime" should {
      "be able to do round-trip conversion" in {
        forAll { term: ErlTerm =>
          val actual = ErlTermCodec.codec.decode(ErlTermCodec.codec.encode(term).require)
          val expected = Attempt.Successful(DecodeResult(term, BitVector.empty))
          assert(actual === expected)
        }
      }
    }

    "the term is an integer" should {
      "encode" when {
        "the term is a small int" in {
          forAll(Gen.choose(0, 255).map(ErlInteger.apply)) { term: ErlInteger =>
            val actual = ErlTermCodec.codec.encode(term)
            val expected = Successful(BitVector(97, term.value.toByte))
            assert(actual === expected)
          }
        }

        "the term is a integer" in {
          val gen = Gen.oneOf(
            Gen.choose(Int.MinValue, -1),
            Gen.choose(256, Int.MaxValue)
          ).map(ErlInteger.apply)
          forAll(gen) { term: ErlInteger =>
            val actual = ErlTermCodec.codec.encode(term)
            val expected = Successful(BitVector(98) ++ BitVector.fromInt(term.value.toInt))
            assert(actual === expected)
          }
        }

        "the term is a small big" in {
          forAll(genSignAndPositiveNum(255, BigInt(Int.MaxValue) + 1)) {
            case (isPositive, positiveNum, num) =>
              val erlInteger = ErlInteger(num)
              val actual = ErlTermCodec.codec.encode(erlInteger)
              val sign: Byte = if (isPositive) 0 else 1
              val bytes = positiveNum.toByteArray.dropWhile(_ == 0).reverse
              val n = bytes.length
              val expected = Successful(BitVector[Byte](110, n.toByte, sign) ++ BitVector(bytes))
              assert(actual === expected)
          }
        }

        "the term is a large big" in {
          forAll(genSignAndPositiveNum(512, BigInt(1) << 8 * 255)) {
            case (isPositive, positiveNum, num)  =>
              val erlInteger = ErlInteger(num)
              val actual = ErlTermCodec.codec.encode(erlInteger)
              val sign: Byte = if (isPositive) 0 else 1
              val bytes = positiveNum.toByteArray.dropWhile(_ == 0).reverse
              val n = bytes.length
              val header = BitVector.fromByte(111) ++ BitVector.fromInt(n) ++ BitVector.fromByte(sign)
              val expected = Successful(header ++ BitVector(bytes))
              assert(actual === expected)
          }
        }
      }

      "decode" when {
        "the term is a small int" in {
          forAll(Gen.choose(0, 255)) { value: Int =>
            val actual = ErlTermCodec.codec.decode(BitVector(97, value))
            val expected = Successful(DecodeResult(ErlInteger(value), BitVector.empty))
            assert(actual === expected)
          }
        }

        "the term is a integer" in {
          forAll(Gen.choose(Int.MinValue, Int.MaxValue)) { value: Int =>
            val actual = ErlTermCodec.codec.decode(BitVector(98) ++ BitVector.fromInt(value))
            val expected = Successful(DecodeResult(ErlInteger(value), BitVector.empty))
            assert(actual === expected)
          }
        }

        "the term is a small big" in {
          forAll(genSignAndPositiveNum(255, 0)) {
            case (isPositive, positiveNum, num) =>
              val sign: Byte = if (isPositive) 0 else 1
              val bytes = positiveNum.toByteArray.dropWhile(_ == 0).reverse
              val n = bytes.length
              val bits = BitVector[Byte](110, n.toByte, sign) ++ BitVector(bytes)
              val actual = ErlTermCodec.codec.decode(bits)
              val expected = Successful(DecodeResult(ErlInteger(num), BitVector.empty))
              assert(actual === expected)
          }
        }

        "the term is a large big" in {
          forAll(genSignAndPositiveNum(512, 0)) {
            case (isPositive, positiveNum, num) =>
              val sign: Byte = if (isPositive) 0 else 1
              val bytes = positiveNum.toByteArray.dropWhile(_ == 0).reverse
              val n = bytes.length
              val header = BitVector.fromByte(111) ++ BitVector.fromInt(n) ++ BitVector.fromByte(sign)
              val actual = ErlTermCodec.codec.decode(header ++ BitVector(bytes))
              val expected = Successful(DecodeResult(ErlInteger(num), BitVector.empty))
              assert(actual === expected)
          }
        }
      }
    }

    "the term is a float" should {
      "encode" in {
        forAll { float: ErlFloat =>
          val actual = ErlTermCodec.codec.encode(float)
          val bytes = ByteBuffer.allocate(8).putDouble(float.value)
          bytes.flip()
          val expected = Successful(BitVector(70) ++ BitVector(bytes))
          assert(actual === expected)
        }
      }

      "decode" in {
        forAll { value: Double =>
          val bytes = ByteBuffer.allocate(8).putDouble(value)
          bytes.flip()
          val actual = ErlTermCodec.codec.decode(BitVector(70) ++ BitVector(bytes))
          val expected = Successful(DecodeResult(ErlFloat(value), BitVector.empty))
          assert(actual === expected)
        }
      }
    }

    "the term is an atom" should {
      "encode" in {
        forAll { atom: ErlAtom =>
          val actual = ErlTermCodec.codec.encode(atom)
          val characters = atom.value.getBytes(StandardCharsets.ISO_8859_1)
          val len = BitVector.fromInt(characters.length, size = 16)
          val expected = Successful(BitVector(100) ++ len ++ BitVector(characters))
          assert(actual === expected)
        }
      }

      "decode" in {
        val gen = for {
          len <- Gen.choose(0, 255)
          characters <- Gen.listOfN(len, Gen.choose(Byte.MinValue, Byte.MaxValue)).map(_.toArray)
        } yield (len, characters)
        forAll(gen) {
          case (len, characters) =>
            val bits = BitVector(100) ++ BitVector.fromInt(len, size = 16) ++ BitVector(characters)
            val actual = ErlTermCodec.codec.decode(bits)
            val latin1 = new String(characters, StandardCharsets.ISO_8859_1)
            val expected = Successful(DecodeResult(ErlAtom(latin1), BitVector.empty))
            assert(actual === expected)
        }
      }
    }

    "the term is a bit string" should {
      "encode" in {
        forAll { bitString: ErlBitStringImpl =>
          val actual = ErlTermCodec.codec.encode(bitString)
          val len = BitVector.fromInt(bitString.value.length)
          val bits = ErlBitString.bitsOf(bitString.bitLength)
          val header = BitVector(77) ++ len ++ BitVector.fromByte(bits)
          val expected = Successful(header ++ BitVector(bitString.value))
          assert(actual === expected)
        }
      }

      "decode" in {
        val gen = for {
          bits <- Gen.choose(1, 7)
          bytes <- Arbitrary.arbitrary[Array[Byte]]
          if bytes.nonEmpty
        } yield (bits, bytes)
        forAll(gen) {
          case (bits, bytes) =>
            val len = BitVector.fromInt(bytes.length)
            val header = BitVector(77) ++ len ++ BitVector.fromInt(bits, size = 8)
            val actual = ErlTermCodec.codec.decode(header ++ BitVector(bytes))
            val term = ErlBitStringImpl.create(ByteString(bytes), bits)
            val expected = Successful(DecodeResult(term, BitVector.empty))
            assert(actual === expected)
        }
      }
    }

    "the term is a binary" should {
      "encode" in {
        forAll { binary: ErlBinary =>
          val actual = ErlTermCodec.codec.encode(binary)
          val len = BitVector.fromInt(binary.value.length)
          val expected = Successful(BitVector(109) ++  len ++ BitVector(binary.value))
          assert(actual === expected)
        }
      }

      "decode" in {
        forAll { value: Array[Byte] =>
          val len = BitVector.fromInt(value.length)
          val actual = ErlTermCodec.codec.decode(BitVector(109) ++ len ++ BitVector(value))
          val expected = Successful(DecodeResult(ErlBinary(ByteString(value)), BitVector.empty))
          assert(actual === expected)
        }
      }
    }

    "the term is a reference" should {
      "encode" in {
        forAll { reference: ErlNewReference =>
          val actual = ErlTermCodec.codec.encode(reference)
          val len = BitVector.fromInt(3, size = 16)
          val Successful(node) = ErlTermCodec.codec.encode(reference.nodeName)
          val creation = BitVector(reference.creation)
          val id = reference.id
          val ids = BitVector.fromInt(id._1) ++ BitVector.fromInt(id._2) ++ BitVector.fromInt(id._3)
          val expected = Successful(BitVector(114) ++ len ++ node ++ creation ++ ids)
          assert(actual === expected)
        }
      }

      "decode" when {
        forAll { (nodeName: ErlAtom, creation: Byte, id: (Int, Int, Int)) =>
          val len = BitVector.fromShort(3, size = 16)
          val Successful(node) = ErlTermCodec.codec.encode(nodeName)
          val ids = BitVector.fromInt(id._1) ++ BitVector.fromInt(id._2) ++ BitVector.fromInt(id._3)
          val bits = BitVector(114) ++ len ++ node ++ BitVector(creation) ++ ids
          val actual = ErlTermCodec.codec.decode(bits)
          val reference = ErlNewReference(
            nodeName,
            (id._1 & 0x3ffff, id._2, id._3),
            (creation & 0x03).toByte
          )
          val expected = Successful(DecodeResult(reference, BitVector.empty))
          assert(actual === expected)
        }
      }
    }

    "the term is an internal function" should {
      "encode" in {
        forAll { fun: ErlNewFun =>
          val actual = ErlTermCodec.codec.encode(fun)
          val arity = BitVector.fromInt(fun.arity, size = 8)
          val uniq = BitVector(fun.uniq)
          val index = BitVector.fromLong(fun.index, size = 32)
          val numFree = BitVector.fromInt(fun.freeVars.size)
          val Successful(module) = ErlTermCodec.codec.encode(fun.module)
          val Successful(oldIndex) = ErlTermCodec.codec.encode(ErlInteger(fun.oldIndex))
          val Successful(oldUniq) = ErlTermCodec.codec.encode(ErlInteger(fun.oldUniq))
          val Successful(pid) = ErlTermCodec.codec.encode(fun.pid)
          val freeVars = fun.freeVars.foldLeft(BitVector.empty) { (acc, freeVar) =>
            acc ++ ErlTermCodec.codec.encode(freeVar).require
          }
          val payload: BitVector = arity ++ uniq ++ index ++ numFree ++ module ++ oldIndex ++ oldUniq ++ pid ++ freeVars

          val size = BitVector.fromInt(4 + payload.bytes.size)
          val expected = Successful(BitVector(112) ++ size ++ payload)
          assert(actual === expected)
        }
      }

      "decode" in {
        val gen = for {
          arity <- Gen.oneOf(0, 255)
          uniq <- Gen.listOfN(16, Arbitrary.arbitrary[Byte]).map(x => ByteString(x.toArray))
          index <- Arbitrary.arbitrary[Int]
          module <- arbErlAtom.arbitrary
          oldIndex <- Arbitrary.arbitrary[Int]
          oldUniq <- Arbitrary.arbitrary[Int]
          pid <- arbErlPid.arbitrary
          freeVars <- Gen.listOf(arbErlTerm.arbitrary)
        } yield (arity, uniq, index, module, oldIndex, oldUniq, pid, freeVars)
        forAll(gen) {
          case (arity, uniq, index, module, oldIndex, oldUniq, pid, freeVars) =>
            val arityBits = BitVector.fromInt(arity, size = 8)
            val uniqBits = BitVector(uniq)
            val indexBits = BitVector.fromLong(index, size = 32)
            val numFree = BitVector.fromInt(freeVars.size)
            val Successful(moduleBits) = ErlTermCodec.codec.encode(module)
            val Successful(oldIndexBits) = ErlTermCodec.codec.encode(ErlInteger(oldIndex))
            val Successful(oldUniqBits) = ErlTermCodec.codec.encode(ErlInteger(oldUniq))
            val Successful(pidBits) = ErlTermCodec.codec.encode(pid)
            val freeVarsBits = freeVars.foldLeft(BitVector.empty) { (acc, freeVar) =>
              acc ++ ErlTermCodec.codec.encode(freeVar).require
            }
            val payload: BitVector = arityBits ++ uniqBits ++ indexBits ++ numFree ++ moduleBits ++ oldIndexBits ++ oldUniqBits ++ pidBits ++ freeVarsBits
            val size = BitVector.fromInt(4 + payload.bytes.size)
            val actual = ErlTermCodec.codec.decode(BitVector(112) ++ size ++ payload)
            val fun = ErlNewFun(arity, uniq, index, module, oldIndex, oldUniq, pid, freeVars)
            assert(actual === Successful(DecodeResult(fun, BitVector.empty)))
        }
      }
    }

    "the term is an external function" should {
      "encode" in {
        forAll { fun: ErlExternalFun =>
          val actual = ErlTermCodec.codec.encode(fun)
          val Successful(module) = ErlTermCodec.codec.encode(fun.module)
          val Successful(function) = ErlTermCodec.codec.encode(fun.function)
          val Successful(arity) = ErlTermCodec.codec.encode(ErlInteger(fun.arity))
          val expected = Successful(BitVector(113) ++ module ++ function ++ arity)
          assert(actual === expected)
        }
      }

      "decode" in {
        forAll { (module: ErlAtom, function: ErlAtom, _arity: Byte) =>
          val arity = _arity & 0xff
          val Successful(moduleBits) = ErlTermCodec.codec.encode(module)
          val Successful(functionBits) = ErlTermCodec.codec.encode(function)
          val Successful(arityBits) = ErlTermCodec.codec.encode(ErlInteger(arity))
          val bits = BitVector(113) ++ moduleBits ++ functionBits ++ arityBits
          val actual = ErlTermCodec.codec.decode(bits)
          val fun = ErlExternalFun(module, function, arity)
          assert(actual === Successful(DecodeResult(fun, BitVector.empty)))
        }
      }
    }

    "the term is a port" should {
      "encode" in {
        forAll { port: ErlPort =>
          val actual = ErlTermCodec.codec.encode(port)
          val Successful(node) = ErlTermCodec.codec.encode(port.nodeName)
          val id = BitVector.fromInt(port.id)
          val creation = BitVector(port.creation)
          val expected = Successful(BitVector(102) ++ node ++ id ++ creation)
          assert(actual === expected)
        }
      }

      "decode" in {
        forAll { (nodeName: ErlAtom, id: Int, creation: Byte) =>
          val Successful(node) = ErlTermCodec.codec.encode(nodeName)
          val bits = BitVector(102) ++ node ++ BitVector.fromInt(id) ++ BitVector(creation)
          val actual = ErlTermCodec.codec.decode(bits)
          val port = ErlPort(nodeName, id, (creation & 0x03).toByte)
          val expected = Successful(DecodeResult(port, BitVector.empty))
          assert(actual === expected)
        }
      }
    }

    "the term is a pid" should {
      "encode" in {
        forAll { pid: ErlPid =>
          val actual = ErlTermCodec.codec.encode(pid)
          val Successful(node) = ErlTermCodec.codec.encode(pid.nodeName)
          val id = BitVector.fromInt(pid.id)
          val serial = BitVector.fromInt(pid.serial)
          val creation = BitVector(pid.creation)
          val expected = Successful(BitVector(103) ++ node ++ id ++ serial ++ creation)
          assert(actual === expected)
        }
      }

      "decode" in {
        forAll { (nodeName: ErlAtom, id: Int, serial: Int, creation: Byte) =>
          val Successful(node) = ErlTermCodec.codec.encode(nodeName)
          val bits = BitVector(103) ++ node ++ BitVector.fromInt(id) ++ BitVector.fromInt(serial) ++ BitVector(creation)
          val actual = ErlTermCodec.codec.decode(bits)
          val pid = ErlPid(nodeName, id & 0x7fff, serial & 0x1fff, (creation & 0x03).toByte)
          val expected = Successful(DecodeResult(pid, BitVector.empty))
          assert(actual === expected)
        }
      }
    }

    "the term is a tuple" should {
      "encode" when {
        "the tuple is a small tuple" in {
          val gen = for {
            arity <- Gen.choose(0, 255)
            elements <- Gen.listOfN(arity, arbErlTerm.arbitrary)
          } yield ErlTuple(elements: _*)
          forAll(gen) { tuple: ErlTuple =>
            val actual = ErlTermCodec.codec.encode(tuple)
            val elements = tuple.elements.foldLeft(BitVector.empty) { (acc, element) =>
              acc ++ ErlTermCodec.codec.encode(element).require
            }
            val bits = BitVector(104) ++ BitVector.fromInt(tuple.size, size = 8) ++ elements
            assert(actual === Successful(bits))
          }
        }

        "the tuple is a large tuple" in {
          val gen = for {
            arity <- Gen.choose(256, 512)
            elements <- Gen.listOfN(arity, arbErlTerm.arbitrary)
          } yield ErlTuple(elements: _*)
          forAll(gen) { tuple: ErlTuple =>
            val actual = ErlTermCodec.codec.encode(tuple)
            val elements = tuple.elements.foldLeft(BitVector.empty) { (acc, element) =>
              acc ++ ErlTermCodec.codec.encode(element).require
            }
            val bits = BitVector(105) ++ BitVector.fromInt(tuple.size) ++ elements
            assert(actual === Successful(bits))
          }
        }
      }

      "decode" when {
        "the tuple is a small tuple" in {
          val gen = for {
            arity <- Gen.choose(0, 255)
            elements <- Gen.listOfN(arity, arbErlTerm.arbitrary)
          } yield elements
          forAll(gen) { elements: List[ErlTerm] =>
            val elementsBits = elements.foldLeft(BitVector.empty) { (acc, element) =>
              acc ++ ErlTermCodec.codec.encode(element).require
            }
            val bits = BitVector(104) ++ BitVector.fromInt(elements.size, size = 8) ++ elementsBits
            val actual = ErlTermCodec.codec.decode(bits)
            val expected = Successful(DecodeResult(ErlTuple(elements: _*), BitVector.empty))
            assert(actual === expected)
          }
        }

        "the tuple is a large tuple" in {
          val gen = for {
            arity <- Gen.choose(256, 512)
            elements <- Gen.listOfN(arity, arbErlTerm.arbitrary)
          } yield elements
          forAll(gen) { elements: List[ErlTerm] =>
            val elementsBits = elements.foldLeft(BitVector.empty) { (acc, element) =>
              acc ++ ErlTermCodec.codec.encode(element).require
            }
            val bits = BitVector(105) ++ BitVector.fromInt(elements.size) ++ elementsBits
            val actual = ErlTermCodec.codec.decode(bits)
            val expected = Successful(DecodeResult(ErlTuple(elements: _*), BitVector.empty))
            assert(actual === expected)
          }
        }
      }
    }

    "the term is a map" should {
      "encode" in {
        forAll { map: ErlMap =>
          val actual = ErlTermCodec.codec.encode(map)
          val arity = BitVector.fromInt(map.value.size)
          val pairs = map.value.foldLeft(BitVector.empty) {
            case (acc, (k, v)) =>
              acc ++ ErlTermCodec.codec.encode(k).require ++ ErlTermCodec.codec.encode(v).require
          }
          val expected = Successful(BitVector(116) ++ arity ++ pairs)
          assert(actual === expected)
        }
      }

      "decode" in {
        forAll { value: Map[ErlTerm, ErlTerm] =>
          val arity = BitVector.fromInt(value.size)
          val pairs = value.foldLeft(BitVector.empty) {
            case (acc, (k, v)) =>
              acc ++ ErlTermCodec.codec.encode(k).require ++ ErlTermCodec.codec.encode(v).require
          }
          val bits = BitVector(116) ++ arity ++ pairs
          val actual = ErlTermCodec.codec.decode(bits)
          val expected = Successful(DecodeResult(ErlMap(value), BitVector.empty))
          assert(actual === expected)
        }
      }
    }

    "the term is a list" should {
      "encode" when {
        "the list is []" in {
          val actual = ErlTermCodec.codec.encode(ErlList(Nil))
          val expected = Successful(BitVector(106))
          assert(actual === expected)
        }

        "the list is a string" in {
          val gen = for {
            length <- Gen.choose(1, 65535)
            characters <- Gen.listOfN(length, Arbitrary.arbByte.arbitrary)
            integers = characters.map(_ & 0xff).map(ErlInteger.apply)
          } yield (characters, ErlList(integers))
          forAll(gen) {
            case (characters, list: ErlList) =>
              val actual = ErlTermCodec.codec.encode(list)
              val length = BitVector.fromInt(list.value.size, size = 16)
              val expected = Successful(BitVector(107) ++ length ++ BitVector(characters))
              assert(actual === expected)
          }
        }

        "the list is a long string" in {
          val gen = for {
            length <- Gen.choose(65536, 131072)
            characters <- Gen.listOfN(length, Arbitrary.arbByte.arbitrary)
            integers = characters.map(_ & 0xff).map(ErlInteger.apply)
          } yield ErlList(integers)
          forAll(gen) { list: ErlList =>
            val actual = ErlTermCodec.codec.encode(list)
            val length = BitVector.fromInt(list.value.size)
            val elements = list.value.foldLeft(BitVector.empty) { (acc, element) =>
              acc ++ ErlTermCodec.codec.encode(element).require
            }
            val expected = Successful(BitVector(108) ++ length ++ elements ++ BitVector(106))
            assert(actual === expected)
          }
        }

        "the list is neither nil nor string" in {
          forAll { list: ErlList =>
            val isString = list.value.forall {
              case ErlInteger(v) if v >= 0 && v <= 255 => true
              case _ => false
            }
            whenever(list.value.nonEmpty && !isString) {
              val actual = ErlTermCodec.codec.encode(list)
              val length = BitVector.fromInt(list.value.size)
              val elements = list.value.foldLeft(BitVector.empty) { (acc, element) =>
                acc ++ ErlTermCodec.codec.encode(element).require
              }
              val expected = Successful(BitVector(108) ++ length ++ elements ++ BitVector(106))
              assert(actual === expected)
            }
          }
        }
      }

      "decode" when {
        "the list is []" in {
          val actual = ErlTermCodec.codec.decode(BitVector(106))
          val expected = Successful(DecodeResult(ErlList.empty, BitVector.empty))
          assert(actual === expected)
        }

        "the list is a string" in {
          val gen = for {
            length <- Gen.choose(1, 65535)
            characters <- Gen.listOfN(length, Arbitrary.arbByte.arbitrary)
          } yield characters
          forAll(gen) { characters: List[Byte] =>
            val length = BitVector.fromInt(characters.size, size = 16)
            val bits = BitVector(107) ++ length ++ BitVector(characters)
            val actual = ErlTermCodec.codec.decode(bits)
            val list = ErlList(characters.map(_ & 0xff).map(ErlInteger.apply))
            val expected = Successful(DecodeResult(list, BitVector.empty))
            assert(actual === expected)
          }
        }

        "the list is neither nil nor string" in {
          forAll { value: List[ErlTerm] =>
            val isString = value.forall {
              case ErlInteger(v) if v >= 0 && v <= 255 => true
              case _ => false
            }
            whenever(value.nonEmpty && !isString) {
              val length = BitVector.fromInt(value.size)
              val elements = value.foldLeft(BitVector.empty) { (acc, element) =>
                acc ++ ErlTermCodec.codec.encode(element).require
              }
              val bits = BitVector(108) ++ length ++ elements ++ BitVector(106)
              val actual = ErlTermCodec.codec.decode(bits)
              val expected = Successful(DecodeResult(ErlList(value), BitVector.empty))
              assert(actual === expected)
            }
          }
        }
      }
    }

    "the term is an improper list" should {
      "encode" in {
        forAll { list: ErlImproperList =>
          val actual = ErlTermCodec.codec.encode(list)
          val length = BitVector.fromInt(list.elements.size)
          val elements = bitsOf(list.elements)
          val Successful(tail) = ErlTermCodec.codec.encode(list.tail)
          val expected = Successful(BitVector(108) ++ length ++ elements ++ tail)
          assert(actual === expected)
        }
      }

      "decode" in {
        forAll { (elements: List[ErlTerm], tail: ErlTerm) =>
          whenever(tail != ErlList.empty) {
            val length = BitVector.fromInt(elements.size)
            val Successful(tailBits) = ErlTermCodec.codec.encode(tail)
            val bits = BitVector(108) ++ length ++ bitsOf(elements) ++ tailBits
            val actual = ErlTermCodec.codec.decode(bits)
            val expected = Successful(DecodeResult(ErlImproperList(elements, tail), BitVector.empty))
            assert(actual === expected)
          }
        }
      }
    }
  }
}
