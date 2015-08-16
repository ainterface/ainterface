package akka.ainterface.local

import akka.actor.ActorRef
import akka.ainterface.datatype.ErlPid
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}

/**
 * A process table.
 * Processes are associated with pids in this table.
 */
private[local] final class LocalProcessTable {
  private[this] val underlying: ConcurrentMap[ErlPid, ActorRef] = {
    new ConcurrentHashMap[ErlPid, ActorRef]()
  }

  /**
   * Looks up the process associated with the pid.
   */
  def lookup(pid: ErlPid): Option[ActorRef] = Option(underlying.get(pid))

  /**
   * Associates the pid with the process.
   * @return true if ok, false if another process is already associated with the pid.
   */
  def add(pid: ErlPid, process: ActorRef): Boolean = underlying.putIfAbsent(pid, process) == null

  /**
   * Removes the process associated with the pid.
   */
  def remove(pid: ErlPid): Unit = underlying.remove(pid)
}
