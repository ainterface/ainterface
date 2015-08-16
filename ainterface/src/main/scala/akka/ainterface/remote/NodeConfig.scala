package akka.ainterface.remote

import akka.ainterface.NodeName

/**
 * Erlang distribution flags.
 */
private[ainterface] final case class DFlags(value: Int) {
  import DFlags._

  private[this] def isSet(i: Int): Boolean = (value & i) == i

  def published: Boolean = isSet(DFlagPublished)

  def isHidden: Boolean = !published

  /**
   * This is required.
   */
  def acceptsExtendedReferences: Boolean = isSet(DFlagExtendedReferences)

  /**
   * This is required.
   */
  def acceptsExtendedPidsPorts: Boolean = isSet(DFlagExtendedPidsPorts)

  def hide: DFlags = {
    if (isHidden) this else DFlags(value - DFlagPublished)
  }
}

private[ainterface] object DFlags {
  def hidden: DFlags = DFlags(DefaultFlags)

  def published: DFlags = DFlags(DFlagPublished | DefaultFlags)

  /**
   * @see [[http://erlang.org/doc/apps/erts/erl_dist_protocol.html#id97443]]
   */
  private val DFlagPublished = 1
  //private val DFlagAtomCache = 2
  private val DFlagExtendedReferences = 4
  private val DFlagDistMonitor = 8
  //private val DFlagFunTags = 0x10
  private val DFlagDistMonitorName = 0x20
  //private val DFlagHiddenAtomCache = 0x40
  private val DFlagNewFunTags = 0x80
  private val DFlagExtendedPidsPorts = 0x100
  private val DFlagExportPtrTag = 0x200
  private val DFlagBitBinaries = 0x400
  private val DFlagNewFloats = 0x800
  //private val DFlagUnicodeIO = 0x1000
  //private val DFlagDistHdrAtomCache = 0x2000
  //private val DFlagSmallAtomTags = 0x4000
  //private val DFlagUTF8Atoms = 0x10000
  private val DFlagMapTag = 0x20000

  private val DefaultFlags = DFlagExportPtrTag |
    DFlagExtendedPidsPorts |
    DFlagExtendedReferences |
    DFlagDistMonitor |
    //DFlagFunTags |
    DFlagDistMonitorName |
    //DFlagHiddenAtomCache |
    DFlagNewFunTags |
    DFlagBitBinaries |
    DFlagNewFloats |
    //DFlagUnicodeIO |
    //DFlagDistHdrAtomCache |
    //DFlagSmallAtomTags |
    //DFlagUTF8Atoms |
    DFlagMapTag
}

/**
 * Configurations about Erlang node.
 */
private[ainterface] final case class NodeConfig(nodeName: NodeName,
                                            version: Int,
                                            flags: DFlags)
