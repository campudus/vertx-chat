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

package com.campudus.scala.helpers

import java.util.concurrent.ConcurrentMap
import scala.annotation.tailrec

/**
 * Some helper methods for concurrent maps. It allows you to merge values consisting of containers inside the maps.
 * @author <a href="http://www.campudus.com/">Joern Bernhardt</a>
 */
object MapHelper {

  trait Merger[Value, Container] {
    def valueToContainer(v: Value): Container
    def mergeIntoContainer(v: Value, c: Container): Container
    def removeFromContainer(v: Value, c: Container): Container
  }

  trait ListMerger[X] extends Merger[X, List[X]] {
    def valueToContainer(v: X) = v :: Nil
    def mergeIntoContainer(v: X, c: List[X]) = if (c.contains(v)) { c } else { v :: c }
    def removeFromContainer(v: X, c: List[X]) = c.filterNot(_ == v)
  }

  implicit object CharListMerger extends ListMerger[Char]
  implicit object DoubleListMerger extends ListMerger[Double]
  implicit object FloatListMerger extends ListMerger[Float]
  implicit object IntListMerger extends ListMerger[Int]
  implicit object LongListMerger extends ListMerger[Long]
  implicit object StringListMerger extends ListMerger[String]

  def mergeIntoConcurrentMap[K, V, C](map: ConcurrentMap[K, C], key: K, value: V)(implicit merger: Merger[V, C]) {
    @tailrec
    def putIntoMap(): Unit = map.putIfAbsent(key, merger.valueToContainer(value)) match {
      case null =>
      case oldContainer =>
        if (!map.replace(key, oldContainer, merger.mergeIntoContainer(value, oldContainer))) {
          putIntoMap()
        }
    }

    putIntoMap()
  }

  def removeFromConcurrentMap[K, V, C](map: ConcurrentMap[K, C], key: K, value: V)(implicit merger: Merger[V, C]): Boolean = {
    val container = map.get(key)
    if (container == null) {
      false
    } else {
      val containerWithoutValue = merger.removeFromContainer(value, container)
      if (containerWithoutValue == container) {
        false
      } else if (map.replace(key, container, containerWithoutValue)) {
        true
      } else {
        removeFromConcurrentMap(map, key, value)(merger)
      }
    }
  }

}
