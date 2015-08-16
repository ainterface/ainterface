package akka.ainterface.util.collection

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.WordSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class BiMapSpec extends WordSpec with GeneratorDrivenPropertyChecks {
  private[this] val genPair: Gen[(Int, String)] = {
    Arbitrary.arbitrary[Int].map { x =>
      x -> x.toString
    }
  }
  private[this] def create(pairs: List[(Int, String)]): BiMap[Int, String] = {
    pairs.foldLeft(BiMap.empty[Int, String]) {
      case (map, (k, v)) => map + (k, v)
    }
  }

  "BiMap" should {
    "return inverse" in {
      forAll(Gen.listOf(genPair)) { pairs: List[(Int, String)] =>
        val biMap = create(pairs)
        assert(biMap === biMap.inverse.inverse)
        assert(pairs.forall { case (k, v) => biMap.get(k) == Some(v) })
        assert(pairs.forall { case (k, v) => biMap.inverse.get(v) == Some(k) })
      }
    }

    "get Some value" when {
      "the associated value exists" in {
        forAll(Gen.nonEmptyListOf(genPair)) { pairs: List[(Int, String)] =>
          val biMap = create(pairs)
          assert(pairs.forall { case (k, v) => biMap.get(k) == Some(v) })
          assert(pairs.forall { case (k, v) => biMap.contains(k) })
        }
      }
    }

    "get None" when {
      "the associated value is not found" in {
        forAll(Gen.listOf(genPair), Arbitrary.arbitrary[Int]) {
          (pairs: List[(Int, String)], key: Int) =>
            whenever(!pairs.map(_._1).contains(key)) {
              val biMap = create(pairs)
              assert(biMap.get(key) === None)
              assert(biMap.contains(key) === false)
            }
        }
      }
    }

    "update" when {
      "the same pair exists" in {
        forAll(Gen.nonEmptyListOf(genPair), Arbitrary.arbitrary[Int]) {
          (pairs: List[(Int, String)], key: Int) =>
            whenever(pairs.map(_._1).contains(key)) {
              val value = key.toString
              val biMap = create(pairs)
              val actual = biMap + (key, value)
              assert(actual === biMap)
              assert(actual.get(key) === Some(value))
              assert(actual.inverse.get(value) === Some(key))
            }
        }
      }

      "the associated value exists" in {
        forAll(Gen.nonEmptyListOf(genPair), Arbitrary.arbitrary[Int], Gen.alphaChar.map(_.toString)) {
          (pairs: List[(Int, String)], key: Int, value: String) =>
            whenever(pairs.map(_._1).contains(key)) {
              val biMap = create(pairs)
              assert(biMap.get(key) === Some(key.toString))
              val actual = biMap + (key, value)
              assert(actual.get(key) === Some(value))
              assert(actual.inverse.get(value) === Some(key))
              assert(actual.inverse.get(key.toString) === None)
            }
        }
      }

      "the associated value is not found" in {
        forAll(Gen.nonEmptyListOf(genPair), Arbitrary.arbitrary[Int]) {
          (pairs: List[(Int, String)], key: Int) =>
            whenever(!pairs.map(_._1).contains(key)) {
              val value = key.toString
              val biMap = create(pairs)
              assert(biMap.get(key) === None)
              val actual = biMap + (key, value)
              assert(actual.get(key) === Some(value))
              assert(actual.inverse.get(value) === Some(key))
            }
        }
      }
    }

    "fail updating" when {
      "the value is already associated with a different key" in {
        forAll(Gen.nonEmptyListOf(genPair), Arbitrary.arbitrary[Int]) {
          (pairs: List[(Int, String)], key: Int) =>
            whenever(pairs.map(_._1).contains(key)) {
              val biMap = create(pairs)
              intercept[IllegalArgumentException] {
                biMap + (key + 1, key.toString)
              }
            }
        }
      }
    }

    "delete" when {
      "the associated value exists" in {
        forAll(Gen.nonEmptyListOf(genPair), Arbitrary.arbitrary[Int]) {
          (pairs: List[(Int, String)], key: Int) =>
            whenever(pairs.map(_._1).contains(key)) {
              val biMap = create(pairs)
              val actual = biMap - key
              val expected = create(pairs.filterNot(_._1 == key))
              assert(actual === expected)
              assert(actual.get(key) === None)
              assert(actual.inverse.get(key.toString) === None)
            }
        }
      }

      "the associated value is not found" in {
        forAll(Gen.listOf(genPair), Arbitrary.arbitrary[Int]) {
          (pairs: List[(Int, String)], key: Int) =>
            whenever(!pairs.map(_._1).contains(key)) {
              val biMap = create(pairs)
              val actual = biMap - key
              assert(biMap - key === biMap)
              assert(actual.get(key) === None)
              assert(actual.inverse.get(key.toString) === None)
            }
        }
      }
    }
  }

  "return all keys" in {
    forAll(Gen.listOf(genPair)) { pairs: List[(Int, String)] =>
      val biMap = create(pairs)
      assert(biMap.keys.toSet === pairs.map(_._1).toSet)
      assert(biMap.inverse.keys.toSet === pairs.map(_._2).toSet)
    }
  }

  "return all values" in {
    forAll(Gen.listOf(genPair)) { pairs: List[(Int, String)] =>
      val biMap = create(pairs)
      assert(biMap.values.toSet === pairs.map(_._2).toSet)
      assert(biMap.inverse.values.toSet === pairs.map(_._1).toSet)
    }
  }
}
