package akka.ainterface.local

import akka.ainterface.NodeName
import akka.ainterface.datatype.{ErlNewReference, ErlReference}

/**
 * A generator of [[ErlReference]].
 */
private[local] final class ReferenceGenerator(zero: ErlNewReference) {
  private[this] val generator: AtomicGenerator[ErlNewReference] = AtomicGenerator(zero) {
    case ErlNewReference(node, (id1, id2, id3), creation) if id1 < ErlNewReference.MaxId1 =>
      ErlNewReference(node, (id1 + 1, id2, id3), creation)
    case ErlNewReference(node, (_, id2, id3), creation) if id2 != -1 =>
      ErlNewReference(node, (0, id2 + 1, id3), creation)
    case ErlNewReference(node, (_, _, id3), creation) =>
      ErlNewReference(node, (0, 0, id3 + 1), creation)
  }

  def generate(): ErlReference = generator.next()

  def updateCreation(creation: Byte): Unit = {
    generator.update {
      case ErlNewReference(node, ids, _) => ErlNewReference(node, ids, creation)
    }
  }
}

private[local] object ReferenceGenerator {
  def apply(nodeName: NodeName): ReferenceGenerator = {
    val zero = ErlNewReference(nodeName.asErlAtom, id = (1, 0, 0), creation = 0)
    new ReferenceGenerator(zero)
  }
}
