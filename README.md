# Chat

This module adds basic chat functionalities to Vertx.

If you want to improve this module or its documentation, feel free to submit a pull request!

The latest version of this module can be found in the [campudus/vertx-chat repository](https://github.com/campudus/vertx-chat).

## Dependencies

There are no special dependencies right now. It uses SharedData to store all members in the chat.

## Name

The module name is `com.campudus.chat`.

## Configuration

The chat module takes the following configuration:

    {
        "address": <address>,
        "client-prefix": <clientPrefix>,
        "map-prefix": <sharedMapsPrefix>,
        "timeout": <timeout>,
        "latency-timeout": <latencyTimeout>
    }

For example:

    {
        "address": "campudus.chat",
        "client-prefix": "campudus.chatters.",
        "map-prefix": "com.campudus.vertx.chat.",
        "timeout": 120000,
        "latency-timeout" : 2000
    }        

A short description about each field:
* `address` The main address for the module. Every module has a main address. Defaults to `campudus.chat`.
* `client-prefix` All clients should register an address at `prefix.<token>` where `<token>` represents a UUID given to the client by the server on its initial connection. The client prefix defaults to `campudus.chatters.`.
* `map-prefix` The chat module uses four different shared data maps. With a unique map-prefix, these maps should not interfere with other shared data maps. The map prefix defaults to `com.campudus.vertx.chat.`.
* `timeout` How long after a client should send a ping to tell the server it is still connected. If a timeout occurs, the connection will be disconnected and the token gets invalid. The timeout is set as a long value in milliseconds. Defaults to two minutes, i.e. 120000.
* `latency-timeout` How long will it take until the connection will be disconnected if the request for a ping was not answered. The timeout disconnection will occur after `timeout` + `latency-timeout` effectively. Defaults to two seconds, i.e. 2000.

## Quickstart

Here is a small example for a Java Client to get you started using this module.

    public class ChatTestVerticle extends Verticle {
      final String nickname = "Guest" + new Random().nextInt(1000);
    
      @Override
      public void start() throws Exception {
        vertx.eventBus().send("campudus.chat",
            new JsonObject().putString("action", "connect").putString("nickname", nickname),
            new Handler<Message<JsonObject>>() {
              public void handle(Message<JsonObject> connectMessage) {
                final String myToken = connectMessage.body.getString("token");
    
                vertx.eventBus().registerHandler("campudus.chatters." + myToken, new Handler<Message<JsonObject>>() {
                  public void handle(Message<JsonObject> message) {
                    switch (message.body.getString("action")) {
                    case "message":
                      final String sender = message.body.getString("nickname");
                      final String text = message.body.getString("message");
                      final String room = message.body.getString("room");
                      if (room == null) {
                        handlePrivateMessage(sender, text);
                      } else {
                        handleChatMessage(room, sender, text);
                      }
                      break;
                    case "join":
                      final String joinedPerson = message.body.getString("nickname");
                      final String joinedRoom = message.body.getString("room");
                      handleJoin(joinedPerson, joinedRoom);
                      break;
                    case "part":
                      final String partedPerson = message.body.getString("nickname");
                      final String partedRoom = message.body.getString("room");
                      handlePart(partedPerson, partedRoom);
                      break;
                    case "ping":
                      sendPingToServer(myToken);
                      break;
                    }
                  }
    
                });
    
                performInitialActions(myToken, nickname);
              }
            });
      }
    
      // ...methods to handle the various incoming messages and performInitialActions...
    
    }

## Operations

The module supports a few operations. Generally you provide a JSON object with `"action" : <action>` and required or optional parameters. For most operations, you will need to provide a token which you receive when you connect to the server for the first time and issue the `connect` operation. The server will send messages to the clients to a special address, consisting of the client-prefix and the provided token. See [Incoming Messages][] how this should be set up.

### connect (Initializes the connection to the server)

Connecting to the server is required before you can use any other action in a meaningful way. To connect to the server, you will need to provide a nickname, which other clients can see. As a return message, you might get an error, if the nickname you choose is already in use. If connecting succeeded, you will receive a unique token. You have to send this token to the server in all later requests. This will let the server know which client it is dealing with and may allow/disallow it to do specific operations.

You should also use this token to register a handler on `<client-prefix><token>`. The server sends messages to this client to this address.

Example:

    {
        "action" : "connect",
        "nickname" : "ThePlayfulGorilla"
    }

Return value:

    {
        "success": true,
        "token": "48468827-e20c-4ebd-b1c5-f7c36dc4bc95"
    }

### join (Join a chat-room)

Joins a chat-room. The client will receive incoming text, join and part messages for this room on its handler `<client-prefix><token>`. The server will respond with a list of nicknames connected to this chat-room right now.

Example:

    {
        "action" : "join",
        "token" : "48468827-e20c-4ebd-b1c5-f7c36dc4bc95",
        "room" : "jungle"
    }

Return value:

    {
        "success" : true,
        "users" : ["ThePlayfulRobot", "TheRedSnake", "GreenFlash"]
    }

### part (Leave a chat-room)

Leaves a chat-room. The client will not receive any more join, part or text messages for this room on its handler.

Example:

    {
        "action" : "part",
        "token" : "48468827-e20c-4ebd-b1c5-f7c36dc4bc95",
        "room" : "jungle"
    }

Return value:

    {
        "success" : true
    }

### send (Send a message to a chat-room)

Sends a message to all clients inside a chat-room.

Example to send a _Howdy!_ to the chat-room _jungle_:

    {
        "action" : "send",
        "token" : "48468827-e20c-4ebd-b1c5-f7c36dc4bc95",
        "room" : "jungle",
        "message" : "Howdy!"
    }

Return value:

    {
        "success" : true
    }

### send (Send a message to another user directly)

Sends a message to another client directly. The other client will receive the message directly on its handler (see incoming operation `message`).

In this example, a message is sent to _ThePlayfulGorilla_:

    {
        "action" : "send",
        "token" : "48468827-e20c-4ebd-b1c5-f7c36dc4bc95",
        "nickname" : "ThePlayfulGorilla",
        "message" : "Hi there, Mr. Gorilla!"
    }

Return value:

    {
        "success" : true
    }

### disconnect (Disconnects the client from the server)

Disconnects the client from the server. All rooms will receive `part` messages if the client did not leave them before its disconnect. The token will be invalid after the client sent the disconnect to the server and cannot be reused after this action. The client has to use the `connect` operation again to receive a new token.

Example:

    {
        "action" : "disconnect",
        "token" : "48468827-e20c-4ebd-b1c5-f7c36dc4bc95"
    }

Return value:

    {
        "success" : true
    }

### ping (Sends a ping to the server to stay connected)

Sends a ping request to the server. The server should reply with a `pong` set to the timeout for chat connections.

Example:

    {
        "action" : "ping",
        "token" : "48468827-e20c-4ebd-b1c5-f7c36dc4bc95"
    }

Return value:

    {
        "success" : true,
        "pong" : 120000
    }

## Incoming Messages

The server will send messages to the client without the client asking for it. The client should therefore register a listener as soon as possible to catch these messages.

Here is a small Java example to do this:

    vertx.eventBus().registerHandler("campudus.chatters." + myToken, new Handler<Message<JsonObject>>() {
      public void handle(Message<JsonObject> message) {
        switch (message.body.getString("action")) {
        case "message":
          final String sender = message.body.getString("nickname");
          final String text = message.body.getString("message");
          final String room = message.body.getString("room");
          if (room == null) {
            handlePrivateMessage(sender, text);
          } else {
            handleChatMessage(room, sender, text);
          }
          break;
        case "join":
          final String joinedPerson = message.body.getString("nickname");
          final String joinedRoom = message.body.getString("room");
          handleJoin(joinedPerson, joinedRoom);
          break;
        case "part":
          final String partedPerson = message.body.getString("nickname");
          final String partedRoom = message.body.getString("room");
          handlePart(partedPerson, partedRoom);
          break;
        case "ping":
          sendPingToServer(myToken);
          break;
        }
      }
    });


### message (Receives a message)

If there is an incoming message, the server will send it to the client. If the message was sent to a specific room and the client may be only one of many addressed participants, the message will look like this example:

    {
        "action" : "message",
        "nickname" : "ThePlayfulGorilla",
        "room" : "jungle",
        "message" : "Hrrm!"
    }

Here, the client with nickname _ThePlayfulGorilla_ sent the text _Hrrm!_ to the chat-room _jungle_. The chat-room is optional. That means, if a client decides to write privately to this client, it will be sent without the "room" key/value. So an example for a private message from _ThePlayfulGorilla_ with content _Hi there, my friend!_ looks like:

    {
        "action" : "message",
        "nickname" : "ThePlayfulGorilla",
        "message" : "Hi there, my friend!"
    }

### join (Someone joined a chat-room)

The server will notice all clients connected to a chat-room if another clients joins the room. Here is an example message, if _TheLawfulHuman_ joins the room _jungle_:

    {
        "action" : "join",
        "nickname" : "TheLawfulHuman",
        "room" : "jungle"
    }

Now all clients know that this new client joined the conversation.

### part (Someone left a chat-room)

This is analog to the join. If someone disconnects from a room, all clients still inside this room will be notified by the server like the following example:

    {
        "action" : "part",
        "nickname" : "TheLawfulHuman",
        "room" : "jungle"
    }

Here _TheLawfulHuman_ obviously did not want to stay inside the room _jungle_ any longer or was disconnected by the server.

### ping (The server wants to receive a ping)

The server wants to make sure that all clients stay connected. To be sure a client is still there, it will send a ping request. The client should not answer that message directly but send a `ping` action itself to the server. After a ping request by the server, the client has the amount of milliseconds set up in `latency-timeout` to send the `ping` operation.

Example flow of messages:

Server sends a message like this to _campudus.chatters.48468827-e20c-4ebd-b1c5-f7c36dc4bc95_:

    {
        "action" : "ping"
    }

Client receives that message and sends a message to _campudus.chat_ with the following content:

    {
        "action" : "ping",
        "token" : "48468827-e20c-4ebd-b1c5-f7c36dc4bc95" 
    }

The server receives this message and responds with its regular response:

    {
        "success" : true,
        "pong" : 120000
    }

Inside the server module, the timeout will be reset and it will wait the set up `timeout` before sending a ping request again.

### disconnect (The server disconnected this client)

If the server disconnects a client, it will let the client know. Usually, a disconnect only occurs if the ping request wasn't sent fast enough and the server timed out the client. The server will let the client know with an error message.

A disconnect message will look like this:

    {
        "action" : "disconnect",
        "error" : "PING_TIMEOUT"
    }

Right now, there is only a `PING_TIMEOUT` error. In future versions, an administrator may disconnect clients which will result in different error messages.
