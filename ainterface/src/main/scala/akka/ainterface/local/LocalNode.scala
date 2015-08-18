package akka.ainterface.local

import akka.actor.ActorRef
import akka.ainterface.datatype.{ErlAtom, ErlError, ErlList, ErlPid, ErlReference}
import akka.ainterface.{ControlMessage, NodeName, Target}
import java.util.concurrent.CountDownLatch

/**
 * Represents the local node.
 * This class is mutable and must be thread-safe.
 */
private[ainterface] class LocalNode(val nodeName: NodeName,
                                deadLetterBox: LocalDeadLetterBox,
                                latch: CountDownLatch,
                                table: LocalProcessTable,
                                registry: LocalNameRegistry) {
  private[this] val pidGenerator: PidGenerator = PidGenerator(nodeName)
  private[this] val referenceGenerator: ReferenceGenerator = ReferenceGenerator(nodeName)

  def updateCreation(creation: Byte): Unit = {
    latch.countDown()
    pidGenerator.updateCreation(creation)
    referenceGenerator.updateCreation(creation)
  }

  def createPid(): ErlPid = pidGenerator.generate()

  def makeRef(): ErlReference = referenceGenerator.generate()

  def lookup(pid: ErlPid): Option[ActorRef] = table.lookup(pid)

  def add(pid: ErlPid, process: ActorRef): Unit = {
    assert(table.add(pid, process))
  }

  def remove(pid: ErlPid): Unit = {
    table.remove(pid)
    registry.exit(pid)
  }

  def whereis(name: ErlAtom): Option[ErlPid] = registry.whereis(name)

  def registered: Iterable[ErlAtom] = registry.registered

  def register(name: ErlAtom, pid: ErlPid): Unit = {
    def badarg() = ErlError.badarg(
      ErlAtom("erlang"),
      ErlAtom("register"),
      ErlList(name, pid)
    )
    if (pid.nodeName != nodeName.asErlAtom) {
      throw badarg()
    } else if (lookup(pid).isEmpty) {
      throw badarg()
    } else if (name == ErlAtom("undefined")) {
      throw badarg()
    } else if (!registry.register(name, pid)) {
      throw badarg()
    }
  }

  def unregister(name: ErlAtom): Unit = {
    if (!registry.unregister(name)) {
      throw ErlError(
        ErlAtom("erlang"),
        ErlAtom("unregister"),
        ErlAtom("badarg"),
        ErlList(name)
      )
    }
  }

  def sendEvent(message: ControlMessage): Unit = {
    val process = message.target match {
      case Target.Pid(pid) => table.lookup(pid)
      case Target.Name(name, node) =>
        assert(node == nodeName)
        for {
          pid <- registry.whereis(name)
          ref <- table.lookup(pid)
        } yield ref
    }
    process match {
      case Some(p) => p ! message
      case None => deadLetterBox.drop(message)
    }
  }
}

private[ainterface] object LocalNode {
  def apply(nodeName: NodeName,
            deadLetterBox: LocalDeadLetterBox,
            latch: CountDownLatch): LocalNode = {
    new LocalNode(nodeName, deadLetterBox, latch, new LocalProcessTable, new LocalNameRegistry)
  }
}
