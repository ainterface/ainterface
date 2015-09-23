package akka.ainterface.datatype

import scala.language.implicitConversions

package object interpolation {
  /**
   * Provides ErlTermInterpolation.
   * ErlTermInterpolation makes it possible to create [[ErlTerm]] by Erlang like syntax.
   *
   * {{{
   *   import akka.ainterface.datatype.interpolation._
   *
   *   erl"334" // ErlInteger(334)
   *   erl"33.4" // ErlFloat(33.4)
   *   erl"mofu" // ErlAtom("mofu")
   *   erl"'MOFU'" // ErlAtom("MOFU")
   *   erl"{1, 2, 3}" // ErlTuple(ErlInteger(1), ErlInteger(2), ErlInteger(3))
   *   erl"[1, 2, 3]" // ErlList(List(ErlInteger(1), ErlInteger(2), ErlInteger(3)))
   * }}}
   *
   * And then, any [[ErlTerm]] can be placed in ErlTermInterpolation.
   *
   * {{{
   *   import akka.ainterface.datatype.interpolation._
   *
   *   val one = ErlInteger(1)
   *   erl"$one" // ErlInteger(1)
   *   erl"[$one, $one, $one]" // ErlList(List(ErlInteger(1), ErlInteger(2), ErlInteger(3)))
   *
   *   val pid: ErlPid = ???
   *   erl"{ok, $pid}" // ErlTuple(ErlAtom("ok"), pid)
   * }}}
   */
  implicit def term(context: StringContext): ErlTermStringContext = new ErlTermStringContext(context)
}
