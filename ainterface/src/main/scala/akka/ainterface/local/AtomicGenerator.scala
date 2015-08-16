package akka.ainterface.local

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/**
 * Generates an element of an infinite sequence.
 */
private[local] final class AtomicGenerator[A](zero: A, f: A => A) {
  private[this] val ref: AtomicReference[A] = new AtomicReference[A](zero)

  @tailrec
  def next(): A = {
    val current = ref.get()
    if (ref.compareAndSet(current, f(current))) current else next()
  }

  @tailrec
  def update(f: A => A): Unit = {
    val current = ref.get()
    val updated = f(current)
    if (!ref.compareAndSet(current, updated)) update(f)
  }
}

private[local] object AtomicGenerator {
  def apply[A](zero: A)(f: A => A): AtomicGenerator[A] = new AtomicGenerator[A](zero, f)
}
