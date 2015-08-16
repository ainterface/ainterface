package akka.ainterface.local

import akka.ainterface.datatype.ErlNewReference
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbErlNewReference
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class ReferenceGeneratorSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "ReferenceGenerator" when {
    "its id1 is not the max value" should {
      "increment the id1" in {
        forAll(Gen.chooseNum(0, 0x3ffff - 1), arbitrary[ErlNewReference]) {
          (id1: Int, reference: ErlNewReference) =>
            val zero = reference.copy(id = reference.id.copy(_1 = id1))
            val generator = new ReferenceGenerator(zero)

            assert(generator.generate() === zero)
            val next = zero.copy(id = zero.id.copy(_1 = id1 + 1))
            assert(generator.generate() === next)
        }
      }
    }

    "its id1 is the max value and its id2 is not the max value" should {
      "increment the id2" in {
        forAll { (id2: Int, reference: ErlNewReference) =>
          whenever(id2 != -1) {
            val zero = reference.copy(id = reference.id.copy(_1 = 0x3ffff, _2 = id2))
            val generator = new ReferenceGenerator(zero)

            assert(generator.generate() === zero)
            val next = zero.copy(id = zero.id.copy(_1 = 0, _2 = id2 + 1))
            assert(generator.generate() === next)
          }
        }
      }
    }

    "its id1 and id2 are their value" should {
      "reset id1 and id2 and increment id3" in {
        forAll { (id3: Int, reference: ErlNewReference) =>
          val zero = reference.copy(id = (0x3ffff, -1, id3))
          val generator = new ReferenceGenerator(zero)

          assert(generator.generate() === zero)
          val next = zero.copy(id = (0, 0, id3 + 1))
          assert(generator.generate() === next)
        }
      }
    }
  }

  "updateCreation" should {
    "replace the creation of the current reference" in {
      forAll { (creation: Byte, reference: ErlNewReference) =>
        val generator = new ReferenceGenerator(reference)

        generator.updateCreation(creation)
        val expected = reference.copy(creation = creation)
        assert(generator.generate() === expected)
      }
    }
  }
}
