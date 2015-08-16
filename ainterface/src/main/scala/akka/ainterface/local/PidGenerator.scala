package akka.ainterface.local

import akka.ainterface.NodeName
import akka.ainterface.datatype.ErlPid

/**
 * A generator of [[ErlPid]].
 */
final private[local] class PidGenerator(zero: ErlPid) {
  private[this] val generator: AtomicGenerator[ErlPid] = AtomicGenerator(zero) {
    case ErlPid(node, id, serial, creation) if id < ErlPid.MaxId =>
      ErlPid(node, id + 1, serial, creation)
    case ErlPid(node, _, serial, creation) if serial < ErlPid.MaxSerial =>
      ErlPid(node, 0, serial + 1, creation)
    case ErlPid(node, _, _, creation) => ErlPid(node, 0, 0, creation)
  }

  def generate(): ErlPid = generator.next()

  def updateCreation(creation: Byte): Unit = {
    generator.update {
      case ErlPid(node, id, serial, _) => ErlPid(node, id, serial, creation)
    }
  }
}

private[local] object PidGenerator {
  def apply(nodeName: NodeName): PidGenerator = {
    val zero = ErlPid(nodeName.asErlAtom, id = 1, serial = 0, creation = 0)
    new PidGenerator(zero)
  }
}
