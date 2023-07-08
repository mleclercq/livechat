package livechat

import livechat.ChatRoomBehavior.*
import io.circe.parser.*
import io.circe.syntax.*

import zio.*
import zio.http.*
import zio.http.socket.{WebSocketChannelEvent, WebSocketFrame}
import com.devsisters.shardcake.*
import zio.stream.ZStream
import zio.stream.Take
import zio.http.internal.middlewares.Cors.CorsConfig

object ChatRoomApi:

  class Impl(
      messenger: Messenger[Message],
      openChatRooms: Ref.Synchronized[Map[String, (Int, Hub[Take[Throwable, ChatEvent]])]],
      serverScope: Scope
  ):

    def getUpdates(chatRoomId: String): ZStream[Any, Throwable, ChatEvent] =
      messenger
        .sendStreamAutoRestart(chatRoomId, -1)(Message.GetUpdates.apply):
          case (_, ChatEvent.UserWrote(_, offset, _, _)) =>
            // plus one here because if we were to restart the stream at that point, we want to get all the events that
            // happened after this one
            offset + 1
          case (offset, _) => offset
        .ensuring(ZIO.log("[api] Chat Event stream ended. Will restart"))
        .concat(getUpdates(chatRoomId))

    /** Subscribes to the ChatEvents from a chat room. The subscription is automatically cancelled when the scope
      * closes.
      *
      * The goal here is to share a single stream of update from the chat room entity between all the websockets
      * connected to the same chat room. This complicates things a bit but the idea is that we may have a large number
      * of end-users all listening to the events from the same chat room. If each one of them has its own response
      * stream, not only it could put quite some load on the entity but also it would create a "blast" of requests when
      * the entity is rebalanced.
      */
    def subscribe(chatRoomId: String): ZIO[Scope, Nothing, ZStream[Any, Throwable, ChatEvent]] =
      for
        stream <- openChatRooms
          .modifyZIO: openChatRooms =>
            openChatRooms.get(chatRoomId) match
              case Some((count, hub)) =>
                // Happy path: we already have a hub from which we can get the ChatEvent.
                ZIO.succeed(
                  // The effect to run outside of `modifyZIO` to get the stream of events from the hub
                  ZStream.fromHubScoped(hub).map(_.flattenTake),
                  openChatRooms + (chatRoomId -> ((count + 1, hub)))
                )
              case None =>
                // No hub, we need to create one and add it to the `openChatRooms` Ref
                Hub
                  .unbounded[Take[Throwable, ChatEvent]]
                  .map: hub =>
                    (
                      // The effect to ask the entity to send us ChatEvents. This is run by `.flatten` bellow
                      for
                        stream <- ZStream.fromHubScoped(hub)
                        // Start a fiber that gets the updates from the entity and push them in the hub so they are
                        // sent to all websockets listening to the same room.
                        // critical that the scope of this is the server one, not the scope of the first websocket that
                        // listens to that room
                        _ <- serverScope.extend(
                          getUpdates(chatRoomId)
                            .runIntoHub(hub)
                            .ensuring(ZIO.log(s"[api] Done getting messages for $chatRoomId"))
                            .forkScoped
                        )
                      yield stream.flattenTake,
                      openChatRooms + (chatRoomId -> (1, hub))
                    )
          .flatten
        // When the current scope closes (i.e. the websocket is closed), we decrease the count of opened websockets and
        // potentially shutdown the hub which terminates the streaming of events from the chat room
        _ <- Scope.addFinalizerExit: exit =>
          openChatRooms
            .modify: openChatRooms =>
              openChatRooms.get(chatRoomId) match
                case Some((count, hub)) =>
                  if count <= 1 || exit.isInterrupted then
                    val reason = if exit.isInterrupted then "on interruption" else "no more client"
                    (
                      ZIO.log(s"[api] Shutting down subscription for $chatRoomId $reason") *> hub.shutdown,
                      (openChatRooms - chatRoomId)
                    )
                  else
                    (
                      ZIO.log(
                        s"[api] NOT shutting down subscription for $chatRoomId as we still have ${count - 1} websockets listenting to it"
                      ),
                      (openChatRooms + (chatRoomId -> (count - 1, hub)))
                    )
                case None => ZIO.unit -> openChatRooms
            .flatten
      yield stream

    def socketApp(chatRoomId: String, userId: String) =
      for
        // The scope matching the lifecycle of the websocket
        webSocketScope <- serverScope.fork
        // subscribe to the ChatEvents from the chatRoom in the scope of the websocket (i.e. automatically cancel
        // subscription when the websocket closes)
        events <- webSocketScope.extend(subscribe(chatRoomId))
      yield Http.collectZIO[WebSocketChannelEvent]:
        // Apparently this event is not fired despite what the doc says
        // case ChannelEvent(channel, ChannelEvent.ChannelRegistered)   =>
        case ChannelEvent(channel, ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete)) =>
          for
            // Fork the fiber that send ChatEvents onto the websocket
            _ <- webSocketScope.extend:
              events
                .runForeach: event =>
                  ZIO.log(s"[api] Send chat event $event in room $chatRoomId to $userId") *>
                    channel.writeAndFlush(WebSocketFrame.text(event.asJson.noSpaces))
                .catchAllCause(e => ZIO.logErrorCause("Websocket error", e) *> channel.close())
                .forkScoped
            _     <- ZIO.log(s"[api] $userId joining $chatRoomId")
            users <- messenger.send[Set[String]](chatRoomId)(Message.Join(userId, _))
            _     <- ZIO.log(s"[api] $userId joined $chatRoomId")
            _ <- channel.writeAndFlush(WebSocketFrame.text((ChatEvent.UsersInRoom(users): ChatEvent).asJson.noSpaces))
          yield ()

        case ChannelEvent(channel, ChannelEvent.ChannelUnregistered) =>
          for
            _ <- ZIO.checkInterruptible(status =>
              ZIO.log(s"[api] Websocket for room $chatRoomId to $userId closed $status")
            )
            _ <- webSocketScope.close(Exit.unit)
            // Note: We get ChannelUnregistered event when the pod is shuting down, but if this pod is also the one
            // running the chat room entity, the Leave message isn't delivered since the entity is also shutting
            // down. Not sure how to solve this...
            _ <- messenger.sendDiscard(chatRoomId)(Message.Leave(userId))
          yield ()

        case ChannelEvent(channel, ChannelEvent.ChannelRead(WebSocketFrame.Text(text))) =>
          for
            message <- ZIO.fromEither(parse(text).flatMap(_.as[String]))
            _       <- messenger.sendDiscard(chatRoomId)(Message.Write(userId, message))
          yield ()

    val config: CorsConfig =
      CorsConfig(
        allowedOrigin =
          case origin @ Header.Origin.Value(_, "localhost" | "127.0.0.1", _) =>
            Some(Header.AccessControlAllowOrigin.Specific(origin))
          case _ => None
        ,
        allowedMethods = Header.AccessControlAllowMethods(Method.GET)
      )

    val app: HttpApp[Any, Throwable] =
      HttpAppMiddleware.cors(config):
        Http.collectZIO[Request]:
          case req @ Method.GET -> !! / "chatRooms" / chatRoomId / "history" =>
            val from = req.url.queryParams.get("from").flatMap(_.headOption).map(_.toInt).getOrElse(0)
            val to   = req.url.queryParams.get("to").flatMap(_.headOption).map(_.toInt).getOrElse(-1)
            for history <- messenger.send[Chunk[ChatEvent.UserWrote]](chatRoomId)(Message.GetHistory(from, to, _))
            yield Response.json((history: Seq[ChatEvent]).asJson.noSpaces)

          case Method.GET -> !! / "chatRooms" / chatRoomId / "users" / userId =>
            for
              sApp <- socketApp(chatRoomId, userId)
              r    <- sApp.toSocketApp.toResponse
            yield r

          case Method.GET -> !! / "hello" =>
            ZIO.succeed(Response.text("world"))
  end Impl

  val make: URIO[Sharding & Scope, App[Any]] =
    for
      messenger <- Sharding.messenger(ChatRoom)
      openChatRooms <-
        Ref.Synchronized.make(Map.empty[String, (Int, Hub[Take[Throwable, ChatEvent]])])
      scope <- ZIO.scope
    yield new Impl(messenger, openChatRooms, scope).app.withDefaultErrorResponse

end ChatRoomApi
