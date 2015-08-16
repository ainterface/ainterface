package akka.ainterface.test.arbitrary

import akka.ainterface.NodeName
import akka.ainterface.remote.{DFlags, ErlCookie, NodeConfig}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}

object AinterfaceArbitrary
  extends ErlTermArbitrary
  with BitVectorArbitrary
  with DurationArbitrary
  with StringArbitrary {

  implicit val arbDFlags: Arbitrary[DFlags] = Arbitrary(arbitrary[Int].map(DFlags(_)))

  implicit val arbNodeConfig: Arbitrary[NodeConfig] = Arbitrary {
    for {
      nodeName <- arbitrary[NodeName]
      version <- Gen.posNum[Int]
      flags <- arbitrary[DFlags]
    } yield NodeConfig(nodeName, version, flags)
  }

  implicit val arbErlCookie: Arbitrary[ErlCookie] = {
    Arbitrary(arbReadableString.arbitrary.map(ErlCookie.apply))
  }
}
