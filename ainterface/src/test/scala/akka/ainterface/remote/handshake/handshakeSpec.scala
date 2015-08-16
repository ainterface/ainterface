package akka.ainterface.remote.handshake

import akka.ainterface.remote.DFlags
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbDFlags
import akka.util.ByteString
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class handshakeSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "genDigest" should {
    "generate a digest" in {
      forAll(Gen.chooseNum(0L, 0xffffffffL), Arbitrary.arbitrary[String]) {
        (challenge, cookie) =>
          val actual = genDigest(challenge.toInt, cookie)
          val list = (cookie + challenge.toString).getBytes(StandardCharsets.ISO_8859_1)
          val digest = MessageDigest.getInstance("MD5").digest(list)
          assert(actual === ByteString(digest))
      }
    }
  }

  "adjust" when {
    "both flags are published" should {
      "return the original flags" in {
        forAll { (thisFlags: DFlags, otherFlags: DFlags) =>
          whenever(thisFlags.published && otherFlags.published) {
            val (x1, x2) = adjustFlags(thisFlags, otherFlags)
            assert(x1 === thisFlags)
            assert(x2 === otherFlags)
          }
        }
      }
    }

    "either flags are hidden" should {
      "return hidden flags" in {
        forAll { (thisFlags: DFlags, otherFlags: DFlags) =>
          whenever(thisFlags.isHidden || otherFlags.isHidden) {
            val (x1, x2) = adjustFlags(thisFlags, otherFlags)
            assert(x1.isHidden)
            assert(x1 === thisFlags.hide)
            assert(x2.isHidden)
            assert(x2 === otherFlags.hide)
          }
        }
      }
    }
  }
}
