package akka.ainterface.local

import akka.ainterface.datatype.{ErlAtom, ErlPid}
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.{arbErlAtom, arbErlPid}
import akka.ainterface.util.collection.BiMap
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class LocalNameRegistrySpec extends WordSpec with GeneratorDrivenPropertyChecks {
  implicit private[this] val arbPairs: Arbitrary[List[(ErlAtom, ErlPid)]] = Arbitrary {
    var atoms = Set.empty[ErlAtom]
    var pids = Set.empty[ErlPid]
    Gen.listOf(Arbitrary.arbitrary[(ErlAtom, ErlPid)]).map { pairs =>
      pairs.filter {
        case (k, v) =>
          if (atoms(k) || pids(v)) {
            false
          } else {
            atoms = atoms + k
            pids = pids + v
            true
          }
      }
    }
  }
  private[this] def setup(kvs: Seq[(ErlAtom, ErlPid)]): LocalNameRegistry = {
    val registry = new LocalNameRegistry
    kvs.foreach {
      case (k, v) => registry.register(k, v)
    }
    registry
  }

  "LocalNameRegistry" should {
    "look up the pid associated with the name" in {
      forAll { kvs: List[(ErlAtom, ErlPid)] =>
        val registry = setup(kvs)
        kvs.foreach {
          case (name, pid) => assert(registry.whereis(name) === Some(pid))
        }
      }
    }

    "look up and return None" when {
      "no pid is associated with the name" in {
        forAll { (kvs: List[(ErlAtom, ErlPid)], name: ErlAtom) =>
          whenever(!kvs.exists(_._1 == name)) {
            val registry = setup(kvs)
            assert(registry.whereis(name) === None)
          }
        }
      }
    }

    "return registered names" in {
      forAll { kvs: List[(ErlAtom, ErlPid)] =>
        val registry = setup(kvs)
        assert(registry.registered.toSet === kvs.map(_._1).toSet)
      }
    }

    "register the pid" in {
      forAll { (name: ErlAtom, pid: ErlPid) =>
        val registry = new LocalNameRegistry
        assert(registry.register(name, pid))
        assert(registry.underlying === BiMap.empty + (name -> pid))
      }
    }

    "fail registering the pid" when {
      "the name is already used" in {
        forAll { (name: ErlAtom, pid1: ErlPid, pid2: ErlPid) =>
          val registry = new LocalNameRegistry
          assert(registry.register(name, pid1))
          assert(registry.underlying === BiMap.empty + (name -> pid1))
          assert(!registry.register(name, pid2))
          assert(registry.underlying === BiMap.empty + (name -> pid1))
        }
      }

      "the pid is already associated" in {
        forAll { (name1: ErlAtom, name2: ErlAtom, pid: ErlPid) =>
          val registry = new LocalNameRegistry
          assert(registry.register(name1, pid))
          assert(registry.underlying === BiMap.empty + (name1 -> pid))
          assert(!registry.register(name2, pid))
          assert(registry.underlying === BiMap.empty + (name1 -> pid))
        }
      }
    }

    "unregister" in {
      forAll { kvs: List[(ErlAtom, ErlPid)] =>
        val registry = setup(kvs)
        kvs.foreach {
          case (name, pid) =>
            assert(registry.whereis(name) === Some(pid))
            registry.unregister(name)
            assert(registry.whereis(name) === None)
        }
        assert(registry.registered.isEmpty)
      }
    }

    "fail unregistering" when {
      "the name is not used" in {
        forAll { (kvs: List[(ErlAtom, ErlPid)], name: ErlAtom) =>
          whenever(!kvs.exists(_._1 == name)) {
            val registry = setup(kvs)
            assert(!registry.unregister(name))
          }
        }
      }
    }

    "exit" in {
      forAll { (kvs: List[(ErlAtom, ErlPid)]) =>
        val registry = setup(kvs)
        kvs.foreach {
          case (name, pid) =>
            assert(registry.whereis(name) === Some(pid))
            registry.exit(pid)
            assert(registry.whereis(name) === None)
        }
        assert(registry.registered.isEmpty)
      }
    }

    "do nothing" when {
      "exit a non registered pid" in {
        forAll { (kvs: List[(ErlAtom, ErlPid)], pid: ErlPid) =>
          whenever(!kvs.exists(_._2 == pid)) {
            val registry = setup(kvs)
            val map = registry.underlying
            registry.exit(pid)
            assert(registry.underlying === map)
          }
        }
      }
    }
  }
}
