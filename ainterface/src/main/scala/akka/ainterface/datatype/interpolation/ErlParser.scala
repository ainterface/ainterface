package akka.ainterface.datatype.interpolation

import akka.ainterface.datatype.ErlAtom
import scala.util.parsing.combinator.RegexParsers

private[interpolation] object ErlParser extends RegexParsers {
  val PlaceHolder = "???"

  private[this] val integer: Parser[EInteger] = """-?[0-9]+""".r ^^ { x => EInteger(BigInt(x)) }
  private[this] val float: Parser[EFloat] = """-?[0-9]+\.[0-9]+""".r ^^ { x =>
    EFloat(java.lang.Double.parseDouble(x))
  }
  private[this] val atomLiteral: Parser[EAtom] = """[a-z][a-zA-Z0-9_@]*""".r ^? {
    case x if x.length <= ErlAtom.AllowableLength => EAtom(x)
  }
  private[this] val atomQuotes: Parser[EAtom] = """'[\x20-\x7E]*'""".r ^? {
    case x if x.length <= ErlAtom.AllowableLength + 2 => EAtom(x.tail.init)
  }
  private[this] val atom: Parser[EAtom] = atomLiteral | atomQuotes
  private[this] val tuple: Parser[ETuple] = "{" ~> repsep(term, ",") <~ "}" ^^ ETuple
  private[this] val list: Parser[EList] = "[" ~> repsep(term, ",") <~ "]" ^^ EList

  private[this] val variable: Parser[EVariable.type] = PlaceHolder ^^^ EVariable

  private[this] lazy val term: Parser[ETerm] = tuple | list | float | integer | atom | variable

  def apply(input: String): Either[String, ETerm] = parseAll(term, input) match {
    case Success(v, _) => Right(v)
    case NoSuccess(e, _) => Left(e)
  }
}
