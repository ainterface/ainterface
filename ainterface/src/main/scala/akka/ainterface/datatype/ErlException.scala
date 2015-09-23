package akka.ainterface.datatype

import akka.ainterface.datatype.interpolation.atom

/**
 * An erlang exception.
 */
sealed abstract class ErlException(message: String) extends RuntimeException(message)

/**
 * An error by erlang:error.
 */
final case class ErlError private (reason: ErlTerm,
                                   module: ErlAtom,
                                   function: ErlAtom,
                                   args: ErlTerm)
  extends ErlException(s"** exception error: $reason") {

  def asTuple: ErlTuple = ErlTuple(
    atom"EXIT",
    ErlTuple(
      reason,
      ErlList(
        ErlTuple(
          module,
          function,
          args,
          ErlList.empty
        )
      )
    )
  )
}

object ErlError {
  /**
   * erlang:error/1
   */
  def apply(reason: ErlTerm, module: ErlAtom, function: ErlAtom, arity: ErlInteger): ErlError = {
    new ErlError(reason, module, function, arity)
  }

  /**
   * erlang:error/2
   */
  def apply(reason: ErlTerm, module: ErlAtom, function: ErlAtom, args: ErlList): ErlError = {
    new ErlError(reason, module, function, args)
  }

  def badarg(module: ErlAtom, function: ErlAtom, args: ErlList): ErlError = {
    ErlError(atom"badarg", module, function, args)
  }
}

/**
 * An error by erlang:exit.
 */
final case class ErlExit(reason: ErlTerm) extends ErlException(s"** exception exit: $reason") {
  def asTuple: ErlTuple = ErlTuple(atom"EXIT", reason)
}

object ErlExit {
  val Normal: ErlAtom = atom"normal"
  val Kill: ErlAtom = atom"kill"
  val Killed: ErlAtom = atom"killed"

  val Noproc: ErlAtom = atom"noproc"
  val Noconnection: ErlAtom = atom"noconnection"

  val KilledExit: ErlExit = ErlExit(Killed)
}

/**
 * An error by erlang:throw/1.
 */
final case class ErlThrow(any: ErlTerm) extends ErlException(s"** exception throw: $any")
