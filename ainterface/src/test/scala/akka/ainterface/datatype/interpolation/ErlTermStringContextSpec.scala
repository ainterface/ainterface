package akka.ainterface.datatype.interpolation

import akka.ainterface.datatype.{ErlAtom, ErlFloat, ErlInteger, ErlList, ErlPid, ErlTuple}
import org.scalatest.WordSpec

class ErlTermStringContextSpec extends WordSpec {
  "erl" should {
    "parse an integer" in {
      assert(erl"0" === ErlInteger(0))
      assert(erl"334" === ErlInteger(334))
      assert(erl"0334" === ErlInteger(334))
      assert(erl"-334" === ErlInteger(-334))
      assert(erl"1111111111111111111111111111111" === ErlInteger(BigInt("1111111111111111111111111111111")))
      assert(erl"-1111111111111111111111111111111" === ErlInteger(BigInt("-1111111111111111111111111111111")))
    }

    "parse a float" in {
      assert(erl"0.0" === ErlFloat(0.0))
      assert(erl"33.4" === ErlFloat(33.4))
      assert(erl"033.4" === ErlFloat(33.4))
      assert(erl"-33.4" === ErlFloat(-33.4))
    }

    "parse an atom" in {
      assert(erl"mofu" === ErlAtom("mofu"))
      assert(erl"mofu_Mofu@334" === ErlAtom("mofu_Mofu@334"))
      assert(erl"'MOFU~MOFU'" === ErlAtom("MOFU~MOFU"))
      assert(erl"'???'" === ErlAtom("???"))
    }

    "parse an tuple" in {
      assert(erl"{}" === ErlTuple())
      assert(erl"{1}" === ErlTuple(ErlInteger(1)))
      assert(erl"{a, b}" === ErlTuple(ErlAtom("a"), ErlAtom("b")))
    }

    "parse an list" in {
      assert(erl"[]" === ErlList.empty)
      assert(erl"[1]" === ErlList(List(ErlInteger(1))))
      assert(erl"[a, b]" === ErlList(List(ErlAtom("a"), ErlAtom("b"))))
      assert(erl"[{a, b}, 1, {33.4}, 0]" === ErlList(List(
        ErlTuple(ErlAtom("a"), ErlAtom("b")),
        ErlInteger(1),
        ErlTuple(ErlFloat(33.4)), ErlInteger(0))
      ))
    }

    "put terms" in {
      val i = ErlInteger(334)
      val f = ErlFloat(33.4)
      val a = ErlAtom("mofu")
      val p = ErlPid(a, 5, 10, 1)
      val t = ErlTuple(i, f, a, p)
      val l = ErlList(List(i, f))

      assert(erl"$i" === i)
      assert(erl"$f" === f)
      assert(erl"$a" === a)
      assert(erl"$p" === p)
      assert(erl"$t" === t)
      assert(erl"$l" === l)

      assert(erl"{$i, $f, $a}" === ErlTuple(i, f, a))
      assert(erl"[$i, $f, $a]" === ErlList(List(i, f, a)))

      val xs = (0 to 10).map(ErlInteger.apply)
      val actual =
        erl"""
             {
               [
                 ${xs.head},
                 [${xs(1)}, ${xs(2)}],
                 ${xs(3)}
               ],
               ${xs(4)},
               ${xs(5)},
               [
                 [${xs(6)}, ${xs(7)}],
                 [${xs(8)}, ${xs(9)}],
                 ${xs(10)}
               ]
             }
           """
      val expected = ErlTuple(
        ErlList(List(
          xs.head,
          ErlList(List(xs(1), xs(2))),
          xs(3)
        )),
        xs(4),
        xs(5),
        ErlList(List(
          ErlList(List(xs(6), xs(7))),
          ErlList(List(xs(8), xs(9))),
          xs(10)
        ))
      )
      assert(actual === expected)
    }

    "fail parsing" when {
      "illegal literal is passed" in {
        assertDoesNotCompile("""erl"Atom"""")
        assertDoesNotCompile("""erl"0atom"""")
        assertDoesNotCompile("""erl"_atom"""")
        assertDoesNotCompile("""erl"@atom"""")
        assertDoesNotCompile("""erl"atom~"""")
        assertDoesNotCompile("""erl"{[1, 2}]"""")
      }
    }
  }

  "atom" should {
    "parse an atom" in {
      assert(atom"" === ErlAtom(""))
      assert(atom"mofu" === ErlAtom("mofu"))
      assert(atom"mofu_Mofu@334" === ErlAtom("mofu_Mofu@334"))
      assert(atom"MOFU~MOFU" === ErlAtom("MOFU~MOFU"))
      assert(atom"'mofu'" === ErlAtom("'mofu'"))
      assert(atom"???" === ErlAtom("???"))
    }
  }
}
