package akka.ainterface.datatype.interpolation

import akka.ainterface.datatype.ErlTerm
import scala.language.experimental.macros

final class ErlTermStringContext(val context: StringContext) extends AnyVal {
  def erl(args: ErlTerm*): ErlTerm = macro ErlTermInterpolationMacro.erlImpl
}

