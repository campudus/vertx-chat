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

import org.junit.Test
import org.vertx.java.framework.TestBase

class ChatTest extends TestBase {

  @throws(classOf[Exception])
  override protected def setUp() {
    super.setUp()
    startApp(classOf[ChatTestClient].getName())
  }

  @throws(classOf[Exception])
  override protected def tearDown() {
    super.tearDown()
  }

  @Test
  def testConnect = startTest(getMethodName)

  @Test
  def testJoin = startTest(getMethodName)

  @Test
  def testPart = startTest(getMethodName)

  @Test
  def testJoinAndPartMessages = startTest(getMethodName)

  @Test
  def testSend = startTest(getMethodName)

  @Test
  def testSendAfterLeaving = startTest(getMethodName)

  @Test
  def testSendWithPartedPerson = startTest(getMethodName)

  @Test
  def testSendPrivate = startTest(getMethodName)

  @Test
  def testDisconnect = startTest(getMethodName)

  @Test
  def testTimeoutWithDisconnect = startTest(getMethodName)

  @Test
  def testTimeoutPing = startTest(getMethodName)

}

