package akka.ainterface.util.actor

import akka.actor.ActorPath
import java.util.UUID

private[ainterface] object ActorPathUtil {
  def orUUID(s: String): String = {
    if (ActorPath.isValidPathElement(s)) s else UUID.randomUUID().toString
  }
}
