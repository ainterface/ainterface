package akka.ainterface.util.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.ainterface.test.ActorSpec
import akka.ainterface.util.actor.DynamicSupervisorProtocol.{ChildRef, StartChild}
import akka.ainterface.util.actor.DynamicSupervisorSpec.{TestActor, TestSupervisor}
import akka.testkit.TestProbe

class DynamicSupervisorSpec extends ActorSpec {
  private[this] def propsOf(ref: ActorRef): Props = Props(classOf[TestActor], ref)

  "DynamicSupervisor" should {
    "start child and reply to the reverse path" when {
      "command contains the reverse path" in {
        forAll { identifier: Int =>
          val supervisor = system.actorOf(Props(classOf[TestSupervisor], None))
          val childProbe = TestProbe()
          supervisor ! StartChild(testActor, propsOf(childProbe.ref), identifier)
          childProbe.expectMsg(())
          expectMsgPF() {
            case ChildRef(_, Some(id)) => assert(id === identifier)
          }
        }
      }
    }

    "start child and reply to the sender" when {
      "command contains the reverse path" in {
        forAll { identifier: Int =>
          val supervisor = system.actorOf(Props(classOf[TestSupervisor], None))
          val childProbe = TestProbe()
          supervisor ! StartChild(None, propsOf(childProbe.ref), None, Some(identifier))
          childProbe.expectMsg(())
          expectMsgPF() {
            case ChildRef(_, Some(id)) => assert(id === identifier)
          }
        }
      }
    }

    "should be able to handle terminates of children" in {
      val terminateProbe = TestProbe()
      val supervisor = system.actorOf(Props(classOf[TestSupervisor], Some(terminateProbe.ref)))
      val childProbe = TestProbe()
      supervisor ! StartChild(propsOf(childProbe.ref))
      childProbe.expectMsg(())
      val child = expectMsgPF() {
        case ChildRef(ref, None) => ref
      }
      terminateProbe.expectNoMsg(shortDuration)
      system.stop(child)
      terminateProbe.expectMsg(child)
    }
  }
}

private object DynamicSupervisorSpec {
  private class TestSupervisor(ref: Option[ActorRef]) extends DynamicSupervisor {
    override protected[this] def onTerminate(child: ActorRef): Unit = ref.foreach(_ ! child)
  }
  private class TestActor(ref: ActorRef) extends Actor {
    ref ! ()

    override def receive: Receive = {
      case _ => sys.error("error")
    }
  }
}
