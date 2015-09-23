package akka.ainterface.datatype.interpolation

import akka.ainterface.datatype.{ErlAtom, ErlTerm}
import scala.language.experimental.macros

final class ErlTermStringContext(val context: StringContext) extends AnyVal {
  def erl(args: ErlTerm*): ErlTerm = macro ErlTermInterpolationMacro.erlImpl
}

final class ErlAtomStringContext(val context: StringContext) extends AnyVal {
  def atom(args: String*): ErlAtom = {
    context.checkLengths(args)
    val pi = context.parts.iterator
    val ai = args.iterator
    val sb = StringBuilder.newBuilder.append(pi.next())
    while (pi.hasNext) {
      sb.append(ai.next())
      sb.append(pi.next())
    }
    ErlAtom(sb.result())
  }
}
