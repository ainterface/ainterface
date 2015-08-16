package akka.ainterface.local

import akka.ainterface.datatype.{ErlAtom, ErlPid}
import akka.ainterface.util.collection.BiMap

/**
 * A process registry.
 * Names are associated with pids.
 */
private[local] final class LocalNameRegistry {
  private[local] var underlying: BiMap[ErlAtom, ErlPid] = BiMap.empty
  private[this] def inverse: BiMap[ErlPid, ErlAtom] = underlying.inverse

  /**
   * Returns the pid with the registered name.
   */
  def whereis(name: ErlAtom): Option[ErlPid] = underlying.get(name)

  /**
   * Returns a list of names.
   */
  def registered: Iterable[ErlAtom] = underlying.keys

  /**
   * Associates the name with the pid.
   * @return true if ok,
   *         false if name of pid is already associated with the another pid or name.
   */
  def register(name: ErlAtom, pid: ErlPid): Boolean = synchronized {
    underlying.get(name) match {
      case Some(_) => false
      case None if inverse.contains(pid) => false
      case None =>
        underlying = underlying + (name -> pid)
        true
    }
  }

  /**
   * Removes the registered name and the pid associated with it.
   * @return true if ok,
   *         false if the name is not registered.
   */
  def unregister(name: ErlAtom): Boolean = synchronized {
    underlying.get(name) match {
      case None => false
      case Some(pid) =>
        underlying = underlying - name
        true
    }
  }

  /**
   * Removes the registered names associated with the pid.
   */
  def exit(pid: ErlPid): Unit = synchronized {
    underlying = (inverse - pid).inverse
  }
}
