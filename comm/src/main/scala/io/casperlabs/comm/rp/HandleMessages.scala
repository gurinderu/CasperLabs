package io.casperlabs.comm.rp

import scala.concurrent.duration._
import cats._
import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import io.casperlabs.comm._
import io.casperlabs.comm.CommError._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.rp.Connect.Connections._
import io.casperlabs.comm.transport._
import io.casperlabs.comm.transport.CommunicationResponse._
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.effects._
import io.casperlabs.shared._

@silent()
object HandleMessages {

  private implicit val logSource: LogSource = LogSource(this.getClass)
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "rp.handle")

  def handle[F[_]: Sync: Log: Time: Metrics: TransportLayer: ErrorHandler: PacketHandler: ConnectionsCell: RPConfAsk](
      protocol: Protocol,
      defaultTimeout: FiniteDuration
  ): F[CommunicationResponse] =
    ProtocolHelper.sender(protocol) match {
      case None =>
        Log[F].error(s"Sender not present, DROPPING $protocol").as(notHandled(senderNotAvailable))
      case Some(sender) => handle_[F](protocol, sender, defaultTimeout)
    }

  private def handle_[F[_]: Sync: Log: Time: Metrics: TransportLayer: ErrorHandler: PacketHandler: ConnectionsCell: RPConfAsk](
      proto: Protocol,
      sender: Node,
      defaultTimeout: FiniteDuration
  ): F[CommunicationResponse] =
    proto.message match {
      case Protocol.Message.Heartbeat(heartbeat) => handleHeartbeat[F](sender, heartbeat)
      case Protocol.Message.ProtocolHandshake(protocolhandshake) =>
        handleProtocolHandshake[F](sender, protocolhandshake, defaultTimeout)
      case Protocol.Message.Disconnect(disconnect) => handleDisconnect[F](sender, disconnect)
      case Protocol.Message.Packet(packet)         => handlePacket[F](sender, packet)
      case msg =>
        Log[F].error(s"Unexpected message type $msg") *> notHandled(unexpectedMessage(msg.toString))
          .pure[F]
    }

  def handleDisconnect[F[_]: Sync: Metrics: TransportLayer: Log: ConnectionsCell](
      sender: Node,
      disconnect: Disconnect
  ): F[CommunicationResponse] =
    for {
      _ <- Log[F].info(s"Forgetting about ${sender.show}.")
      _ <- TransportLayer[F].disconnect(sender)
      _ <- ConnectionsCell[F].flatModify(_.removeConn[F](sender))
      _ <- Metrics[F].incrementCounter("disconnect")
    } yield handledWithoutMessage

  def handlePacket[F[_]: Monad: Time: TransportLayer: ErrorHandler: Log: PacketHandler: RPConfAsk](
      remote: Node,
      packet: Packet
  ): F[CommunicationResponse] =
    for {
      local               <- RPConfAsk[F].reader(_.local)
      maybeResponsePacket <- PacketHandler[F].handlePacket(remote, packet)
    } yield maybeResponsePacket
      .fold(notHandled(noResponseForRequest))(
        m => handledWithMessage(ProtocolHelper.protocol(local).withPacket(m))
      )

  def handleProtocolHandshake[F[_]: Monad: Time: TransportLayer: Log: ErrorHandler: ConnectionsCell: RPConfAsk: Metrics](
      peer: Node,
      protocolHandshake: ProtocolHandshake,
      defaultTimeout: FiniteDuration
  ): F[CommunicationResponse] = {

    def notHandledHandshake(error: CommError): F[CommunicationResponse] =
      Log[F]
        .warn(s"Not adding. Could not receive response to heartbeat from $peer, reason: $error")
        .as(notHandled(error))

    def handledHandshake(local: Node): F[CommunicationResponse] =
      for {
        _ <- ConnectionsCell[F].flatModify(_.addConn[F](peer))
        _ <- Log[F].info(s"Responded to protocol handshake request from $peer")
      } yield handledWithMessage(ProtocolHelper.protocolHandshakeResponse(local))

    for {
      local        <- RPConfAsk[F].reader(_.local)
      hbrErr       <- TransportLayer[F].roundTrip(peer, ProtocolHelper.heartbeat(local), defaultTimeout)
      commResponse <- hbrErr.fold(error => notHandledHandshake(error), _ => handledHandshake(local))
    } yield commResponse
  }

  def handleHeartbeat[F[_]: Monad: Time: TransportLayer: ErrorHandler: RPConfAsk](
      peer: Node,
      heartbeat: Heartbeat
  ): F[CommunicationResponse] =
    RPConfAsk[F].reader(_.local) map (
        local => handledWithMessage(ProtocolHelper.heartbeatResponse(local))
    )

}
