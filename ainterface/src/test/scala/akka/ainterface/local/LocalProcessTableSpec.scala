package akka.ainterface.local

import akka.actor.{Actor, ActorRef, Props}
import akka.ainterface.datatype.ErlPid
import akka.ainterface.local.LocalProcessTableSpec.TestActor
import akka.ainterface.test.ActorSpec
import akka.ainterface.test.arbitrary.AinterfaceArbitrary.arbErlPid
import org.scalacheck.{Arbitrary, Gen}

class LocalProcessTableSpec extends ActorSpec {
  implicit private[this] val arbPairs: Arbitrary[List[(ErlPid, ActorRef)]] = Arbitrary {
    Gen.listOf(Arbitrary.arbitrary[ErlPid]).map { pid =>
      pid.distinct.map { _ -> ref }
    }
  }
  private[this] def ref(): ActorRef = system.actorOf(Props[TestActor])
  private[this] def setup(kvs: Seq[(ErlPid, ActorRef)]): LocalProcessTable = {
    val table = new LocalProcessTable
    kvs.foreach {
      case (k, v) => table.add(k, v)
    }
    table
  }

  "LocalProcessTable" should {
    "look up the process" in {
      forAll { kvs: List[(ErlPid, ActorRef)] =>
        val table = setup(kvs)
        kvs.foreach {
          case (pid, process) =>
            assert(table.lookup(pid) === Some(process))
        }
      }
    }

    "not find the process" when {
      "looking up a non existent pid" in {
        forAll { (kvs: List[(ErlPid, ActorRef)], pid: ErlPid) =>
          whenever(!kvs.exists(_._1 == pid)) {
            val table = setup(kvs)
            assert(table.lookup(pid) === None)
          }
        }
      }
    }

    "insert the new process" in {
      forAll { pid: ErlPid =>
        val table = new LocalProcessTable
        assert(table.lookup(pid) === None)
        val process = ref()
        assert(table.add(pid, process))
        assert(table.lookup(pid) === Some(process))
      }
    }

    "fail inserting the new process" when {
      "the pid is already registered" in {
        forAll { pid: ErlPid =>
          val table = new LocalProcessTable
          assert(table.lookup(pid) === None)
          val process = ref()
          assert(table.add(pid, process))
          assert(!table.add(pid, ref()))
          assert(table.lookup(pid) === Some(process))
        }
      }
    }

    "remove the process" in {
      forAll { kvs: List[(ErlPid, ActorRef)] =>
        val table = setup(kvs)
        kvs.foreach {
          case (pid, process) =>
            assert(table.lookup(pid) === Some(process))
            table.remove(pid)
            assert(table.lookup(pid) === None)
        }
      }
    }

    "do nothing" when {
      "removing a non existent pid" in {
        forAll { (kvs: List[(ErlPid, ActorRef)], pid: ErlPid) =>
          whenever(!kvs.exists(_._1 == pid)) {
            val table = setup(kvs)
            assert(table.lookup(pid) === None)
            table.remove(pid)
            assert(table.lookup(pid) === None)
          }
        }
      }
    }
  }
}

object LocalProcessTableSpec {
  class TestActor extends Actor {
    override def receive: Receive = {
      case _ =>
    }
  }
}
