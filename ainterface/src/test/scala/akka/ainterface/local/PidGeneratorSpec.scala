package akka.ainterface.local

import akka.ainterface.datatype.ErlPid
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbErlPid
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class PidGeneratorSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "PidGenerator" when {
    "its id is not the max value" should {
      "increment the id" in {
        forAll(Gen.chooseNum(0, 0x7fff - 1), arbitrary[ErlPid]) { (id: Int, pid: ErlPid) =>
          val zero = ErlPid(pid.nodeName, id, pid.serial, pid.creation)
          val generator = new PidGenerator(zero)

          assert(generator.generate() === zero)
          val next = ErlPid(zero.nodeName, id + 1, zero.serial, zero.creation)
          assert(generator.generate() === next)
        }
      }
    }

    "its id is the max value and its serial is not the max value" should {
      "increment the serial" in {
        forAll(Gen.chooseNum(0, 0x1fff - 1), arbitrary[ErlPid]) { (serial: Int, pid: ErlPid) =>
          val zero = ErlPid(pid.nodeName, 0x7fff, serial, pid.creation)
          val generator = new PidGenerator(zero)

          assert(generator.generate() === zero)
          val next = ErlPid(zero.nodeName, 0, serial + 1, zero.creation)
          assert(generator.generate() === next)
        }
      }
    }

    "its id and serial are their value" should {
      "reset the id and the serial" in {
        forAll { pid: ErlPid =>
          val zero = ErlPid(pid.nodeName, 0x7fff, 0x1fff, pid.creation)
          val generator = new PidGenerator(zero)

          assert(generator.generate() === zero)
          val next = ErlPid(zero.nodeName, 0, 0, zero.creation)
          assert(generator.generate() === next)
        }
      }
    }
  }

  "updateCreation" should {
    "replace the creation of the current pid" in {
      forAll { (creation: Byte, pid: ErlPid) =>
        val generator = new PidGenerator(pid)

        generator.updateCreation(creation)
        val expected = ErlPid(pid.nodeName, pid.id, pid.serial, creation)
        assert(generator.generate() === expected)
      }
    }
  }
}
