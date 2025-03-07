package io.casperlabs.casper.util.comm

import cats.data.EitherT
import cats.effect.concurrent.Ref
import cats.effect.Sync
import cats.implicits._
import cats.{FlatMap, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.casper.LastApprovedBlock.LastApprovedBlock
import io.casperlabs.casper.protocol._
import io.casperlabs.casper._
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.MonadTrans
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._

import scala.concurrent.duration._
import scala.language.higherKinds

/**
  * Bootstrap side of the protocol defined in
  * https://rchain.atlassian.net/wiki/spaces/CORE/pages/485556483/Initializing+the+Blockchain+--+Protocol+for+generating+the+Genesis+block
  */
trait ApproveBlockProtocol[F[_]] {
  def addApproval(a: BlockApproval): F[Unit]
  def run(): F[Unit]
}

abstract class ApproveBlockProtocolInstances {
  implicit def eitherTApproveBlockProtocol[E, F[_]: Monad: ApproveBlockProtocol[?[_]]]
      : ApproveBlockProtocol[EitherT[F, E, ?]] =
    ApproveBlockProtocol.forTrans[F, EitherT[?[_], E, ?]]
}

object ApproveBlockProtocol {
  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](
      implicit C: ApproveBlockProtocol[F]
  ): ApproveBlockProtocol[T[F, ?]] =
    new ApproveBlockProtocol[T[F, ?]] {
      override def addApproval(a: BlockApproval): T[F, Unit] = C.addApproval(a).liftM[T]
      override def run(): T[F, Unit]                         = C.run().liftM[T]
    }

  def apply[F[_]](implicit instance: ApproveBlockProtocol[F]): ApproveBlockProtocol[F] = instance

  //For usage in tests only
  def unsafe[F[_]: Sync: ConnectionsCell: TransportLayer: Log: Time: Metrics: RPConfAsk: LastApprovedBlock](
      block: BlockMessage,
      transforms: Seq[TransformEntry],
      trustedValidators: Set[ByteString],
      requiredSigs: Int,
      duration: FiniteDuration,
      interval: FiniteDuration,
      sigsF: Ref[F, Set[Signature]],
      start: Long
  ): ApproveBlockProtocol[F] =
    new ApproveBlockProtocolImpl[F](
      block,
      transforms,
      requiredSigs,
      trustedValidators,
      start,
      duration,
      interval,
      sigsF
    )

  def of[F[_]: Sync: ConnectionsCell: TransportLayer: Log: Time: Metrics: RPConfAsk: LastApprovedBlock](
      block: BlockMessage,
      transforms: Seq[TransformEntry],
      trustedValidators: Set[ByteString],
      requiredSigs: Int,
      duration: FiniteDuration,
      interval: FiniteDuration
  ): F[ApproveBlockProtocol[F]] =
    for {
      now   <- Time[F].currentMillis
      sigsF <- Ref.of[F, Set[Signature]](Set.empty)
    } yield new ApproveBlockProtocolImpl[F](
      block,
      transforms,
      requiredSigs,
      trustedValidators,
      now,
      duration,
      interval,
      sigsF
    )

  private class ApproveBlockProtocolImpl[F[_]: Sync: ConnectionsCell: TransportLayer: Log: Time: Metrics: RPConfAsk: LastApprovedBlock](
      val block: BlockMessage,
      val transforms: Seq[TransformEntry],
      val requiredSigs: Int,
      val trustedValidators: Set[ByteString],
      val start: Long,
      val duration: FiniteDuration,
      val interval: FiniteDuration,
      private val sigsF: Ref[F, Set[Signature]]
  ) extends ApproveBlockProtocol[F] {
    private implicit val logSource: LogSource = LogSource(this.getClass)
    private implicit val metricsSource: Metrics.Source =
      Metrics.Source(CasperMetricsSource, "approve-block")

    private val candidate                 = ApprovedBlockCandidate(Some(block), requiredSigs)
    private val u                         = UnapprovedBlock(Some(candidate), start, duration.toMillis)
    private val serializedUnapprovedBlock = u.toByteString
    private val candidateHash             = PrettyPrinter.buildString(block.blockHash)
    private val sigData                   = Blake2b256.hash(candidate.toByteArray)

    def addApproval(a: BlockApproval): F[Unit] = {
      val validSig = for {
        _   <- a.candidate.filter(_ == this.candidate)
        sig <- a.sig
        if Validate.signature(sigData, sig)
      } yield sig

      val trustedValidator =
        if (signedByTrustedValidator(a)) {
          true.pure[F]
        } else {
          Log[F].warn(s"APPROVAL: Received BlockApproval from untrusted validator.") *> false
            .pure[F]
        }

      val isValid = for {
        validValidators <- trustedValidator
      } yield validValidators && validSig.isDefined

      val sender =
        a.sig.fold("<Empty Signature>")(sig => Base16.encode(sig.publicKey.toByteArray))

      FlatMap[F].ifM(isValid)(
        for {
          before <- sigsF.get
          _      <- sigsF.update(_ + validSig.get)
          after  <- sigsF.get
          _ <- if (after > before)
                Metrics[F].incrementCounter("genesis")
              else ().pure[F]
          _ <- Log[F].info(s"APPROVAL: received block approval from $sender")
        } yield (),
        Log[F].warn(s"APPROVAL: ignoring invalid block approval from $sender")
      )
    }

    private def signedByTrustedValidator(a: BlockApproval): Boolean =
      a.sig.fold(false)(s => trustedValidators.contains(s.publicKey))

    def run(): F[Unit] = internalRun()

    private def internalRun(): F[Unit] =
      for {
        _    <- sendUnapprovedBlock
        t    <- Time[F].currentMillis
        sigs <- sigsF.get
        _    <- completeIf(t, sigs)
      } yield ()

    //TODO: potential optimization, only send to peers we have not
    //      received a valid signature from yet
    private def sendUnapprovedBlock: F[Unit] =
      for {
        _ <- Log[F].info(s"APPROVAL: Beginning send of UnapprovedBlock $candidateHash to peers...")
        _ <- CommUtil.streamToPeers[F](transport.UnapprovedBlock, serializedUnapprovedBlock)
        _ <- Log[F].info(s"APPROVAL: Sent UnapprovedBlock $candidateHash to peers.")
      } yield ()

    private def completeIf(time: Long, signatures: Set[Signature]): F[Unit] =
      if ((time >= start + duration.toMillis && signatures.size >= requiredSigs) || requiredSigs == 0) {
        for {
          _ <- LastApprovedBlock[F].set(
                ApprovedBlockWithTransforms(
                  ApprovedBlock(Some(candidate), signatures.toSeq),
                  transforms
                )
              )
          _ <- sendApprovedBlock
        } yield ()
      } else Time[F].sleep(interval) >> internalRun()

    private def sendApprovedBlock: F[Unit] =
      for {
        apbO <- LastApprovedBlock[F].get
        _ <- apbO match {
              case None =>
                Log[F].warn(s"APPROVAL: Expected ApprovedBlock but was None.")
              case Some(b) =>
                val serializedApprovedBlock = b.approvedBlock.toByteString
                for {
                  _ <- Log[F].info(
                        s"APPROVAL: Beginning send of ApprovedBlock $candidateHash to peers..."
                      )
                  _ <- CommUtil.streamToPeers[F](transport.ApprovedBlock, serializedApprovedBlock)
                  _ <- Log[F].info(s"APPROVAL: Sent ApprovedBlock $candidateHash to peers.")
                } yield ()
            }
      } yield ()
  }
}
