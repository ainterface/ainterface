package akka.ainterface.remote.epmd.client

import akka.actor.{ActorRef, Props}
import akka.ainterface.NodeName
import akka.ainterface.remote.epmd.client.EpmdClient.{Data, Normal, PoolInitializing}
import akka.ainterface.remote.epmd.client.EpmdClientProtocol.GetPort
import akka.ainterface.test.ActorSpec
import akka.ainterface.util.actor.DynamicSupervisorProtocol.{ChildRef, StartChild}
import akka.ainterface.util.collection.BiMap
import akka.testkit.{TestFSMRef, TestProbe}
import scala.concurrent.duration._

class EpmdClientSpec extends ActorSpec {
  private[this] val alive = "ainterface"
  private[this] val host = "akka.ainterface"
  private[this] def setup(workerPoolSupSup: ActorRef = nullRef, tcpClient: ActorRef = nullRef) = {
    TestFSMRef(new EpmdClient(workerPoolSupSup, tcpClient, 2000.millis))
  }

  "EpmdClient" when {
    "Normal" should {
      "send requests to a worker pool" when {
        "the target pool is cached" in {
          val pool = TestProbe()
          val client = setup()
          val workers = BiMap.empty[String, ActorRef] + (host -> pool.ref)
          client.setState(Normal, Data(workers))

          client ! GetPort(testActor, NodeName(alive, host))
          pool.expectMsg(EpmdWorkerPoolProtocol.PortPlease(testActor, alive))
          assert(client.stateName === Normal)
        }
      }

      "start a worker pool and stash the message" when {
        "the target pool is not cached" in {
          val workerPoolSupSup = TestProbe()
          val tcpClient = TestProbe()
          val client = setup(workerPoolSupSup.ref, tcpClient.ref)
          client.setState(Normal)

          client ! GetPort(testActor, NodeName(alive, host))
          val props = EpmdWorkerPoolSupervisor.props(host, tcpClient.ref)
          val name = s"worker-pool-supervisor-$host"
          workerPoolSupSup.expectMsg(StartChild(client, props, name))
          expectNoMsg(shortDuration)
          awaitAssert(client.stateName === PoolInitializing)
          awaitAssert(client.stateData === Data(initializingHost = Some(host)))
          awaitAssert(client.isStateTimerActive)
        }
      }
    }

    "PoolInitializing" should {
      "stash normal messages and unstash all the messages and go to Normal" when {
        "it receives the child" in {
          val alive1 = "ainterface1"
          val alive2 = "ainterface2"
          val alive3 = "ainterface3"

          val workerPoolSupSup = TestProbe()
          val tcpClient = TestProbe()
          val client = setup(workerPoolSupSup.ref, tcpClient.ref)
          client.setState(Normal)

          client ! GetPort(testActor, NodeName(alive1, host))
          val props = EpmdWorkerPoolSupervisor.props(host, tcpClient.ref)
          val name = s"worker-pool-supervisor-$host"
          workerPoolSupSup.expectMsg(StartChild(client, props, name))
          assert(client.stateName === PoolInitializing)

          client ! GetPort(testActor, NodeName(alive2, host))
          client ! GetPort(testActor, NodeName(alive3, host))
          assert(client.stateName === PoolInitializing)
          expectNoMsg(shortDuration)

          val worker = TestProbe()
          workerPoolSupSup.send(client, ChildRef(worker.ref, None))
          assert(client.stateName === Normal)

          worker.expectMsg(EpmdWorkerPoolProtocol.PortPlease(testActor, alive1))
          worker.expectMsg(EpmdWorkerPoolProtocol.PortPlease(testActor, alive2))
          worker.expectMsg(EpmdWorkerPoolProtocol.PortPlease(testActor, alive3))
          assert(client.stateName === Normal)
          val workers = BiMap.empty + (host -> worker.ref)
          assert(client.stateData === Data(workers))
          assert(!client.isStateTimerActive)
        }

        "it times out" in {
          val workerPoolSupSup = TestProbe()
          val tcpClient = TestProbe()
          val clientProps = Props(
            classOf[EpmdClient],
            workerPoolSupSup.ref,
            tcpClient.ref,
            shortDuration
          )
          val client = system.actorOf(clientProps)

          client ! GetPort(testActor, NodeName(alive, host))
          val props = EpmdWorkerPoolSupervisor.props(host, tcpClient.ref)
          val name = s"worker-pool-supervisor-$host"
          workerPoolSupSup.expectMsg(StartChild(client, props, name))

          client ! GetPort(testActor, NodeName(alive, host))
          client ! GetPort(testActor, NodeName(alive, host))

          workerPoolSupSup.expectMsg(StartChild(client, props, name))
          workerPoolSupSup.expectMsg(StartChild(client, props, name))
        }
      }
    }

    "anytime" should {
      "remove a pool from cache" when {
        "the pool terminates" in {
          val host1 = "akka.ainterface1"
          val host2 = "akka.ainterface2"
          val tcpClient = TestProbe()
          val client = setup(testActor, tcpClient.ref)
          val zero = BiMap.empty[String, ActorRef]
          client.setState(Normal, Data(zero))

          client ! GetPort(testActor, NodeName(alive, host1))
          val props1 = EpmdWorkerPoolSupervisor.props(host1, tcpClient.ref)
          val name1 = s"worker-pool-supervisor-$host1"
          expectMsg(StartChild(client, props1, name1))
          val pool1 = TestProbe()
          client ! ChildRef(pool1.ref, None)
          pool1.expectMsg(EpmdWorkerPoolProtocol.PortPlease(testActor, alive))

          client ! GetPort(testActor, NodeName(alive, host2))
          val props2 = EpmdWorkerPoolSupervisor.props(host2, tcpClient.ref)
          val name2 = s"worker-pool-supervisor-$host2"
          expectMsg(StartChild(client, props2, name2))
          val pool2 = TestProbe()
          client ! ChildRef(pool2.ref, None)
          pool2.expectMsg(EpmdWorkerPoolProtocol.PortPlease(testActor, alive))

          val full = zero + (host1 -> pool1.ref) + (host2 -> pool2.ref)
          assert(client.stateData === Data(full))

          system.stop(pool1.ref)
          awaitAssert(assert(client.stateData === Data(zero + (host2 -> pool2.ref))))

          system.stop(pool2.ref)
          awaitAssert(assert(client.stateData === Data(zero)))
        }
      }
    }
  }
}
