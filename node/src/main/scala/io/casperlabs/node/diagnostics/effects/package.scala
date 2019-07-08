package io.casperlabs.node.diagnostics

import java.lang.management.{ManagementFactory, MemoryType}

import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.empty.Empty
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.comm.rp.Connect.ConnectionsCell
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.diagnostics._
import javax.management.ObjectName
import monix.eval.Task

import scala.collection.JavaConverters._

package object effects {

  def jvmMetrics[F[_]: Sync]: JvmMetrics[F] =
    new JvmMetrics[F] {

      private def check[A](a: A)(implicit n: Numeric[A]): Option[A] = checkOpt(Some(a))

      private def checkOpt[A](a: Option[A])(implicit n: Numeric[A]): Option[A] =
        a.filter(n.gteq(_, n.zero))

      def processCpu: F[ProcessCpu] =
        Sync[F].delay {
          ManagementFactory.getOperatingSystemMXBean match {
            case b: com.sun.management.OperatingSystemMXBean =>
              ProcessCpu(check(b.getProcessCpuLoad), check(b.getProcessCpuTime))
            case _ => ProcessCpu()
          }
        }

      def memoryUsage: F[MemoryUsage] =
        Sync[F].delay {
          val b       = ManagementFactory.getMemoryMXBean
          val heap    = b.getHeapMemoryUsage
          val nonHeap = b.getNonHeapMemoryUsage
          MemoryUsage(
            Some(
              Memory(
                committed = heap.getCommitted,
                init = heap.getInit,
                max = check(heap.getMax),
                used = heap.getUsed
              )
            ),
            Some(
              Memory(
                committed = nonHeap.getCommitted,
                init = nonHeap.getInit,
                max = check(nonHeap.getMax),
                used = nonHeap.getUsed
              )
            )
          )
        }

      def garbageCollectors: F[Seq[GarbageCollector]] =
        Sync[F].delay {
          ManagementFactory.getGarbageCollectorMXBeans.asScala.map {
            case b: com.sun.management.GarbageCollectorMXBean =>
              val last = Option(b.getLastGcInfo)
              GarbageCollector(
                name = b.getName,
                totalCollections = b.getCollectionCount,
                totalCollectionTime = b.getCollectionTime,
                startTime = checkOpt(last.map(_.getStartTime)),
                endTime = checkOpt(last.map(_.getEndTime)),
                duration = checkOpt(last.map(_.getDuration))
              )
            case b =>
              GarbageCollector(
                name = b.getName,
                totalCollections = b.getCollectionCount,
                totalCollectionTime = b.getCollectionTime
              )
          }
        }

      def memoryPools: F[Seq[MemoryPool]] =
        Sync[F].delay {
          ManagementFactory.getMemoryPoolMXBeans.asScala.map { b =>
            val usage     = b.getUsage
            val peakUsage = b.getPeakUsage
            MemoryPool(
              name = b.getName,
              poolType = b.getType match {
                case MemoryType.HEAP     => "HEAP"
                case MemoryType.NON_HEAP => "NON_HEAP"
              },
              usage = Some(
                Memory(
                  committed = usage.getCommitted,
                  init = usage.getInit,
                  max = check(usage.getMax),
                  used = usage.getUsed
                )
              ),
              peakUsage = Some(
                Memory(
                  committed = peakUsage.getCommitted,
                  init = peakUsage.getInit,
                  max = check(peakUsage.getMax),
                  used = peakUsage.getUsed
                )
              )
            )
          }
        }

      def threads: F[Threads] =
        Sync[F].delay {
          val b = ManagementFactory.getThreadMXBean
          Threads(
            threadCount = b.getThreadCount,
            daemonThreadCount = b.getDaemonThreadCount,
            peakThreadCount = b.getPeakThreadCount,
            totalStartedThreadCount = b.getTotalStartedThreadCount
          )
        }
    }

  def nodeCoreMetrics[F[_]: Sync]: NodeMetrics[F] =
    new NodeMetrics[F] {
      private val mbs  = ManagementFactory.getPlatformMBeanServer
      private val name = ObjectName.getInstance(NodeMXBean.Name)

      private def getValue(map: Map[String, Long], name: String): Long =
        map.getOrElse(name, 0)

      def metrics: F[NodeCoreMetrics] =
        Sync[F].delay {
          val map = mbs
            .getAttributes(name, NodeMXBean.Attributes)
            .asList
            .asScala
            .map(a => a.getName -> a.getValue.asInstanceOf[Long])
            .toMap

          NodeCoreMetrics(
            pingReceiverCount = getValue(map, NodeMXBean.PingReceiverCount),
            lookupReceiverCount = getValue(map, NodeMXBean.LookupReceiverCount),
            disconnectReceiverCount = getValue(map, NodeMXBean.DisconnectReceiverCount),
            connects = getValue(map, NodeMXBean.Connects),
            p2PEncryptionHandshakeReceiverCount =
              getValue(map, NodeMXBean.P2pEncryptionHandshakeReceiverCount),
            p2PProtocolHandshakeReceiverCount =
              getValue(map, NodeMXBean.P2pProtocolHandshakeReceiverCount),
            peers = getValue(map, NodeMXBean.Peers),
            from = getValue(map, NodeMXBean.From),
            to = getValue(map, NodeMXBean.To)
          )
        }
    }

  def metrics[F[_]: Sync]: Metrics[F] =
    new Metrics[F] {
      import kamon._

      private val m = scala.collection.concurrent.TrieMap[String, metric.Metric[_]]()

      private def source(name: String)(implicit ev: Metrics.Source): String = s"$ev.$name"

      def incrementCounter(name: String, delta: Long)(implicit ev: Metrics.Source): F[Unit] =
        Sync[F].delay {
          m.getOrElseUpdate(source(name), Kamon.counter(source(name))) match {
            case c: metric.Counter => c.increment(delta)
          }
        }

      def incrementSampler(name: String, delta: Long)(implicit ev: Metrics.Source): F[Unit] =
        Sync[F].delay {
          m.getOrElseUpdate(source(name), Kamon.rangeSampler(source(name))) match {
            case c: metric.RangeSampler => c.increment(delta)
          }
        }

      def sample(name: String)(implicit ev: Metrics.Source): F[Unit] =
        Sync[F].delay {
          m.getOrElseUpdate(source(name), Kamon.rangeSampler(source(name))) match {
            case c: metric.RangeSampler => c.sample
          }
        }

      def setGauge(name: String, value: Long)(implicit ev: Metrics.Source): F[Unit] =
        Sync[F].delay {
          m.getOrElseUpdate(source(name), Kamon.gauge(source(name))) match {
            case c: metric.Gauge => c.set(value)
          }
        }

      def incrementGauge(name: String, delta: Long)(implicit ev: Metrics.Source): F[Unit] =
        Sync[F].delay {
          m.getOrElseUpdate(source(name), Kamon.gauge(source(name))) match {
            case c: metric.Gauge => c.increment(delta)
          }
        }

      def decrementGauge(name: String, delta: Long)(implicit ev: Metrics.Source): F[Unit] =
        Sync[F].delay {
          m.getOrElseUpdate(source(name), Kamon.gauge(source(name))) match {
            case c: metric.Gauge => c.decrement(delta)
          }
        }

      def record(name: String, value: Long, count: Long = 1)(implicit ev: Metrics.Source): F[Unit] =
        Sync[F].delay {
          m.getOrElseUpdate(source(name), Kamon.histogram(source(name))) match {
            case c: metric.Histogram => c.record(value, count)
          }
        }

      def timer[A](name: String)(block: F[A])(implicit ev: Metrics.Source): F[A] =
        m.getOrElseUpdate(source(name), Kamon.timer(source(name))) match {
          case c: metric.Timer =>
            for {
              t  <- Sync[F].delay(c.start())
              r0 <- Sync[F].attempt(block)
              _  = t.stop()
              r  <- r0.fold(Sync[F].raiseError[A], Sync[F].pure)
            } yield r
        }
    }

  def diagnosticsService(
      implicit nodeDiscovery: NodeDiscovery[Task],
      jvmMetrics: JvmMetrics[Task],
      nodeMetrics: NodeMetrics[Task],
      connectionsCell: ConnectionsCell[Task]
  ): DiagnosticsGrpcMonix.Diagnostics =
    new DiagnosticsGrpcMonix.Diagnostics {
      def listPeers(request: Empty): Task[Peers] =
        connectionsCell.read.map { ps =>
          Peers(
            ps.map(
              p => Peer(p.host, p.protocolPort, p.id)
            )
          )
        }

      def listDiscoveredPeers(request: Empty): Task[Peers] =
        nodeDiscovery.recentlyAlivePeersAscendingDistance.map { ps =>
          Peers(
            ps.map(
              p => Peer(p.host, p.protocolPort, p.id)
            )
          )
        }

      def getProcessCpu(request: Empty): Task[ProcessCpu] =
        jvmMetrics.processCpu

      def getMemoryUsage(request: Empty): Task[MemoryUsage] =
        jvmMetrics.memoryUsage

      def getGarbageCollectors(request: Empty): Task[GarbageCollectors] =
        jvmMetrics.garbageCollectors.map(GarbageCollectors.apply)

      def getMemoryPools(request: Empty): Task[MemoryPools] =
        jvmMetrics.memoryPools.map(MemoryPools.apply)

      def getThreads(request: Empty): Task[Threads] =
        jvmMetrics.threads

      def getNodeCoreMetrics(request: Empty): Task[NodeCoreMetrics] =
        nodeMetrics.metrics
    }

}
