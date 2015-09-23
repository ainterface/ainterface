package akka.ainterface.datatype.interpolation

import akka.ainterface.datatype.ErlTerm
import scala.reflect.macros.blackbox.Context

private[interpolation] object ErlTermInterpolationMacro {
  def erlImpl(c: Context)(args: c.Expr[ErlTerm]*): c.Expr[ErlTerm] = {
    import c.universe._

    implicit val bigIntLiftable: Liftable[BigInt] = Liftable { v =>
      q"_root_.scala.math.BigInt(${v.bigInteger.toString})"
    }

    def lift(value: ETerm, ai: Iterator[Tree]): Tree = value match {
      case EInteger(v) => q"_root_.akka.ainterface.datatype.ErlInteger($v)"
      case EFloat(v) => q"_root_.akka.ainterface.datatype.ErlFloat($v)"
      case EAtom(v) => q"_root_.akka.ainterface.datatype.ErlAtom($v)"
      case ETuple(v) => q"_root_.akka.ainterface.datatype.ErlTuple(..${v.map { x => lift(x, ai)}})"
      case EList(v) => q"_root_.akka.ainterface.datatype.ErlList(${v.map { x => lift(x, ai) }})"
      case EVariable => q"${ai.next()}"
    }

    c.prefix.tree match {
      case Apply(_, List(Apply(_, erlTrees))) if erlTrees.size == args.size +1 =>
        val erl = erlTrees.map {
          case Literal(Constant(x: String)) => x
        }.mkString(ErlParser.Placeholder)

        ErlParser(erl) match {
          case Right(term) => c.Expr[ErlTerm](lift(term, args.iterator.map(_.tree)))
          case Left(e) => c.abort(c.enclosingPosition, e)
        }
      case _ => c.abort(c.enclosingPosition, "Invalid invocation.")
    }
  }
}
