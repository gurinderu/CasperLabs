package io.casperlabs.casper

sealed trait BlockStatus {
  val inDag: Boolean
}

final case object Processing extends BlockStatus {
  override val inDag: Boolean = false
}
final case object Processed extends BlockStatus {
  override val inDag: Boolean = true
}

final case class UnexpectedBlockException(ex: Throwable) extends BlockStatus {
  override val inDag: Boolean = false
}

sealed trait ValidBlock extends BlockStatus {
  override val inDag: Boolean = true
}
sealed trait InvalidBlock extends BlockStatus {
  override val inDag: Boolean = false
}
sealed trait Slashable

final case object Valid extends ValidBlock

// AdmissibleEquivocation are blocks that would create an equivocation but are
// pulled in through a justification of another block
final case object AdmissibleEquivocation extends InvalidBlock with Slashable
// TODO: Make IgnorableEquivocation slashable again and remember to add an entry to the equivocation record.
// For now we won't eagerly slash equivocations that we can just ignore,
// as we aren't forced to add it to our view as a dependency.
// TODO: The above will become a DOS vector if we don't fix.
final case object IgnorableEquivocation   extends InvalidBlock
final case object InvalidUnslashableBlock extends InvalidBlock
final case object MissingBlocks           extends InvalidBlock

final case object InvalidBlockNumber    extends InvalidBlock with Slashable
final case object InvalidRepeatDeploy   extends InvalidBlock with Slashable
final case object InvalidParents        extends InvalidBlock with Slashable
final case object InvalidSequenceNumber extends InvalidBlock with Slashable
final case object InvalidChainId        extends InvalidBlock with Slashable
final case object NeglectedInvalidBlock extends InvalidBlock with Slashable
final case object NeglectedEquivocation extends InvalidBlock with Slashable
final case object InvalidTransaction    extends InvalidBlock with Slashable
final case object InvalidPreStateHash   extends InvalidBlock with Slashable
final case object InvalidPostStateHash  extends InvalidBlock with Slashable
final case object InvalidBondsCache     extends InvalidBlock with Slashable
final case object InvalidBlockHash      extends InvalidBlock with Slashable
final case object InvalidDeployCount    extends InvalidBlock with Slashable

object BlockStatus {
  val valid: BlockStatus      = Valid
  val processing: BlockStatus = Processing
  val processed: BlockStatus  = Processed
}
