package livechat

import io.circe.Codec
import io.circe.generic.semiauto.*

enum ChatEvent derives Codec.AsObject:
  case UserJoined(user: String)
  case UserLeft(user: String)
  case UserWrote(timestamp: Long, offset: Int, user: String, message: String)
  case UsersInRoom(users: Set[String])
