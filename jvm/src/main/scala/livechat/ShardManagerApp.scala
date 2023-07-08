package livechat

import com.devsisters.shardcake.*
import com.devsisters.shardcake.interfaces.*
import zio.*

object ShardManagerApp extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = logger

  val managerConfig = ZLayer.succeed(
    ManagerConfig.default.copy(
      apiPort = 8008,
      rebalanceInterval = 10.seconds
    )
  )

  def run: Task[Nothing] =
    Server.run.provide(
      managerConfig,
      ZLayer.succeed(GrpcConfig.default),
      ZLayer.succeed(RedisConfig.default),
      redis,
      StorageRedis.live,
      PodsHealth.local,
      GrpcPods.live,
      ShardManager.live
    )
