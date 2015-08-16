package akka.ainterface.util.actor

import org.scalacheck.Gen
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class ActorPathUtilSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  private[this] val UUIDReg = """[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"""

  "orUUID" should {
    "not fail" in {
      forAll { s: String =>
        val actual = ActorPathUtil.orUUID(s)
        assert(actual === s || actual.matches(UUIDReg))
      }
    }

    "return the passed String" when {
      "it is a valid path element" in {
        forAll(Gen.alphaStr) { s: String =>
          whenever(s.nonEmpty) {
            val actual = ActorPathUtil.orUUID(s)
            assert(actual === s)
          }
        }
      }
    }

    "return a UUID" when {
      "it is not a valid path element" in {
        val actual = ActorPathUtil.orUUID("/home/mofu")
        assert(actual.matches(UUIDReg))
      }
    }
  }
}
