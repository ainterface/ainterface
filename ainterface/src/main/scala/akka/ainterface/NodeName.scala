package akka.ainterface

import akka.ainterface.datatype.ErlAtom

/**
 * Represents a name of an Erlang node.
 * When the node name is `abc@example.com`, `alive` is `abc` and `host` is `example.com`.
 */
private[ainterface] final case class NodeName(alive: String, host: String) {
  val asString: String = alive + NodeName.Separator + host
  val asErlAtom: ErlAtom = ErlAtom(asString)
}

private[ainterface] object NodeName {
  private val Separator = '@'

  /**
   * The name of node that is not a distributed node.
   */
  val NoNode: NodeName = NodeName("nonode", "nohost")

  def apply(name: ErlAtom): NodeName = NodeName(name.value)

  def apply(name: String): NodeName = {
    name.split(Separator) match {
      case Array(alive, host) => NodeName(alive, host)
      case _ => throw new IllegalArgumentException(s"$name is not a node name.")
    }
  }

  implicit val ordering: Ordering[NodeName] = Ordering[String].on(_.asString)
}
