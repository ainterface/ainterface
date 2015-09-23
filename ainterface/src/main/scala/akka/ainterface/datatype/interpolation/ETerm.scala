package akka.ainterface.datatype.interpolation

private[interpolation] sealed abstract class ETerm

private[interpolation] final case class EInteger(value: BigInt) extends ETerm
private[interpolation] final case class EFloat(value: Double) extends ETerm
private[interpolation] final case class EAtom(value: String) extends ETerm
private[interpolation] final case class ETuple(elements: List[ETerm]) extends ETerm
private[interpolation] final case class EList(elements: List[ETerm]) extends ETerm
private[interpolation] case object EVariable extends ETerm
