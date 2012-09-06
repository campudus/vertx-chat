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

import org.vertx.java.deploy.Verticle

/**
 * Creates and starts the Campudus chat module.
 * @author <a href="http://www.campudus.com/">Joern Bernhardt, Maximilian Stemplinger</a>
 */
class Chat extends Verticle {

  override def start() {
    val logger = container.getLogger()
    val config = container.getConfig()
    val address = config.getString("address", "campudus.chat")
    val chatClientPrefix = config.getString("client-prefix", "campudus.chatters.")
    val sharedMapPrefix = config.getString("map-prefix", "com.campudus.vertx.chat.")
    val timeout = config.getNumber("timeout", 2 * 60 * 1000).longValue
    val latencyTimeout = config.getNumber("latency-timeout", 2000).longValue

    vertx.eventBus().registerHandler(address, new ChatDataHandler(this, chatClientPrefix, sharedMapPrefix, timeout, latencyTimeout))

    logger.info("Campudus chat module started with " + timeout + "ms timeout and " + latencyTimeout + "ms latency timeout.")
  }
}