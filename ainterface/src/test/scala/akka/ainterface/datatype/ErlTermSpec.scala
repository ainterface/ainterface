package akka.ainterface.datatype

import akka.ainterface.test.arbitrary.AinterfaceArbitrary._
import akka.util.ByteString
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class ErlTermSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  private[this] def testToSerialize[A <: ErlTerm : Arbitrary](): Unit = {
    forAll { value: A =>
      val outputStream = new ByteArrayOutputStream()
      new ObjectOutputStream(outputStream).writeObject(value)
      val bytes = outputStream.toByteArray

      val inputStream = new ObjectInputStream(new ByteArrayInputStream(bytes))
      val actual = inputStream.readObject().asInstanceOf[A]
      assert(actual === value)
    }
  }

  "ErlTerm" should {
    "be serializable" in {
      pendingUntilFixed {
        testToSerialize[ErlTerm]()
      }
    }
  }

  "ErlInteger" should {
    "should be serializable" in {
      testToSerialize[ErlInteger]()
    }

    "have a correct value" when {
      "being created from Byte" in {
        forAll { value: Byte =>
          val integer = ErlInteger(value)
          assert(integer.value.toByte === value)
          assert(integer === ErlInteger(BigInt(value)))
        }
      }

      "being created from Short" in {
        forAll { value: Short =>
          val integer = ErlInteger(value)
          assert(integer.value.toShort === value)
          assert(integer === ErlInteger(BigInt(value)))
        }
      }

      "being created from Int" in {
        forAll { value: Int =>
          val integer = ErlInteger(value)
          assert(integer.value.toInt === value)
          assert(integer === ErlInteger(BigInt(value)))
        }
      }

      "being created from Long" in {
        forAll { value: Long =>
          val integer = ErlInteger(value)
          assert(integer.value.toLong === value)
          assert(integer === ErlInteger(BigInt(value)))
        }
      }
    }
  }

  "ErlFloat" should {
    "should be serializable" in {
      testToSerialize[ErlFloat]()
    }

    "have a correct value" when {
      "being created from Float" in {
        forAll { value: Float =>
          val float = ErlFloat(value)
          assert(float.value.toFloat === value)
          assert(float === ErlFloat(value.toDouble))
        }
      }
    }
  }

  "ErlAtom" should {
    "should be serializable" in {
      testToSerialize[ErlAtom]()
    }

    "throws an error" when {
      "the length of parameter exceeds limit" in {
        val gen = Gen.oneOf(
          Arbitrary.arbString.arbitrary,
          Gen.listOfN(255, Arbitrary.arbChar.arbitrary).map(x => x.mkString),
          Gen.listOfN(256, Arbitrary.arbChar.arbitrary).map(x => x.mkString)
        )
        forAll(gen) {
          case value if value.length <= 255 => assert(ErlAtom(value).value === value)
          case value => intercept[IllegalArgumentException] { ErlAtom(value) }
        }
      }
    }
  }

  "ErlBitString" should {
    "be serializable" in {
      // The current ByteString is not serializable, so ErlBitString is not serializable, too.
      pendingUntilFixed {
        testToSerialize[ErlBitString]()
      }
    }

    "have correct values" when {
      "being created without bit-length" in {
        forAll { value: ByteString =>
          val bitString = ErlBitString(value)
          assert(bitString.value === value)
          assert(bitString.bitLength === value.length * 8)
          assert(bitString === ErlBinary(value))
        }
      }

      "being created with the 8 divisible bit-length equal to that of ByteString" in {
        forAll { value: ByteString =>
          val bitString = ErlBitString(value, value.length * 8)
          assert(bitString.value === value)
          assert(bitString.bitLength === value.length * 8)
          assert(bitString === ErlBinary(value))
        }
      }

      "being created with the 8 divisible bit-length shorter than that of ByteString" in {
        val gen = for {
          value <- Arbitrary.arbitrary[ByteString]
          byteLength <- Gen.choose(0, value.length - 1)
        } yield (value, byteLength, byteLength * 8)

        forAll(gen) {
          case (value, byteLength, bitLength) =>
            val bitString = ErlBitString(value, bitLength)
            assert(bitString.value === value.take(byteLength))
            assert(bitString.bitLength === bitLength)
        }
      }

      "being created with the 8 divisible bit-length longer than that of ByteString" in {
        val gen = for {
          value <- Arbitrary.arbitrary[ByteString]
          byteLength <- Gen.choose(value.length + 1, value.length + 100)
        } yield (value, byteLength, byteLength * 8)

        forAll(gen) {
          case (value, byteLength, bitLength) =>
            val bitString = ErlBitString(value, bitLength)
            val builder = ByteString.newBuilder.append(value)
            val expectedValue = (1 to byteLength - value.length).foldLeft(builder) { (b, _) =>
              b.putByte(0)
            }.result()
            assert(bitString.value === expectedValue)
            assert(bitString.value.length === byteLength)
            assert(bitString.bitLength === bitLength)
        }
      }

      "being created with the non 8 divisible bit-length shorter than that of ByteString" in {
        val gen = for {
          value <- Arbitrary.arbitrary[ByteString]
          byteLength <- Gen.choose(1, value.length - 1)
          unusedBits <- Gen.choose(1, 7)
        } yield (value, byteLength, byteLength * 8 - unusedBits)

        forAll(gen) {
          case (value, byteLength, bitLength) =>
            val bitString = ErlBitString(value, bitLength)
            assert(bitString.value === value.take(byteLength))
            assert(bitString.value.length === byteLength)
            assert(bitString.bitLength === bitLength)
        }
      }

      "being created with the non 8 divisible bit-length longer than that of ByteString" in {
        val gen = for {
          value <- Arbitrary.arbitrary[ByteString]
          byteLength <- Gen.choose(value.length + 1, value.length + 100)
          unusedBits <- Gen.choose(1, 7)
        } yield (value, byteLength, byteLength * 8 - unusedBits)

        forAll(gen) {
          case (value, byteLength, bitLength) =>
            val bitString = ErlBitString(value, bitLength)
            val builder = ByteString.newBuilder.append(value)
            val expectedValue = (1 to byteLength - value.length).foldLeft(builder) { (b, _) =>
              b.putByte(0)
            }.result()
            assert(bitString.value === expectedValue)
            assert(bitString.value.length === byteLength)
            assert(bitString.bitLength === bitLength)
        }
      }
    }
  }

  "ErlBinary" should {
    "be serializable" in {
      testToSerialize[ErlBinary]()
    }

    "have a correct bit-length" in {
      forAll { value: ByteString =>
        val binary = ErlBinary(value)
        assert(binary.bitLength === value.length * 8)
      }
    }

    "have a correct value" in {
      forAll { value: ByteString =>
        val binary = ErlBinary(value)
        assert(binary.value === value)
        assert(binary === ErlBitString(value))
      }
    }
  }

  "ErlReference" should {
    "be serializable" in {
      testToSerialize[ErlReference]()
    }
  }

  "ErlNewReference" should {
    "be serializable" in {
      testToSerialize[ErlNewReference]()
    }
  }

  "ErlFun" should {
    "be serializable" in {
      pendingUntilFixed {
        testToSerialize[ErlFun]()
      }
    }
  }

  "ErlNewFun" should {
    "be serializable" in {
      pendingUntilFixed {
        testToSerialize[ErlNewFun]()
      }
    }
  }

  "ErlExternalFun" should {
    "be serializable" in {
      testToSerialize[ErlExternalFun]()
    }
  }

  "ErlPort" should {
    "be serializable" in {
      testToSerialize[ErlPort]()
    }

    "be equal to ErlPort with the same attributes" in {
      forAll { (nodeName: ErlAtom, id: Int, creation: Byte) =>
        val port1 = ErlPort(nodeName, id, creation)
        val port2 = ErlPort(nodeName, id, creation)
        assert(port1.hashCode() === port2.hashCode())
        assert(port1 === port2)
      }
    }

    "be able to be extracted" in {
      forAll { (nodeName: ErlAtom, id: Int, creation: Byte) =>
        val port = ErlPort(nodeName, id, creation)
        val ErlPort(n, i, c) = port
        assert(n === nodeName)
        assert(i === id)
        assert(c === creation)
      }
    }
  }

  "ErlPid" should {
    "be serializable" in {
      testToSerialize[ErlPid]()
    }

    "be equal to ErlPid with the same attributes" in {
      forAll { (nodeName: ErlAtom, id: Int, serial: Int, creation: Byte) =>
        val pid1 = ErlPid(nodeName, id, serial, creation)
        val pid2 = ErlPid(nodeName, id, serial, creation)
        assert(pid1.hashCode() === pid2.hashCode())
        assert(pid1 === pid2)
      }
    }

    "be able to be extracted" in {
      forAll { (nodeName: ErlAtom, id: Int, serial: Int, creation: Byte) =>
        val pid = ErlPid(nodeName, id, serial, creation)
        val ErlPid(n, i, s, c) = pid
        assert(n === nodeName)
        assert(i === id)
        assert(s === serial)
        assert(c === creation)
      }
    }
  }

  "ErlTuple" should {
    "be serializable" in {
      // ErlBitString is not serializable now.
      pendingUntilFixed {
        testToSerialize[ErlTuple]()
      }
    }

    "have a correct size" in {
      forAll { terms: Seq[ErlTerm] =>
        val tuple = ErlTuple(terms: _*)
        assert(tuple.elements === terms)
        assert(tuple.size === terms.size)
      }
    }
  }

  "ErlMap" should {
    "be serializable" in {
      pendingUntilFixed {
        testToSerialize[ErlMap]()
      }
    }
  }

  "ErlList" should {
    "be serializable" in {
      pendingUntilFixed {
        testToSerialize[ErlList]()
      }
    }
  }
}
