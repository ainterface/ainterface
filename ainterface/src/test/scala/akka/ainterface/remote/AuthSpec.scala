package akka.ainterface.remote

import akka.ainterface.NodeName
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.{arbErlCookie, arbNodeName}
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class AuthSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  "Auth#getCookie" when {
    "not distributed mode" should {
      "always return nocookie" in {
        val auth = new Auth(NodeName.NoNode, ErlCookie("cookie"))
        assert(auth.getCookie === ErlCookie.NoCookie)
        assert(auth.getCookie(NodeName("local@node")) === ErlCookie.NoCookie)
      }
    }

    "distributed mode" should {
      "return our cookie" when {
        "local node name is specified" in {
          forAll { (nodeName: NodeName, cookie: ErlCookie) =>
            val auth = new Auth(nodeName, cookie)
            assert(auth.getCookie === cookie)
            assert(auth.getCookie(nodeName) === cookie)
          }
        }

        "non configured node name is specified" in {
          forAll {
            (local: NodeName, remote1: NodeName, remote2: NodeName, cookie1: ErlCookie, cookie2: ErlCookie) =>
              whenever(remote1 != remote2) {
                val auth = new Auth(local, cookie1)
                auth.setCookie(remote1, cookie2)
                assert(auth.getCookie === cookie1)
                assert(auth.getCookie(remote1) === cookie2)
                assert(auth.getCookie(remote2) === cookie1)
              }
          }
        }
      }

      "return configured cookie" in {
        forAll { (local: NodeName, remote: NodeName, cookie1: ErlCookie, cookie2: ErlCookie) =>
            whenever(local != remote) {
              val auth = new Auth(local, cookie1)
              auth.setCookie(remote, cookie2)
              assert(auth.getCookie === cookie1)
              assert(auth.getCookie(remote) === cookie2)
            }
        }
      }
    }
  }

  "Auth#setCookie" should {
    "not distributed mode" should {
      "not be able to set cookie" in {
        val auth = new Auth(NodeName.NoNode, ErlCookie("cookie"))
        intercept[RuntimeException] {
          auth.setCookie(ErlCookie("cookie"))
        }
        intercept[RuntimeException] {
          auth.setCookie(NodeName("local@node"), ErlCookie("cookie"))
        }
      }
    }

    "distributed mode" should {
      "set our cookie" when {
        "no node is specified" in {
          forAll { (nodeName: NodeName, cookie1: ErlCookie, cookie2: ErlCookie) =>
            val auth = new Auth(nodeName, cookie1)
            auth.setCookie(cookie2)
            assert(auth.getCookie === cookie2)
          }
        }

        "local node name is specified" in {
          forAll { (nodeName: NodeName, cookie1: ErlCookie, cookie2: ErlCookie) =>
            val auth = new Auth(nodeName, cookie1)
            auth.setCookie(nodeName, cookie2)
            assert(auth.getCookie === cookie2)
          }
        }
      }

      "set an individual cookie" when {
        "non local node name is specified" in {
          forAll {
            (local: NodeName, remote: NodeName, cookie1: ErlCookie, cookie2: ErlCookie) =>
              val auth = new Auth(local, cookie1)
              auth.setCookie(remote, cookie2)
              assert(auth.getCookie === cookie1)
              assert(auth.getCookie(remote) === cookie2)
          }
        }
      }
    }
  }
}
