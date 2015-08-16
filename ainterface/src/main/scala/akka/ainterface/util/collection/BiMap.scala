package akka.ainterface.util.collection

/**
 * A bidirectional map.
 */
private[ainterface] final case class BiMap[K, V] private (_delegate: Map[K, V], _inverse: Map[V, K]) {
  def inverse: BiMap[V, K] = new BiMap[V, K](_inverse, _delegate)

  def get(key: K): Option[V] = _delegate.get(key)

  def contains(key: K): Boolean = get(key).isDefined

  def +(kv: (K, V)): BiMap[K, V] = {
    val (key, value) = kv
    inverse.get(value) match {
      case Some(associated) if associated == key => this
      case Some(_) =>
        throw new IllegalArgumentException(s"$value is already associated another key.")
      case None =>
        val removed = _delegate.get(key) match {
          case None => _inverse
          case Some(v) => _inverse - v
        }
        new BiMap[K, V](_delegate.updated(key, value), removed.updated(value, key))
    }
  }

  def -(key: K): BiMap[K, V] = {
    _delegate.get(key) match {
      case None => this
      case Some(value) => new BiMap[K, V](_delegate - key, _inverse - value)
    }
  }

  def keys: Iterable[K] = _delegate.keys

  def values: Iterable[V] = _delegate.values
}

private[ainterface] object BiMap {
  def empty[K, V]: BiMap[K, V] = new BiMap[K, V](Map.empty, Map.empty)
}
