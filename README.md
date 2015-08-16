# Ainterface

[![Build Status](https://travis-ci.org/ainterface/ainterface.svg?branch=master)](https://travis-ci.org/ainterface/ainterface)

Ainterface provides APIs to communicate with Erlang processes, like [Jinterface](http://www.erlang.org/doc/apps/jinterface/jinterface_users_guide.html).
And APIs of Ainterface are designed to integrate Akka with Erlang seamlessly.

## Notice

Since Ainterface is an experimental project, there is no guarantee that APIs will not be changed.

## Example

First, start an Erlang node and EPMD.

```
$ erl -name mofu
Erlang/OTP 17 [erts-6.2.1] [source] [64-bit] [smp:4:4] [async-threads:10] [hipe] [kernel-poll:false] [dtrace]

Eshell V6.2.1  (abort with ^G)
(mofu@okumin-mini.local)1>
```

Set up Ainterface.

```
scala> val config = com.typesafe.config.ConfigFactory.parseString("""
     | akka {
     |   loglevel = "ERROR"
     |   ainterface {
     |     root-name = "ainterface-system"
     |     init.name = "ainterface-sample" // the node name
     |   }
     | }
     | """)
config: com.typesafe.config.Config = Config(SimpleConfigObject({"akka":{"ainterface":{"init":{"name":"ainterface-sample"},"root-name":"ainterface-system"},"loglevel":"ERROR"}}))

scala> val system = akka.actor.ActorSystem("sample", config)
system: akka.actor.ActorSystem = akka://sample

scala> akka.ainterface.AinterfaceSystem.init(system) // initialize
```

Start an [echo server](https://github.com/ainterface/ainterface/blob/master/ainterface-sample/src/main/scala/ainterface/EchoActor.scala) with the registered name `echo`.

```
scala> val echo = system.actorOf(akka.actor.Props[ainterface.EchoActor])
echo: akka.actor.ActorRef = Actor[akka://sample/user/$a#1942167954]
```

You can send messages from the Erlang node to Akka!
The echo server returns his pid and `hello` message.

```
(mofu@okumin-mini.local)1> {echo, 'ainterface-sample@okumin-mini.local'} ! {self(), hello}.
{<0.38.0>,hello}
(mofu@okumin-mini.local)2> flush().
Shell got {<6209.1.0>,hello}
ok
```

The other examples are [here](https://github.com/ainterface/ainterface/tree/master/ainterface-sample/src/main/scala/ainterface).

## License

Apache 2.0
