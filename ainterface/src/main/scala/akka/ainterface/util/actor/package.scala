package akka.ainterface.util

import akka.actor.SupervisorStrategy.Escalate
import akka.actor.{AllForOneStrategy, SupervisorStrategy}

package object actor {
  /**
   * Escalates whenever a child fails.
   */
  private[ainterface] val EscalateStrategy: SupervisorStrategy = {
    AllForOneStrategy() {
      case _ => Escalate
    }
  }
}
