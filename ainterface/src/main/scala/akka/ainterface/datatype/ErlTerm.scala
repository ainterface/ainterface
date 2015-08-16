package akka.ainterface.datatype

import akka.util.ByteString

/**
 * Represents an Erlang term.
 */
sealed abstract class ErlTerm extends Serializable

/**
 * Represents an integral value of Erlang.
 */
final case class ErlInteger(value: BigInt) extends ErlTerm

object ErlInteger {
  /**
   * Creates an ErlInteger from the Byte value.
   * @param value the value to be converted into ErlInteger
   */
  def apply(value: Byte): ErlInteger = ErlInteger(BigInt(value))

  /**
   * Creates an ErlInteger from the Short value.
   * @param value the value to be converted into ErlInteger
   */
  def apply(value: Short): ErlInteger = ErlInteger(BigInt(value))

  /**
   * Creates an ErlInteger from the Int value.
   * @param value the value to be converted into ErlInteger
   */
  def apply(value: Int): ErlInteger = ErlInteger(BigInt(value))

  /**
   * Creates an ErlInteger with the Long value.
   * @param value the value to be converted into ErlInteger
   */
  def apply(value: Long): ErlInteger = ErlInteger(BigInt(value))
}

/**
 * Represents a floating point number of Erlang.
 */
final case class ErlFloat(value: Double) extends ErlTerm

/**
 * Represents an atom value of Erlang.
 */
final case class ErlAtom(value: String) extends ErlTerm {
  require(value.length <= ErlAtom.AllowableLength, s"Atom can contain up to 255 characters. $value")
}

object ErlAtom {
  private[ainterface] val AllowableLength = 255
}

/**
 * Represents a bit string value of Erlang.
 */
sealed abstract class ErlBitString extends ErlTerm {
  def value: ByteString
  def bitLength: Int
}
private[ainterface] final case class ErlBitStringImpl(value: ByteString,
                                                  bitLength: Int) extends ErlBitString {
  override def productPrefix: String = "ErlBitString"
}
/**
 * Represents a binary value of Erlang.
 * ErlBinaries are ErlBitStrings which are evenly divisible by eight.
 */
final case class ErlBinary(value: ByteString) extends ErlBitString {
  override def bitLength: Int = value.length * java.lang.Byte.SIZE
}

object ErlBitString {
  /**
   * Creates an ErlBitString from the ByteString.
   * @param value binary data
   */
  def apply(value: ByteString): ErlBitString = ErlBinary(value)

  /**
   * Creates an ErlBitString from the ByteString and it has the specified bit-length.
   *
   * The size of `value` of returned ErlBitString should be
   * - `bitLength` / 8 if `bitLength` is divisible by 8
   * - `bitLength` / 8 + 1 if `bieLength` is not divisible by 8
   *
   * If the specified `value` is longer, the sliced `value` will be the value of ErlBitString.
   * If the specified `value` is shorter, the `value` will be extended by zero padding on the right.
   *
   * @param value binary data
   * @param bitLength bit length
   */
  def apply(value: ByteString, bitLength: Int): ErlBitString = {
    require(bitLength >= 0, "bitLength should be greater than or equal to 0.")

    val isBinary = bitLength % java.lang.Byte.SIZE == 0
    val byteLength = bitLength / java.lang.Byte.SIZE + (if (isBinary) 0 else 1)

    value.length match {
      case x if isBinary && x == byteLength => ErlBinary(value)
      case x if isBinary && x < byteLength =>
        ErlBinary(value ++ ByteString(Array.fill[Byte](byteLength - x)(0)))
      case x if isBinary && x > byteLength => ErlBinary(value.take(byteLength))
      case x if !isBinary && x <= byteLength =>
        ErlBitStringImpl(value ++ ByteString(Array.fill[Byte](byteLength - x)(0)), bitLength)
      case x if !isBinary && x > byteLength => ErlBitStringImpl(value.take(byteLength), bitLength)
    }
  }
}

/**
 * Represents a reference of Erlang.
 * References are unique values created by make_ref/0.
 */
sealed abstract class ErlReference extends ErlTerm
private[ainterface] final case class ErlNewReference(nodeName: ErlAtom,
                                                 id: (Int, Int, Int),
                                                 creation: Byte) extends ErlReference {
  override def productPrefix: String = "ErlReference"
}

private[ainterface] object ErlNewReference {
  val MaxId1 = 0x3ffff // 18 bits
}

/**
 * Represents a function of Erlang.
 */
sealed abstract class ErlFun extends ErlTerm
/**
 * An internal function.
 */
private[ainterface] final case class ErlNewFun(arity: Int,
                                           uniq: ByteString,
                                           index: Int,
                                           module: ErlAtom,
                                           oldIndex: Int,
                                           oldUniq: Int,
                                           pid: ErlPid,
                                           freeVars: List[ErlTerm]) extends ErlFun {
  override def productPrefix: String = "ErlInternalFun"
}
/**
 * An external function, defined by "fun Module:Name/Arity".
 */
private[ainterface] final case class ErlExternalFun(module: ErlAtom,
                                                function: ErlAtom,
                                                arity: Int) extends ErlFun

/**
 * Represents a port identifier of Erlang.
 */
final class ErlPort private (val nodeName: ErlAtom,
                             val id: Int,
                             val creation: Byte) extends ErlTerm {
  override def equals(other: Any): Boolean = other match {
    case that: ErlPort =>
      nodeName == that.nodeName &&
        id == that.id &&
        creation == that.creation
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(nodeName, id, creation)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString = s"ErlPort($nodeName, $id, $creation)"
}

object ErlPort {
  private[ainterface] def apply(nodeName: ErlAtom, id: Int, creation: Byte): ErlPort = {
    new ErlPort(nodeName, id, creation)
  }

  private[ainterface] def unapply(port: ErlPort): Option[(ErlAtom, Int, Byte)] = {
    Some(port.nodeName, port.id, port.creation)
  }
}

/**
 * Represents a process identifier of Erlang.
 */
final class ErlPid private (val nodeName: ErlAtom,
                            val id: Int,
                            val serial: Int,
                            val creation: Byte) extends ErlTerm {
  override def equals(other: Any): Boolean = other match {
    case that: ErlPid =>
      nodeName == that.nodeName &&
        id == that.id &&
        serial == that.serial &&
        creation == that.creation
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(nodeName, id, serial, creation)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString: String = s"ErlPid($nodeName, $id, $serial, $creation)"
}

private[ainterface] object ErlPid {
  val MaxId = 0x7fff // 15 bits
  val MaxSerial = 0x1fff // 13 bits

  def apply(nodeName: ErlAtom, id: Int, serial: Int, creation: Byte): ErlPid = {
    new ErlPid(nodeName, id, serial, creation)
  }

  def unapply(pid: ErlPid): Option[(ErlAtom, Int, Int, Byte)] = {
    Some(pid.nodeName, pid.id, pid.serial, pid.creation)
  }
}

/**
 * Represents a tuple of Erlang.
 */
final case class ErlTuple(elements: ErlTerm*) extends ErlTerm {
  def size: Int = elements.size
}

/**
 * Represents a map of Erlang.
 */
final case class ErlMap(value: Map[ErlTerm, ErlTerm]) extends ErlTerm

/**
 * Represents a list of Erlang.
 */
final case class ErlList(value: List[ErlTerm]) extends ErlTerm

object ErlList {
  val empty: ErlList = ErlList(Nil)

  def apply(args: ErlTerm*): ErlList = ErlList(args.toList)
}

/**
 * Represents an improper list of Erlang.
 * An improper list is a list which final tail is not [].
 */
final case class ErlImproperList(elements: List[ErlTerm], tail: ErlTerm) extends ErlTerm
