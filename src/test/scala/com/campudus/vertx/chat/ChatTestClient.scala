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

import org.vertx.java.core.eventbus.EventBus
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.Handler
import org.vertx.java.framework.TestClientBase
import org.vertx.java.core.json.JsonArray
import scala.actors.threadpool.AtomicInteger
import org.vertx.java.framework.TestUtils
import java.util.UUID
import org.vertx.java.core.logging.Logger

class ChatTestClient extends TestClientBase {

  val csAddress = "chat-server"
  val clientPrefix = "chatter."
  val mapPrefix = "com.campudus.chat-server."
  val timeout = 5 * 1000L
  val latencyTimeout = 2 * 1000L

  val moduleName = System.getProperty("module.name")

  var eb: EventBus = null
  var logger: Logger = null

  override def start() {
    super.start()
    eb = vertx.eventBus()
    logger = container.getLogger
    val config = new JsonObject()
    config.putString("address", csAddress)
    config.putString("client-prefix", clientPrefix)
    config.putString("map-prefix", mapPrefix)
    config.putNumber("timeout", timeout)
    config.putNumber("latency-timeout", latencyTimeout)

    container.deployModule(moduleName, config, 1, new Handler[java.lang.String] {
      def handle(res: String) {
        tu.appReady();
      }
    })
  }

  override def stop() {
    super.stop();
  }

  implicit def f2h(fn: JsonObject => Unit): Handler[Message[JsonObject]] = new Handler[Message[JsonObject]]() {
    def handle(event: Message[JsonObject]) = fn(event.body)
  }

  implicit def enhancedToRegularTestUtils(en: EnhancedTestUtils): TestUtils = en.tu

  def json(): JsonObject = new JsonObject
  def json(token: String): JsonObject = json.putString("token", token)
  def json(token: String, action: String): JsonObject = json(token).putString("action", action)
  def jsonMessage(token: String, room: String, msg: String) = json(token, "send").putString("room", room).putString("message", msg)

  class EnhancedTestUtils(n: Int, val tu: TestUtils) {
    val numberOfTests = new AtomicInteger(math.max(n, 1))
    def testComplete(): Unit = testComplete {}
    def testComplete(fn: => Unit): Unit = if (numberOfTests.decrementAndGet == 0) {
      fn
      tu.testComplete
    } else {
      logger.info("noch " + numberOfTests.get + " tests")
    }
  }

  def asyncTests(i: Int)(fn: EnhancedTestUtils => Unit) = fn(new EnhancedTestUtils(i, tu))

  def afterConnectDo(fn: String => Unit): Unit = afterConnectDo("Guest" + UUID.randomUUID.toString)(fn)

  def afterConnectDo(nickname: String)(fn: String => Unit): Unit = {
    eb.send(csAddress, new JsonObject().putString("action", "connect").putString("nickname", nickname),
      f2h { reply =>
        tu.azzert(reply.getBoolean("success", false), "Should successfully connect with nick '" + nickname + "'.")
        tu.azzert(reply.getString("token") != null, "Should receive a token.")
        fn(reply.getString("token"))
      })
  }

  def afterJoinDo(token: String, room: String)(fn: JsonArray => Unit) = {
    eb.send(csAddress, json(token).putString("action", "join").putString("room", room), f2h { reply =>
      tu.azzert(reply.getBoolean("success", false), "Should successfully join into room '" + room + "'.")
      tu.azzert(reply.getArray("users") != null, "Should receive a list of users inside the room.")
      fn(reply.getArray("users"))
    })
  }

  def afterPartDo(token: String, room: String)(fn: => Unit) = {
    eb.send(csAddress, json(token).putString("action", "part").putString("room", room), f2h { reply =>
      tu.azzert(reply.getBoolean("success", false), "Should successfully part from room '" + room + "'.")
      fn
    })
  }

  def afterConnectAndJoinDo(nickname: String, rooms: String*)(fn: String => Unit) = afterConnectDo(nickname) {
    token =>
      val ai = new AtomicInteger(rooms.length)
      for (room <- rooms) {
        afterJoinDo(token, room) { users =>
          if (ai.decrementAndGet == 0) {
            fn(token)
          }
        }
      }
  }

  /* Real tests */

  def testConnect() = asyncTests(3) { tu =>
    eb.send(csAddress, new JsonObject().putString("action", "connect"), f2h {
      reply =>
        tu.azzert(!reply.getBoolean("success", true), "Shouldn't be successful (nickname missing).")
        tu.azzert(reply.getString("error") != null, "Should receive an error message.")
        tu.testComplete
    })

    eb.send(csAddress, new JsonObject().putString("action", "connect").putString("nickname", "sameNick"),
      f2h { reply =>
        tu.azzert(reply.getBoolean("success", false), "Should be successful first.")
        tu.azzert(reply.getString("token") != null, "Should receive a token.")
        eb.send(csAddress, new JsonObject().putString("action", "connect").putString("nickname", "sameNick"),
          f2h { reply =>
            tu.azzert(!reply.getBoolean("success", true), "Should not be able to connect with the sameNick anymore.")
            tu.azzert(reply.getString("error") != null, "Should receive an error message.")
            tu.testComplete
          })
      })

    afterConnectDo { token =>
      tu.testComplete
    }
  }

  def testJoin() = asyncTests(6) { tu =>
    val numPeople = 4

    def allChattersInListTest(users: JsonArray) = {
      tu.azzert(users.size == numPeople, "Should have " + numPeople + " chatters")
      tu.azzert(users.contains("nick1"), "Userlist should contain nick1")
      tu.azzert(users.contains("nick2"), "Userlist should contain nick2")
      tu.azzert(users.contains("nick3"), "Userlist should contain nick3")
      tu.azzert(users.contains("RandomGuy"), "Userlist should contain RandomGuy")
    }

    afterConnectDo("RandomGuy") { token =>

      eb.send(csAddress, json(token).putString("action", "join"), f2h { reply =>
        tu.azzert(!reply.getBoolean("success", true), "should not be successful (room missing)")
        tu.azzert(reply.getString("error") != null, "Should receive an error message.")
        tu.testComplete
      })

      eb.send(csAddress, json(token).putString("action", "join").putString("room", "someRoom1"), f2h { reply =>
        tu.azzert(reply.getBoolean("success", false), "Should succesfully join the room.")
        tu.azzert(reply.getArray("users") != null && reply.getArray("users").size > 0, "Should receive a list of users.")

        eb.send(csAddress, json(token).putString("action", "join").putString("room", "someRoom1"), f2h { reply =>
          tu.azzert(!reply.getBoolean("success", true), "Should not be able to join the same chatroom twice!")
          tu.azzert(reply.getString("error") != null, "Should receive an error message.")
          tu.testComplete
        })
      })

      // Should be able to join a second room
      afterJoinDo(token, "roomWith4People") { users =>
        tu.azzert(users.size >= 1 && users.size <= numPeople)
        tu.azzert(users.contains("RandomGuy"), "Should contain RandomGuy as user in chatter-list.")
        tu.testComplete {
          allChattersInListTest(users)
        }
      }

    }

    for (i <- 1 to (numPeople - 1)) {
      afterConnectDo("nick" + i) { token =>
        afterJoinDo(token, "roomWith4People") { users =>
          tu.azzert(users.size >= 1 && users.size <= numPeople)
          tu.azzert(users.contains("nick" + i), "Should contain nick" + i + " as user in chatter-list.")
          tu.testComplete {
            allChattersInListTest(users)
          }
        }
      }
    }

  }

  def testJoinAndPartMessages() = asyncTests(4) { tu =>
    afterConnectDo("joinAndPartPerson1") { token =>
      def cleanup = eb.unregisterHandler(clientPrefix + token)

      eb.registerHandler(clientPrefix + token, f2h { json =>
        json.getString("action") match {
          case "join" =>
            tu.azzert("joinAndPartPerson2" == json.getString("nickname"), "only person2 should join!")
            tu.azzert("testJoinAndPartMessagesRoom" == json.getString("room"), "person2 should only join room testJoinAndPartMessagesRoom!")
            tu.testComplete(cleanup)
          case "part" =>
            tu.azzert("joinAndPartPerson2" == json.getString("nickname"), "only person2 should part!")
            tu.azzert("testJoinAndPartMessagesRoom" == json.getString("room"), "person2 should only part room testJoinAndPartMessagesRoom!")
            tu.testComplete(cleanup)
          case unknown =>
            tu.azzert(false, "Received an unknown action - should not happen! action: " + json.encode)
        }
      })

      afterJoinDo(token, "testJoinAndPartMessagesRoom") { users =>
        tu.azzert(users.size == 1)
        afterJoinDo(token, "testJoinAndPartMessagesPerson2NotJoinedRoom") { users =>
          tu.azzert(users.size == 1)

          afterConnectDo("joinAndPartPerson2") { token2 =>
            afterJoinDo(token2, "testJoinAndPartMessagesRoom") { users =>
              tu.azzert(users.size == 2)

              eb.send(csAddress, json(token2).putString("action", "part").putString("room", "testJoinAndPartMessagesPerson2NotJoinedRoom"), f2h { reply =>
                tu.azzert(!reply.getBoolean("success", true), "Should not be able to part room 'testJoinAndPartMessagesPerson2NotJoinedRoom'.")
                tu.azzert(reply.getString("error") != null, "Should receive an error message.")
                tu.testComplete(cleanup)
              })

              afterPartDo(token2, "testJoinAndPartMessagesRoom") {
                tu.testComplete(cleanup)
              }
            }
          }
        }
      }
    }
  }

  def testPart() = asyncTests(2) { tu =>
    afterConnectDo("partPerson1") { token =>
      afterJoinDo(token, "testPartRoom") { users1 =>
        tu.azzert(users1.size == 1, "There can be only one.")

        afterConnectDo("partPerson2") { token =>
          afterJoinDo(token, "testPartRoom") { users2 =>
            tu.azzert(users2.size == 2, "There should be two users in the chat now.")

            eb.send(csAddress, json(token).putString("action", "part").putString("room", "testPartNotExistingRoom"), f2h { reply =>
              tu.azzert(!reply.getBoolean("success", true), "Should not be able to part room 'testPartNotExistingRoom'.")
              tu.azzert(reply.getString("error") != null, "Should receive an error message.")
              tu.testComplete
            })

            eb.send(csAddress, json(token).putString("action", "part").putString("room", "testPartRoom"), f2h { reply =>
              tu.azzert(reply.getBoolean("success", false), "Should be able to part room 'testPartRoom'.")
              tu.azzert(reply.getString("error") == null, "Should not receive an error message.")

              afterConnectDo("partPerson3") { token =>
                afterJoinDo(token, "testPartRoom") {
                  users3 =>
                    tu.azzert(users3.size == 2, "There should be two users in the chat now (since one user parted).")

                    tu.testComplete
                }
              }
            })
          }
        }
      }
    }
  }

  def testSend() = asyncTests(4) { tu =>
    val personName = "sendPerson"
    val roomName = "sendRoom"
    val message = "sendPerson1 to sendRoom1"
    afterConnectAndJoinDo(personName + 1, roomName) { token1 =>
      eb.registerHandler(clientPrefix + token1, f2h { msg =>
        msg.getString("action") match {
          case "message" =>
            tu.azzert(false, "Should not receive a message as sendPerson1")
          case "join" =>
          case unknown =>
            tu.azzert(false, "Should not receive this action as sendPerson1: " + msg.encode)
        }
      })

      afterConnectAndJoinDo(personName + 2, roomName) { token2 =>
        eb.registerHandler(clientPrefix + token2, f2h { msg =>
          msg.getString("action") match {
            case "message" =>
              tu.azzert(message == msg.getString("message"))
              tu.testComplete // 1
            case "join" =>
            case unknown =>
              tu.azzert(false, "Should not receive this action as sendPerson2: " + msg.encode)
          }
        })

        afterConnectAndJoinDo(personName + 3, roomName) { token3 =>
          eb.registerHandler(clientPrefix + token3, f2h { msg =>
            msg.getString("action") match {
              case "message" =>
                tu.azzert(message == msg.getString("message"))
                tu.testComplete // 1
              case unknown =>
                tu.azzert(false, "Should not receive this action as sendPerson3: " + msg.encode)
            }
          })

          eb.send(csAddress, jsonMessage(token1, roomName, message), f2h { reply =>
            tu.azzert(reply.getBoolean("success", false), "Send should be successful, but got " + reply.encode)
            tu.azzert(reply.getString("error") == null, "Should not receive an error message")
            tu.testComplete // 1
          })

          Thread.sleep(500) // Wait so all messages are received
          tu.testComplete // 1
        }
      }
    }
  }

  def testSendAfterLeaving() = asyncTests(2) { tu =>
    val roomName = "sendAfterLeaving"
    val personName = "sendAfterLeavingPerson"
    val message = personName + 1 + " to " + roomName
    afterConnectAndJoinDo(personName + 1, roomName) { token1 =>
      eb.registerHandler(clientPrefix + token1, f2h { msg =>
        msg.getString("action") match {
          case "message" =>
            tu.azzert(false, "Should not receive a message as sendAfterLeavingPerson1")
          case other => // egal
        }
      })

      afterConnectAndJoinDo(personName + 2, roomName) { token2 =>
        eb.registerHandler(clientPrefix + token2, f2h { msg =>
          msg.getString("action") match {
            case unknown =>
              tu.azzert(false, "Should not receive a message at all!")
          }
        })

        afterPartDo(token2, roomName) {
          eb.send(csAddress, jsonMessage(token1, roomName, message), f2h { reply =>
            tu.azzert(reply.getBoolean("success", false), "Send should be successful, but got " + reply.encode)
            tu.azzert(reply.getString("error") == null, "Should not receive an error message")
            tu.testComplete // 1
          })

          Thread.sleep(500) // Wait so all messages are received
          tu.testComplete // 1
        }
      }
    }
  }

  def testSendWithPartedPerson = asyncTests(3) { tu =>
    val personName = "sendWithPartedPerson"
    val roomName = "sendWithPartedPerson"
    val message = "from person1 to room"
    afterConnectAndJoinDo(personName + 1, roomName) { token1 =>
      eb.registerHandler(clientPrefix + token1, f2h { msg =>
        msg.getString("action") match {
          case "message" =>
            tu.azzert(false, "Should not receive a message as sendAfterLeavingPerson1")
          case "join" =>
          case "part" =>
          case unknown =>
            tu.azzert(false, "Received unknown message " + msg.encode)
        }
      })

      afterConnectAndJoinDo(personName + 2, roomName) { token2 =>
        eb.registerHandler(clientPrefix + token2, f2h { msg =>
          msg.getString("action") match {
            case "message" =>
              tu.azzert(message == msg.getString("message"), "Should receive message " + message)
              tu.azzert(personName + 1 == msg.getString("nickname"), "Should receive message from " + personName + 1)
              tu.azzert(roomName == msg.getString("room"), "Should receive the message in room " + roomName)
              tu.testComplete // 1
            case "join" =>
            case "part" =>
            case unknown =>
              tu.azzert(false, "Received unknown message " + msg.encode)
          }
        })

        afterConnectAndJoinDo(personName + 3, roomName) { token3 =>
          eb.registerHandler(clientPrefix + token3, f2h { msg =>
            msg.getString("action") match {
              case unknown =>
                tu.azzert(false, "Should not receive a message at all!")
            }
          })

          afterPartDo(token3, roomName) {
            eb.send(csAddress, jsonMessage(token1, roomName, message), f2h { reply =>
              tu.azzert(reply.getBoolean("success", false), "Send should be successful")
              tu.azzert(reply.getString("error") == null, "Should not receive an error message")
              tu.testComplete // 1
            })

            Thread.sleep(500) // Wait so all messages are received
            tu.testComplete // 1
          }
        }
      }
    }
  }

  def testSendPrivate() = asyncTests(3) { tu =>
    val personName = "sendPrivatePerson"
    val roomName = "sendPrivate"
    val message = "some test message from 1 to 3"
    afterConnectAndJoinDo(personName + 1, roomName) { token1 =>
      eb.registerHandler(clientPrefix + token1, f2h { msg =>
        msg.getString("action") match {
          case "join" =>
          case unknown =>
            tu.azzert(false, "Should not receive a message at all!")
        }
      })

      afterConnectAndJoinDo(personName + 2, roomName) { token2 =>
        eb.registerHandler(clientPrefix + token2, f2h { msg =>
          msg.getString("action") match {
            case "message" =>
              tu.azzert(false, "Should not receive a message at all but got: " + msg.encode)
            case "join" =>
            case unknown =>
              tu.azzert(false, "Received unknown message " + msg.encode)
          }
        })
        afterConnectAndJoinDo(personName + 3, roomName) { token3 =>
          eb.registerHandler(clientPrefix + token3, f2h { msg =>
            msg.getString("action") match {
              case "message" =>
                tu.azzert(message == msg.getString("message"), "message should be " + message)
                tu.azzert(personName + 1 == msg.getString("nickname"), "sender should be " + personName + 1)
                tu.azzert(msg.getString("room") == null, "should not receive a room at all")
                tu.testComplete // 1
              case unknown =>
                tu.azzert(false, "Received unknown message " + msg.encode)
            }
          })

          eb.send(csAddress, json(token1, "send").putString("nickname", personName + 3).putString("message", message), f2h { reply =>
            tu.azzert(reply.getBoolean("success", false), "send should be successful")
            tu.azzert(reply.getString("error") == null, "should not receive an error message")
            tu.testComplete // 1
          })

          Thread.sleep(500) // Wait so all messages are received
          tu.testComplete // 1
        }
      }
    }
  }

  def testDisconnect() = asyncTests(3) { tu =>
    val personName = "disconnectPerson"
    val roomName = "disconnectRoom"
    var exitedRoom1 = false
    var exitedRoom2 = false

    afterConnectAndJoinDo(personName + 1, roomName + 1, roomName + 2) {
      token1 =>
        eb.registerHandler(clientPrefix + token1, f2h {
          msg =>
            msg.getString("action") match {
              case "join" =>
              case "part" =>
                tu.azzert(personName + 2 == msg.getString("nickname"), "only person2 should part room")
                msg.getString("room") match {
                  case room if (roomName + 1 == room) =>
                    tu.azzert(!exitedRoom1, "should only part " + roomName + 1 + " once")
                    exitedRoom1 = true
                    tu.testComplete // 1
                  case room if (roomName + 2 == room) =>
                    tu.azzert(!exitedRoom2, "should only part " + roomName + 2 + " once")
                    exitedRoom2 = true
                    tu.testComplete // 1
                  case other =>
                    tu.azzert(false, "should not part any other room " + other)
                }
              case unknown =>
                tu.azzert(false, "should not receive any other message " + msg.encode)
            }
        })
        afterConnectAndJoinDo(personName + 2, roomName + 1, roomName + 2) {
          token2 =>
            eb.registerHandler(clientPrefix + token2, f2h {
              msg =>
                msg.getString("action") match {
                  case unknown =>
                    tu.azzert(false, "should not receive any message " + msg.encode)
                }
            })
            eb.send(csAddress, json(token2, "disconnect"), f2h {
              reply =>
                tu.azzert(reply.getBoolean("success", false), "should be able to disconnect")
                tu.azzert(reply.getString("error") == null, "should not receive an error message")
                tu.testComplete // 1
            })
        }
    }
  }

  def testTimeoutWithDisconnect() = asyncTests(3) { tu =>
    afterConnectDo("timeoutWithDisconnectPerson") {
      token =>
        var pinged = false
        eb.registerHandler(clientPrefix + token, f2h { msg =>
          msg.getString("action") match {
            case "ping" =>
              pinged = true
              tu.testComplete // 1
            case "disconnect" =>
              tu.azzert(pinged, "should have been pinged before disconnect")
              tu.testComplete // 1
            case unknown =>
              tu.azzert(false, "should not receive unknown message " + msg.encode)
          }
        })
        Thread.sleep(timeout + latencyTimeout + 1000L)
        tu.testComplete // 1
    }
  }

  def testTimeoutPing() = asyncTests(3) { tu =>
    afterConnectDo("timeoutPingPerson") { token =>
      eb.registerHandler(clientPrefix + token, f2h { msg =>
        msg.getString("action") match {
          case "ping" =>
            eb.send(csAddress, json(token, "ping"), f2h { reply =>
              tu.azzert(reply.getBoolean("success", false), "should have a successful ping. error: " + reply.encode)
              tu.azzert(reply.getString("error") == null, "should not get an error message")
              tu.testComplete // 2 (more than once for multiple pings)
            })
          case "disconnect" =>
            tu.azzert(false, "should not be disconnected")
          case unknown =>
            tu.azzert(false, "should not receive unknown message " + msg.encode)
        }
      })
      tu.testComplete // 1
    }
  }
}
