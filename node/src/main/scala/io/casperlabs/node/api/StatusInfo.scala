package io.casperlabs.node.api

import cats.effect.Sync
import cats.implicits._

import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.comm.rp.Connect.ConnectionsCell

import org.http4s.HttpRoutes

object StatusInfo {

  case class Status(
      version: String,
      peers: Int,
      nodes: Int
  )

  def status[F[_]: Sync: ConnectionsCell: NodeDiscovery]: F[Status] =
    for {
      version <- Sync[F].delay(VersionInfo.get)
      peers   <- ConnectionsCell[F].read
      nodes   <- NodeDiscovery[F].recentlyAlivePeersAscendingDistance
    } yield Status(version, peers.length, nodes.length)

  def service[F[_]: Sync: ConnectionsCell: NodeDiscovery]: HttpRoutes[F] = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityEncoder._

    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root => Ok(status.map(_.asJson))
    }
  }
}
