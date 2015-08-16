package akka.ainterface.util.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.ainterface.test.ActorSpec
import akka.ainterface.util.actor.ForwardSupervisorSpec.TestSupervisor
import akka.testkit.TestProbe

class ForwardSupervisorSpec extends ActorSpec {
  "ForwardSupervisor" should {
    "forward messages to the specified actor" in {
      val supervisor = system.actorOf(Props(classOf[TestSupervisor], testActor, None, None))
      supervisor ! 1
      supervisor ! 2
      supervisor ! 3
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
    }

    "should be able to handle terminates of children" in {
      val terminateProbe = TestProbe()
      val childReceiver = TestProbe()
      system.actorOf(Props(
        classOf[TestSupervisor],
        testActor,
        Some(terminateProbe.ref),
        Some(childReceiver.ref)))

      val child = childReceiver.expectMsgType[ActorRef]
      system.stop(child)
      expectNoMsg()
      terminateProbe.expectMsg(child)
    }
  }
}

object ForwardSupervisorSpec {
  private class TestSupervisor(forwardProbe: ActorRef,
                               terminateProbe: Option[ActorRef],
                               childReceiver: Option[ActorRef]) extends ForwardSupervisor {
    override protected[this] val to: ActorRef = {
      startChild(Props(classOf[TestActor], forwardProbe, childReceiver))
    }
    override protected[this] def onTerminate(child: ActorRef): Unit = {
      terminateProbe.foreach(_ ! child)
    }
  }
  private class TestActor(forwardProbe: ActorRef, childReceiver: Option[ActorRef]) extends Actor {
    childReceiver.foreach(_ ! self)

    override def receive: Receive = {
      case x => forwardProbe ! x
    }
  }
}
