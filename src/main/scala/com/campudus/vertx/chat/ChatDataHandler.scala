/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.campudus.vertx.chat

import org.vertx.java.core.Handler
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonObject
import org.vertx.java.deploy.Verticle
import org.vertx.java.core.shareddata.Shareable
import java.util.UUID
import com.campudus.scala.helpers.MapHelper._
import scala.annotation.tailrec
import scala.collection.immutable.Set
import org.vertx.java.core.json.JsonArray

// saves chatter-tokens
case class Chatroom(chatters: Set[String]) extends Shareable
// saves nickname and list of chatroom-names
case class Chatter(nickname: String, rooms: Set[String]) extends Shareable

/**
 * Handles incoming data for the Campudus chat module.
 * @author <a href="http://www.campudus.com/">Joern Bernhardt, Maximilian Stemplinger</a>
 */
class ChatDataHandler(
  private val verticle: Verticle,
  private val chatClientPrefix: String,
  private val sharedMapPrefix: String,
  private val timeout: Long,
  private val latencyTimeout: Long) extends Handler[Message[JsonObject]] {

  val logger = verticle.getContainer.getLogger

  // token -> chatter
  val chattersMap = verticle.getVertx.sharedData.getMap[String, Chatter](sharedMapPrefix + "chatters")

  // nickname -> token
  val nicknameToTokenMap = verticle.getVertx.sharedData.getMap[String, String](sharedMapPrefix + "nickname-to-token")

  // chatroom-names -> chatroom
  val chatRoomsMap = verticle.getVertx.sharedData.getMap[String, Chatroom](sharedMapPrefix + "chat-rooms")

  // token -> timer-id
  val timeoutMap = verticle.getVertx.sharedData.getMap[String, Long](sharedMapPrefix + "timeouts")

  implicit object ChatroomMerger extends Merger[String, Chatroom]() {
    def valueToContainer(v: String): Chatroom = Chatroom(Set(v))
    def mergeIntoContainer(v: String, c: Chatroom): Chatroom = Chatroom(c.chatters + v)
    def removeFromContainer(v: String, c: Chatroom): Chatroom = Chatroom(c.chatters - v)
  }

  private def createTimer(token: String, timeout: Long) = {
    verticle.getVertx.setTimer(timeout, new Handler[java.lang.Long]() {
      def handle(timerId: java.lang.Long) {
        verticle.getVertx.eventBus.send(chatClientPrefix + token, new JsonObject().putString("action", "ping"));

        val newTimerId = verticle.getVertx.setTimer(latencyTimeout, new Handler[java.lang.Long]() {
          def handle(timerId: java.lang.Long) = {
            verticle.getVertx.eventBus.send(chatClientPrefix + token, new JsonObject().putString("action", "disconnect").putString("error", "PING_TIMEOUT"));
            disconnectClient(token)
          }
        })

        if (timeoutMap.replace(token, timerId, newTimerId)) {
          verticle.getVertx.cancelTimer(timerId)
        }
      }
    })
  }

  private def resetTimer(token: String) = {
    if (verticle.getVertx.cancelTimer(timeoutMap.get(token))) {
      timeoutMap.put(token, createTimer(token, timeout))
      true
    } else {
      false
    }
  }

  private def sendPartMessage(nickname: String, room: String) {
    val json = new JsonObject().putString("action", "part")
      .putString("nickname", nickname)
      .putString("room", room)
    for (userToken <- chatRoomsMap.get(room).chatters) {
      verticle.getVertx.eventBus.send(chatClientPrefix + userToken, json)
    }
  }

  private def disconnectClient(token: String) {
    logger.debug("Chatclient disconnected: " + token)

    val chatter = chattersMap.remove(token)
    for (room <- chatter.rooms) {
      removeFromConcurrentMap(chatRoomsMap, room, token)
      sendPartMessage(chatter.nickname, room)
    }
    nicknameToTokenMap.remove(chatter.nickname)

    verticle.getVertx.cancelTimer(timeoutMap.remove(token))
  }

  private def getUsersFromTokens(userTokens: Set[String]) = {
    val jsonArray = new JsonArray()
    userTokens.foreach {
      token =>
        val chatter = chattersMap.get(token)
        // Chatter could have been removed from chat by now
        if (chatter != null) {
          jsonArray.addString(chatter.nickname)
        }
    }
    jsonArray
  }

  private def getUsersInRoom(room: String) = chatRoomsMap.get(room).chatters

  def handle(event: Message[JsonObject]) {
    import scala.collection.JavaConversions._

    val message = event.body
    val replyJson = new JsonObject().putBoolean("success", true)

    def errorReply(msg: String) {
      replyJson.putBoolean("success", false).putString("error", msg)
    }

    def withValidToken(fn: (Chatter, String) => Unit) {
      event.body.getString("token") match {
        case null => errorReply("Access token parameter missing!")
        case token => // check validity of token
          val chatter = chattersMap.get(token)
          if (chatter == null) {
            errorReply("Access token invalid or expired!")
          } else {
            fn(chatter, token)
          }
      }
    }

    @tailrec
    def chattersMapChatroomsUpdate(token: String, room: String)(op: (Set[String], String) => Set[String]) {
      val oldChatter = chattersMap.get(token)
      if (!chattersMap.replace(token, oldChatter,
        Chatter(oldChatter.nickname, op(oldChatter.rooms, room)))) {
        chattersMapChatroomsUpdate(token, room)(op)
      }
    }

    message.getString("action") match {
      case "connect" => // connect to server
        message.getString("nickname") match {
          case null => errorReply("Parameter 'nickname' missing!")
          case nickname => // check nickname
            val token = UUID.randomUUID.toString
            if (nicknameToTokenMap.putIfAbsent(nickname, token) != null) {
              errorReply("Nickname already in use!")
            } else {
              // TODO password in 1.1
              chattersMap.putIfAbsent(token, Chatter(nickname, Set()))
              timeoutMap.put(token, createTimer(token, timeout))
              replyJson.putString("token", token)
            }
        }
      case "ping" =>
        withValidToken { (chatter, token) =>
          if (resetTimer(token)) {
            replyJson.putNumber("pong", timeout)
          } else {
            errorReply("Could not reset timer (too late, maybe?).")
          }
        }
      case "join" => // joins a room
        withValidToken {
          (chatter, token) =>
            message.getString("room") match {
              case null => errorReply("Parameter 'room' missing!")
              case room =>
                val chatroom = chatRoomsMap.get(room)
                if (chatroom != null && chatroom.chatters.contains(token)) {
                  // Disallow joining twice
                  errorReply("Already inside the room")
                } else {
                  // Join existing room or create it
                  mergeIntoConcurrentMap(chatRoomsMap, room, token)

                  chattersMapChatroomsUpdate(token, room)(_ + _)

                  val userTokens = getUsersInRoom(room)
                  val users = getUsersFromTokens(userTokens)
                  for (
                    userToken <- userTokens;
                    if (token != userToken)
                  ) {
                    val json = new JsonObject().putString("action", "join")
                      .putString("nickname", chattersMap.get(token).nickname)
                      .putString("room", room)
                    verticle.getVertx.eventBus.send(chatClientPrefix + userToken, json)
                  }

                  replyJson.putArray("users", users)
                }
            }
        }
      case "part" => // leaves a room
        withValidToken {
          (chatter, token) =>
            message.getString("room") match {
              case null => errorReply("Parameter 'room' missing!")
              case room =>
                val result = removeFromConcurrentMap(chatRoomsMap, room, token)
                if (!result) {
                  errorReply("Not in room.")
                } else {
                  chattersMapChatroomsUpdate(token, room)(_ - _)
                  sendPartMessage(chattersMap.get(token).nickname, room)
                }
            }
        }
      case "send" => // sends a message to a room or a client
        withValidToken {
          (chatter, token) =>
            if (message.getString("nickname") == null && message.getString("room") == null) {
              errorReply("Parameter 'room' or 'nickname' missing!")
            } else {
              message.getString("message") match {
                case null => errorReply("Parameter 'message' missing!")
                case msg =>
                  if (message.getString("nickname") == null) {
                    // check if person is in room
                    val room = message.getString("room")
                    if (chatter.rooms.contains(room)) {
                      // send to all persons in room
                      val json = new JsonObject().putString("action", "message")
                        .putString("nickname", chatter.nickname)
                        .putString("message", msg)
                        .putString("room", room)
                      for (userToken <- chatRoomsMap.get(room).chatters if (userToken != token)) {
                        verticle.getVertx.eventBus.send(chatClientPrefix + userToken, json)
                      }
                    } else {
                      errorReply("Chatter is not in room '" + room + "'")
                    }
                  } else {
                    val nick = message.getString("nickname")
                    // check if person is there
                    nicknameToTokenMap.get(nick) match {
                      case null => errorReply("Person '" + nick + "' is not available!")
                      case tokenOfOtherPerson =>
                        val json = new JsonObject().putString("action", "message")
                          .putString("nickname", chatter.nickname)
                          .putString("message", msg)
                        verticle.getVertx.eventBus.send(chatClientPrefix + tokenOfOtherPerson, json)
                    }
                  }
              }
            }
        }
      case "disconnect" => // leaves all rooms
        withValidToken { (chatter, token) =>
          disconnectClient(token)
        }
      case unknown =>
        logger.warn("Unknown action " + unknown + " in chat module!")
        errorReply("Unknown action '" + unknown + "'!")
    }

    event.reply(replyJson)
  }

}