package akka.ainterface.test.arbitrary

import akka.ainterface.NodeName
import akka.ainterface.datatype._
import akka.util.ByteString
import java.nio.charset.StandardCharsets
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}

trait ErlTermArbitrary extends ByteStringArbitrary with StringArbitrary {

  implicit val arbNodeName: Arbitrary[NodeName] = Arbitrary {
    for {
      length <- Gen.choose(3, ErlAtom.AllowableLength)
      at <- Gen.choose(1, length - 2)
      name <- Gen.listOfN(length, Gen.alphaNumChar).map(_.mkString)
    } yield NodeName(name.updated(at, '@'))
  }

  private[this] val genCreation: Gen[Byte] = Gen.choose[Byte](0, 3)

  private[this] def genErlTerm(level: Int): Gen[ErlTerm] = {
    def genScalar: Gen[ErlTerm] = Gen.oneOf(
      arbitrary[ErlInteger],
      arbitrary[ErlFloat],
      arbitrary[ErlAtom],
      arbitrary[ErlBitString],
      arbitrary[ErlBitStringImpl],
      arbitrary[ErlBinary],
      arbitrary[ErlReference],
      arbitrary[ErlNewReference],
      arbitrary[ErlExternalFun],
      arbitrary[ErlPort],
      arbitrary[ErlPid]
    )

    def genCollection(level: Int): Gen[ErlTerm] = Gen.oneOf(
      genErlFun(level),
      genErlNewFun(level),
      genErlTuple(level),
      genErlMap(level),
      genErlList(level),
      genErlImproperList(level)
    )

    level match {
      case 2 => genScalar
      case x => Gen.frequency(50 -> genScalar, 1 -> genCollection(x + 1))
    }
  }

  implicit lazy val arbErlTerm: Arbitrary[ErlTerm] = Arbitrary(genErlTerm(0))

  implicit lazy val arbErlInteger: Arbitrary[ErlInteger] = {
    Arbitrary(arbitrary[BigInt].map(ErlInteger.apply))
  }

  implicit lazy val arbErlFloat: Arbitrary[ErlFloat] = Arbitrary(arbitrary[Double].map(ErlFloat))

  implicit lazy val arbErlAtom: Arbitrary[ErlAtom] = Arbitrary {
    for {
      len <- Gen.choose(0, ErlAtom.AllowableLength)
      characters <- Gen.frequency(
        1 -> Gen.listOfN(len, Gen.choose(Byte.MinValue, Byte.MaxValue)).map(_.toArray),
        5 -> arbReadableString.arbitrary.map(_.getBytes(StandardCharsets.ISO_8859_1))
      )
    } yield ErlAtom(new String(characters, StandardCharsets.ISO_8859_1))
  }

  implicit lazy val arbErlBitString: Arbitrary[ErlBitString] = Arbitrary {
    val randomGen = for {
      bytes <- arbitrary[ByteString]
      bitLength <- Gen.choose(0, bytes.length * 8)
    } yield ErlBitString(bytes, bitLength)
    Gen.oneOf(randomGen, arbErlBitStringImpl.arbitrary, arbErlBinary.arbitrary)
  }

  implicit lazy val arbErlBitStringImpl: Arbitrary[ErlBitStringImpl] = Arbitrary {
    for {
      bytes <- genNonEmptyByteString
      bits <- Gen.choose(1, 7)
    } yield ErlBitStringImpl.create(bytes, bits)
  }

  implicit lazy val arbErlBinary: Arbitrary[ErlBinary] = Arbitrary {
    arbitrary[ByteString].map(ErlBinary)
  }

  implicit lazy val arbErlReference: Arbitrary[ErlReference] = Arbitrary(arbitrary[ErlNewReference])

  implicit lazy val arbErlNewReference: Arbitrary[ErlNewReference] = Arbitrary {
    for {
      nodeName <- arbitrary[ErlAtom]
      id1 <- Gen.choose(0, ErlNewReference.MaxId1)
      id2 <- arbitrary[Int]
      id3 <- arbitrary[Int]
      creation <- genCreation
    } yield ErlNewReference(nodeName, (id1, id2, id3), creation)
  }

  private[ainterface] def genErlFun(level: Int): Gen[ErlFun] = {
    Gen.oneOf(genErlNewFun(level), arbitrary[ErlExternalFun])
  }

  implicit lazy val arbErlFun: Arbitrary[ErlFun] = Arbitrary(genErlFun(0))

  private[ainterface] def genErlNewFun(level: Int): Gen[ErlNewFun] = {
    for {
      arity <- Gen.oneOf(0, 255)
      uniq <- Gen.listOfN(16, arbitrary[Byte]).map(x => ByteString(x.toArray))
      index <- arbitrary[Int]
      module <- arbitrary[ErlAtom]
      oldIndex <- arbitrary[Int]
      oldUniq <- arbitrary[Int]
      pid <- arbitrary[ErlPid]
      freeVars <- Gen.listOf(genErlTerm(level))
    } yield ErlNewFun(arity, uniq, index, module, oldIndex, oldUniq, pid, freeVars)
  }

  implicit lazy val arbErlNewFun: Arbitrary[ErlNewFun] = Arbitrary(genErlNewFun(0))

  implicit lazy val arbErlExternalFun: Arbitrary[ErlExternalFun] = Arbitrary {
    for {
      module <- arbitrary[ErlAtom]
      function <- arbitrary[ErlAtom]
      arity <- Gen.oneOf(0, 255)
    } yield ErlExternalFun(module, function, arity)
  }

  implicit lazy val arbErlPort: Arbitrary[ErlPort] = Arbitrary {
    for {
      nodeName <- arbitrary[ErlAtom]
      id <- arbitrary[Int]
      creation <- genCreation
    } yield ErlPort(nodeName, id, creation)
  }

  implicit lazy val arbErlPid: Arbitrary[ErlPid] = Arbitrary {
    for {
      nodeName <- arbitrary[NodeName]
      id <- Gen.choose(0, ErlPid.MaxId)
      serial <- Gen.choose(0, ErlPid.MaxSerial)
      creation <- genCreation
    } yield ErlPid(nodeName.asErlAtom, id, serial, creation)
  }

  private[ainterface] def genErlTuple(level: Int): Gen[ErlTuple] = {
    Gen.listOf(genErlTerm(level)).map(ErlTuple.apply)
  }

  implicit lazy val arbErlTuple: Arbitrary[ErlTuple] = Arbitrary(genErlTuple(0))

  private[ainterface] def genErlMap(level: Int): Gen[ErlMap] = {
    val genKV = for {
      k <- genErlTerm(level)
      v <- genErlTerm(level)
    } yield (k, v)
    Gen.mapOf(genKV).map(ErlMap)
  }

  implicit lazy val arbErlMap: Arbitrary[ErlMap] = Arbitrary(genErlMap(0))

  private[ainterface] def genErlList(level: Int): Gen[ErlList] = {
    Gen.frequency(
      1 -> Gen.const(ErlList.empty),
      10 -> Gen.listOf(genErlTerm(level)).map(ErlList.apply)
    )
  }

  implicit lazy val arbErlList: Arbitrary[ErlList] = Arbitrary(genErlList(0))

  private[ainterface] def genErlImproperList(level: Int): Gen[ErlImproperList] = {
    for {
      elements <- Gen.listOf(genErlTerm(level))
      tail <- genErlTerm(level)
      if tail != ErlList.empty
    } yield ErlImproperList(elements, tail)
  }

  implicit lazy val arbErlImproperList: Arbitrary[ErlImproperList] = Arbitrary {
    genErlImproperList(0)
  }
}
