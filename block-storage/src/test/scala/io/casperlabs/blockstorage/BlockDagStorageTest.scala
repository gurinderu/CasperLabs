package io.casperlabs.blockstorage

import java.nio.file.StandardOpenOption

import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockDagRepresentation.Validator
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.util.byteOps._
import io.casperlabs.casper.consensus.Block
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.blockstorage.blockImplicits._
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps._
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.util.Random

@silent("match may not be exhaustive")
trait BlockDagStorageTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll {
  val scheduler = Scheduler.fixedPool("block-dag-storage-test-scheduler", 4)

  def withDagStorage[R](f: BlockDagStorage[Task] => Task[R]): R

  "DAG Storage" should "be able to lookup a stored block" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      withDagStorage { dagStorage =>
        for {
          _ <- blockElements.traverse_(
                blockMsgWithTransform => dagStorage.insert(blockMsgWithTransform.getBlockMessage)
              )
          dag <- dagStorage.getRepresentation
          blockElementLookups <- blockElements.traverse {
                                  case BlockMsgWithTransform(Some(b), _) =>
                                    for {
                                      blockMetadata <- dag.lookup(b.blockHash)
                                      latestMessageHash <- dag.latestMessageHash(
                                                            b.getHeader.validatorPublicKey
                                                          )
                                      latestMessage <- dag.latestMessage(
                                                        b.getHeader.validatorPublicKey
                                                      )
                                    } yield (blockMetadata, latestMessageHash, latestMessage)
                                }
          latestMessageHashes <- dag.latestMessageHashes
          latestMessages      <- dag.latestMessages
          _                   <- dagStorage.clear()
          _ = blockElementLookups.zip(blockElements).foreach {
            case (
                (blockMetadata, latestMessageHash, latestMessage),
                BlockMsgWithTransform(Some(b), _)
                ) =>
              blockMetadata shouldBe Some(BlockMetadata.fromBlock(b))
              latestMessageHash shouldBe Some(b.blockHash)
              latestMessage shouldBe Some(BlockMetadata.fromBlock(b))
          }
          _      = latestMessageHashes.size shouldBe blockElements.size
          result = latestMessages.size shouldBe blockElements.size
        } yield result
      }
    }
  }
}

@silent("match may not be exhaustive")
class BlockDagFileStorageTest extends BlockDagStorageTest {

  import java.nio.file.{Files, Path}

  private[this] def mkTmpDir(): Path = Files.createTempDirectory("casperlabs-dag-storage-test-")

  def withDagStorageLocation[R](f: (Path, BlockStore[Task]) => Task[R]): R = {
    val testProgram = Sync[Task].bracket {
      Sync[Task].delay {
        (mkTmpDir(), mkTmpDir())
      }
    } {
      case (dagDataDir, blockStoreDataDir) =>
        for {
          blockStore <- createBlockStore(blockStoreDataDir)
          result     <- f(dagDataDir, blockStore)
          _          <- blockStore.close()
        } yield result
    } {
      case (dagDataDir, blockStoreDataDir) =>
        Sync[Task].delay {
          dagDataDir.recursivelyDelete()
          blockStoreDataDir.recursivelyDelete()
        }
    }
    testProgram.unsafeRunSync(scheduler)
  }

  override def withDagStorage[R](f: BlockDagStorage[Task] => Task[R]): R =
    withDagStorageLocation { (dagDataDir, blockStore) =>
      for {
        dagStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
        result     <- f(dagStorage)
        _          <- dagStorage.close()
      } yield result
    }

  private def defaultLatestMessagesLog(dagDataDir: Path): Path =
    dagDataDir.resolve("latest-messages-log")

  private def defaultBlockMetadataLog(dagDataDir: Path): Path =
    dagDataDir.resolve("block-metadata-log")

  private def defaultBlockMetadataCrc(dagDataDir: Path): Path =
    dagDataDir.resolve("block-metadata-crc")

  private def defaultCheckpointsDir(dagDataDir: Path): Path =
    dagDataDir.resolve("checkpoints")

  private def createBlockStore(blockStoreDataDir: Path): Task[BlockStore[Task]] = {
    implicit val log = new Log.NOPLog[Task]()
    implicit val met = new MetricsNOP[Task]
    val env          = Context.env(blockStoreDataDir, 100L * 1024L * 1024L * 4096L)
    FileLMDBIndexBlockStore.create[Task](env, blockStoreDataDir).map(_.right.get)
  }

  private def createAtDefaultLocation(
      dagDataDir: Path,
      maxSizeFactor: Int = 10
  )(implicit blockStore: BlockStore[Task]): Task[BlockDagStorage[Task]] = {
    implicit val log = new shared.Log.NOPLog[Task]()
    implicit val met = new MetricsNOP[Task]
    BlockDagFileStorage.create[Task](
      BlockDagFileStorage.Config(
        dagDataDir,
        maxSizeFactor
      )
    )
  }

  type LookupResult =
    (
        List[
          (
              Option[BlockMetadata],
              Option[BlockHash],
              Option[BlockMetadata],
              Option[Set[BlockHash]],
              Option[Set[BlockHash]],
              Boolean
          )
        ],
        Map[Validator, BlockHash],
        Map[Validator, BlockMetadata],
        Vector[Vector[BlockHash]],
        Vector[Vector[BlockHash]]
    )

  private def lookupElements(
      blockElements: List[BlockMsgWithTransform],
      storage: BlockDagStorage[Task],
      topoSortStartBlockNumber: Long = 0,
      topoSortTailLength: Int = 5
  ): Task[LookupResult] =
    for {
      dag <- storage.getRepresentation
      list <- blockElements.traverse {
               case BlockMsgWithTransform(Some(b), _) =>
                 for {
                   blockMetadata                    <- dag.lookup(b.blockHash)
                   latestMessageHash                <- dag.latestMessageHash(b.getHeader.validatorPublicKey)
                   latestMessage                    <- dag.latestMessage(b.getHeader.validatorPublicKey)
                   children                         <- dag.children(b.blockHash)
                   blocksWithSpecifiedJustification <- dag.justificationToBlocks(b.blockHash)
                   contains                         <- dag.contains(b.blockHash)
                 } yield (
                   blockMetadata,
                   latestMessageHash,
                   latestMessage,
                   children,
                   blocksWithSpecifiedJustification,
                   contains
                 )
             }
      latestMessageHashes <- dag.latestMessageHashes
      latestMessages      <- dag.latestMessages
      topoSort            <- dag.topoSort(topoSortStartBlockNumber)
      topoSortTail        <- dag.topoSortTail(topoSortTailLength)
    } yield (list, latestMessageHashes, latestMessages, topoSort, topoSortTail)

  private def testLookupElementsResult(
      lookupResult: LookupResult,
      blockElements: List[Block],
      topoSortStartBlockNumber: Long = 0,
      topoSortTailLength: Int = 5
  ): Assertion = {
    val (list, latestMessageHashes, latestMessages, topoSort, topoSortTail) = lookupResult
    val realLatestMessages = blockElements.foldLeft(Map.empty[Validator, BlockMetadata]) {
      case (lm, b) =>
        // Ignore empty sender for genesis block
        if (b.getHeader.validatorPublicKey != ByteString.EMPTY)
          lm.updated(b.getHeader.validatorPublicKey, BlockMetadata.fromBlock(b))
        else
          lm
    }
    list.zip(blockElements).foreach {
      case (
          (
            blockMetadata,
            latestMessageHash,
            latestMessage,
            children,
            blocksWithSpecifiedJustification,
            contains
          ),
          b
          ) =>
        blockMetadata shouldBe Some(BlockMetadata.fromBlock(b))
        latestMessageHash shouldBe realLatestMessages
          .get(b.getHeader.validatorPublicKey)
          .map(_.blockHash)
        latestMessage shouldBe realLatestMessages.get(b.getHeader.validatorPublicKey)
        children shouldBe
          Some(
            blockElements
              .filter(_.getHeader.parentHashes.contains(b.blockHash))
              .map(_.blockHash)
              .toSet
          )
        blocksWithSpecifiedJustification shouldBe
          Some(
            blockElements
              .filter(_.getHeader.justifications.map(_.latestBlockHash).contains(b.blockHash))
              .map(_.blockHash)
              .toSet
          )
        contains shouldBe true
    }
    latestMessageHashes shouldBe realLatestMessages.mapValues(_.blockHash)
    latestMessages shouldBe realLatestMessages

    def normalize(topoSort: Vector[Vector[BlockHash]]): Vector[Vector[BlockHash]] =
      if (topoSort.size == 1 && topoSort.head.isEmpty)
        Vector.empty
      else
        topoSort

    val realTopoSort = normalize(Vector(blockElements.map(_.blockHash).toVector))
    topoSort shouldBe realTopoSort.drop(topoSortStartBlockNumber.toInt)
    topoSortTail shouldBe realTopoSort.takeRight(topoSortTailLength)
  }

  it should "be able to restore state on startup" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      withDagStorageLocation { (dagDataDir, blockStore) =>
        for {
          firstStorage  <- createAtDefaultLocation(dagDataDir)(blockStore)
          _             <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
          _             <- firstStorage.close()
          secondStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
          result        <- lookupElements(blockElements, secondStorage)
          _             <- secondStorage.close()
        } yield testLookupElementsResult(result, blockElements.flatMap(_.blockMessage))
      }
    }
  }

  it should "be able to restore latest messages with genesis with empty sender field" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      val blockElementsWithGenesis = blockElements match {
        case x :: xs =>
          val block = x.getBlockMessage
          val genesis = x.withBlockMessage(
            block.withHeader(block.getHeader.withValidatorPublicKey(ByteString.EMPTY))
          )
          genesis :: xs
        case Nil =>
          Nil
      }
      withDagStorageLocation { (dagDataDir, blockStore) =>
        for {
          firstStorage  <- createAtDefaultLocation(dagDataDir)(blockStore)
          _             <- blockElementsWithGenesis.traverse_(b => firstStorage.insert(b.getBlockMessage))
          _             <- firstStorage.close()
          secondStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
          result        <- lookupElements(blockElementsWithGenesis, secondStorage)
          _             <- secondStorage.close()
        } yield testLookupElementsResult(result, blockElementsWithGenesis.flatMap(_.blockMessage))
      }
    }
  }

  it should "be able to restore state from the previous two instances" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { firstBlockElements =>
      forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { secondBlockElements =>
        withDagStorageLocation { (dagDataDir, blockStore) =>
          for {
            firstStorage  <- createAtDefaultLocation(dagDataDir)(blockStore)
            _             <- firstBlockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
            _             <- firstStorage.close()
            secondStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
            _             <- secondBlockElements.traverse_(b => secondStorage.insert(b.getBlockMessage))
            _             <- secondStorage.close()
            thirdStorage  <- createAtDefaultLocation(dagDataDir)(blockStore)
            result        <- lookupElements(firstBlockElements ++ secondBlockElements, thirdStorage)
            _             <- thirdStorage.close()
          } yield testLookupElementsResult(
            result,
            (firstBlockElements ++ secondBlockElements).flatMap(_.blockMessage)
          )
        }
      }
    }
  }

  it should "be able to restore latest messages on startup with appended 64 garbage bytes" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      withDagStorageLocation { (dagDataDir, blockStore) =>
        for {
          firstStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
          _            <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
          _            <- firstStorage.close()
          garbageBytes = Array.fill[Byte](64)(0)
          _            <- Sync[Task].delay { Random.nextBytes(garbageBytes) }
          _ <- Sync[Task].delay {
                Files.write(
                  defaultLatestMessagesLog(dagDataDir),
                  garbageBytes,
                  StandardOpenOption.APPEND
                )
              }
          secondStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
          result        <- lookupElements(blockElements, secondStorage)
          _             <- secondStorage.close()
        } yield testLookupElementsResult(result, blockElements.flatMap(_.blockMessage))
      }
    }
  }

  it should "be able to restore data lookup on startup with appended garbage block metadata" in {
    forAll(blockElementsWithParentsGen, blockMsgWithTransformGen, minSize(0), sizeRange(10)) {
      (blockElements, garbageBlock) =>
        withDagStorageLocation { (dagDataDir, blockStore) =>
          for {
            firstStorage      <- createAtDefaultLocation(dagDataDir)(blockStore)
            _                 <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
            _                 <- firstStorage.close()
            garbageByteString = BlockMetadata.fromBlock(garbageBlock.getBlockMessage).toByteString
            garbageBytes      = garbageByteString.size.toByteString.concat(garbageByteString).toByteArray
            _ <- Sync[Task].delay {
                  Files.write(
                    defaultBlockMetadataLog(dagDataDir),
                    garbageBytes,
                    StandardOpenOption.APPEND
                  )
                }
            secondStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
            result        <- lookupElements(blockElements, secondStorage)
            _             <- secondStorage.close()
          } yield testLookupElementsResult(result, blockElements.flatMap(_.blockMessage))
        }
    }
  }

  it should "be able to handle fully corrupted latest messages log file" in withDagStorageLocation {
    (dagDataDir, blockStore) =>
      val garbageBytes = Array.fill[Byte](789)(0)
      for {
        _                   <- Sync[Task].delay { Random.nextBytes(garbageBytes) }
        _                   <- Sync[Task].delay { Files.write(defaultLatestMessagesLog(dagDataDir), garbageBytes) }
        storage             <- createAtDefaultLocation(dagDataDir)(blockStore)
        dag                 <- storage.getRepresentation
        latestMessageHashes <- dag.latestMessageHashes
        latestMessages      <- dag.latestMessages
        _                   <- storage.close()
        _                   = latestMessageHashes.size shouldBe 0
        result              = latestMessages.size shouldBe 0
      } yield result
  }

  it should "be able to restore after squashing latest messages" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      forAll(
        blockWithNewHashesGen(blockElements.flatMap(_.blockMessage)),
        blockWithNewHashesGen(blockElements.flatMap(_.blockMessage))
      ) { (secondBlockElements, thirdBlockElements) =>
        withDagStorageLocation { (dagDataDir, blockStore) =>
          for {
            firstStorage  <- createAtDefaultLocation(dagDataDir, 2)(blockStore)
            _             <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
            _             <- secondBlockElements.traverse_(firstStorage.insert)
            _             <- thirdBlockElements.traverse_(firstStorage.insert)
            _             <- firstStorage.close()
            secondStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
            result        <- lookupElements(blockElements, secondStorage)
            _             <- secondStorage.close()
          } yield testLookupElementsResult(
            result,
            blockElements
              .flatMap(_.blockMessage)
              .toList ++ secondBlockElements ++ thirdBlockElements
          )
        }
      }
    }
  }

  it should "be able to load checkpoints" in {
    forAll(blockElementsWithParentsGen, minSize(1), sizeRange(2)) { blockElements =>
      withDagStorageLocation { (dagDataDir, blockStore) =>
        for {
          firstStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
          _ <- blockElements.traverse_(
                b =>
                  blockStore.put(b.getBlockMessage.blockHash, b) *> firstStorage.insert(
                    b.getBlockMessage
                  )
              )
          _ <- firstStorage.close()
          _ <- Sync[Task].delay {
                Files.move(
                  defaultBlockMetadataLog(dagDataDir),
                  defaultCheckpointsDir(dagDataDir).resolve("0-1")
                )
                Files.delete(defaultBlockMetadataCrc(dagDataDir))
              }
          secondStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
          result        <- lookupElements(blockElements, secondStorage)
          _             <- secondStorage.close()
        } yield testLookupElementsResult(
          result,
          blockElements.flatMap(_.blockMessage)
        )
      }
    }
  }

  it should "be able to clear and continue working" in {
    forAll(blockElementsWithParentsGen, minSize(1), sizeRange(2)) { blockElements =>
      withDagStorageLocation { (dagDataDir, blockStore) =>
        for {
          firstStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
          _ <- blockElements.traverse_(
                b =>
                  blockStore.put(b.getBlockMessage.blockHash, b) *> firstStorage.insert(
                    b.getBlockMessage
                  )
              )
          _             = firstStorage.close()
          secondStorage <- createAtDefaultLocation(dagDataDir)(blockStore)
          elements      <- lookupElements(blockElements, secondStorage)
          _ = testLookupElementsResult(
            elements,
            blockElements.flatMap(_.blockMessage)
          )
          _      <- secondStorage.clear()
          _      <- blockStore.clear()
          result <- lookupElements(blockElements, secondStorage)
          _      <- secondStorage.close()
        } yield result match {
          case (list, latestMessageHashes, latestMessages, topoSort, topoSortTail) => {
            list.foreach(_ shouldBe ((None, None, None, None, None, false)))
            latestMessageHashes shouldBe Map()
            latestMessages shouldBe Map()
            topoSort shouldBe Vector()
            topoSortTail shouldBe Vector()
          }
        }
      }
    }
  }
}
