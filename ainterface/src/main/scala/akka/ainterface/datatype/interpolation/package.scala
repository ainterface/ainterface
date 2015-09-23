package akka.ainterface.datatype

import scala.language.experimental.macros

package object interpolation {
  implicit final class ErlTermStringContext(val context: StringContext) extends AnyVal {
    /**
     * Provides ErlTermInterpolation.
     * ErlTermInterpolation makes it possible to create [[ErlTerm]] by Erlang like syntax.
     *
     * {{{
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
     *   val one = ErlInteger(1)
     *   erl"$one" // ErlInteger(1)
     *   erl"[$one, $one, $one]" // ErlList(List(ErlInteger(1), ErlInteger(2), ErlInteger(3)))
     *
     *   val pid: ErlPid = ???
     *   erl"{ok, $pid}" // ErlTuple(ErlAtom("ok"), pid)
     * }}}
     */
    def erl(args: ErlTerm*): ErlTerm = macro ErlTermInterpolationMacro.erlImpl
  }
}
