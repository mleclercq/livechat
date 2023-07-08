package livechat

import com.devsisters.shardcake.*
import dev.profunktor.redis4cats.RedisCommands
import io.circe.Codec
import io.circe.generic.semiauto.*
import io.circe.parser.*
import io.circe.syntax.*
import zio.*
import zio.stream.ZStream

import java.time.Instant

object ChatRoomBehavior:

  enum Message:
    case Join(userName: String, replier: Replier[Set[String]])
    case Leave(userName: String)
    case Write(userName: String, message: String)

    case GetHistory(from: Int, to: Int, replier: Replier[Chunk[ChatEvent.UserWrote]])
    case GetUpdates(from: Int, replier: StreamReplier[ChatEvent])

  object ChatRoom extends EntityType[Message]("chatRoom")

  private final case class State(users: Set[String], nextOffset: Int, eventsHub: Hub[ChatEvent])

  def behavior(
      entityId: String,
      messages: Dequeue[Message]
  ): RIO[Sharding & RedisCommands[Task, String, String], Nothing] =
    ZIO
      .scoped:
        for
          _              <- ZIO.logInfo(s"[entity] Started chat room $entityId")
          redis          <- ZIO.service[RedisCommands[Task, String, String]]
          recoveredState <- recoverState(entityId, redis)
          state          <- Ref.make(recoveredState)
          r              <- messages.take.flatMap(handleMessage(entityId, state, redis, _)).forever
        yield r
      .ensuring(ZIO.logInfo(s"[entity] Chat room $entityId ended"))

  def usersKey(entityId: String)    = s"chatRoom/${entityId}/users"
  def messagesKey(entityId: String) = s"chatRoom/${entityId}/messages"

  private def getHistory(entityId: String, redis: RedisCommands[Task, String, String], from: Int, to: Int) =
    for
      persistedMessages <- redis.lRange(messagesKey(entityId), from, to)
      pastMessages <- ZIO.foreach(persistedMessages.to(Chunk)): msg =>
        parse(msg).flatMap(_.as[PersistedMessage]) match
          case Right(PersistedMessage(time, nextOffset, userName, message)) =>
            ZIO.some(new ChatEvent.UserWrote(time.toEpochMilli(), nextOffset, userName, message))
          case Left(err) =>
            ZIO.logWarning(s"[entity] Failed to decode persisted message: $err").as(None)
    yield pastMessages.flatten

  private def recoverState(entityId: String, redis: RedisCommands[Task, String, String]): Task[State] =
    for
      users       <- redis.lRange(usersKey(entityId), 0, -1)
      lastMessage <- getHistory(entityId, redis, -1, -1)
      updates     <- Hub.unbounded[ChatEvent]
    yield State(users.toSet, lastMessage.headOption.map(_.offset + 1).getOrElse(0), updates)

  private def handleMessage(
      entityId: String,
      state: Ref[State],
      redis: RedisCommands[Task, String, String],
      message: Message
  ): RIO[Sharding, Unit] =
    message match
      case Message.Join(userName, replier) =>
        for
          s <- state.updateAndGet(s => s.copy(users = s.users + userName))
          _ <- redis.lPush(usersKey(entityId), userName)
          _ <- replier.reply(s.users)
          _ <- ZIO.log(s"[entity] $userName joined $entityId users are ${s.users}")
          _ <- s.eventsHub.publish(ChatEvent.UserJoined(userName))
        yield ()
      case Message.Leave(userName) =>
        for
          s <- state.updateAndGet(s => s.copy(users = s.users - userName))
          _ <- redis.lRem(usersKey(entityId), 1, userName)
          _ <- ZIO.log(s"[entity] $userName left $entityId users are ${s.users}")
          _ <- s.eventsHub.publish(ChatEvent.UserLeft(userName))
        yield ()
      case Message.Write(userName, message) =>
        for
          s   <- state.getAndUpdate(s => s.copy(nextOffset = s.nextOffset + 1))
          now <- Clock.instant
          _ <- redis.rPush(
            messagesKey(entityId),
            PersistedMessage(now, s.nextOffset, userName, message).asJson.noSpaces
          )
          _ <- s.eventsHub.publish(ChatEvent.UserWrote(now.toEpochMilli(), s.nextOffset, userName, message))
        yield ()
      case Message.GetHistory(from, to, replier) =>
        for
          pastMessages <- getHistory(entityId, redis, from, to)
          _            <- replier.reply(pastMessages)
        yield ()
      case Message.GetUpdates(from, replier) =>
        for
          s            <- state.get
          _            <- ZIO.log(s"[entity] Start sending update to $replier")
          pastMessages <- getHistory(entityId, redis, from, -1).when(from > 0).someOrElse(Chunk.empty[ChatEvent])
          _ <- replier.replyStream(
            (ZStream.fromIterable(pastMessages) ++ ZStream.fromHub(s.eventsHub))
              .tap(e => ZIO.log(s"[entity] Sending $e to $replier"))
              .ensuring(ZIO.log(s"[entity] Stop sending updates to $replier"))
          )
        yield ()

  private case class PersistedMessage(time: Instant, offset: Int, userName: String, message: String)
      derives Codec.AsObject

end ChatRoomBehavior
