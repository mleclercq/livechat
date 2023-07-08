# Shardcake / zio-http / Laminar Livechat demo

This is just a toy example with the main focus on demonstrating the streaming of responses in Shardcake.

The frontend app is pretty basic and it's my first time using Laminar so don't take it as an example of how things should be done 😅.

## How to run

Build the backend

```
sbt livechatJVM/stage
```

Start the containers (redis and nginx load-balancer)

```bash
cd docker
docker-compose up
```

Start the shard manager

```bash
./jvm/target/universal/stage/bin/shard-manager-app
```

Start a pod

```bash
./jvm/target/universal/stage/bin/chat-app
```

Start the frontend server

```bash
npm install
npm run dev
```

Open the frontend app at http://127.0.0.1:5173/

Optional: start a second pod

```bash
./jvm/target/universal/stage/bin/chat-app 1
```

You can start more pods by passing increasing numbers. These numbers ensures that each pod uses different ports.

The traffic from the frontend application goes through the nginx load-balancer and then to the pods. So you start and stop pods, the frontend app should automatically reconnect if the connection is closed and you can check in the pod logs how shardcake reassign the shards and how the "ChatRoom" entity is rebalanced.

Note the nginx load balancer is configured to send traffic to at most 4 pods.
