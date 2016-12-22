/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
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

package monix.execution.internal.collection

import java.util.ConcurrentModificationException

import monix.execution.internal.math.nextPowerOf2

import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * An [[drainToArray]] implementation that on overflow starts
  * dropping old elements.
  *
  * This implementation is not thread-safe and on the JVM it
  * needs to be synchronized.
  *
  * @param _recommendedCapacity is the recommended capacity that this queue will support,
  *        however the actual capacity will be the closest power of 2 that is bigger than
  *        the given number, or a maximum of 2^30^-1 (the maximum positive int that
  *        can be expressed as a power of 2, minus 1)
  */
private[monix] final class DropAllOnOverflowQueue[A : ClassTag] private (_recommendedCapacity: Int)
  extends EvictingQueue[A] { self =>

  require(_recommendedCapacity > 0, "recommendedCapacity must be positive")
  private[this] val maxSize = {
    val v = nextPowerOf2(_recommendedCapacity)
    if (v <= 1) 2 else v
  }

  private[this] val modulus = maxSize - 1
  def capacity = modulus

  private[this] val array = new Array[A](maxSize)
  // head is incremented by `poll()`, or by `offer()` on overflow
  private[this] var headIdx = 0
  // tail is incremented by `offer()`
  private[this] var tailIdx = 0

  override def isAtCapacity: Boolean =
    size >= modulus
  override def isEmpty: Boolean =
    headIdx == tailIdx
  override def nonEmpty: Boolean =
    headIdx != tailIdx

  def offer(elem: A): Int = {
    if (elem == null) throw new NullPointerException("Null is not supported")
    array(tailIdx) = elem
    tailIdx = (tailIdx + 1) & modulus

    if (tailIdx != headIdx) 0 else {
      // overflow just happened, dropping all by decrementing head
      headIdx = (headIdx - 1) & modulus
      capacity
    }
  }

  def offerMany(seq: A*): Long = {
    val iterator = seq.iterator
    var acc = 0L
    while (iterator.hasNext)
      acc += offer(iterator.next())
    acc
  }

  def poll(): A = {
    if (headIdx == tailIdx) null.asInstanceOf[A] else {
      val elem = array(headIdx)
      // incrementing head pointer
      headIdx = (headIdx + 1) & modulus
      elem
    }
  }

  def drainToArray(array: Array[A], offset: Int = 0): Int = {
    var arrayIdx = offset
    while (arrayIdx < array.length && headIdx != tailIdx) {
      array(arrayIdx) = self.array(headIdx)
      // incrementing head pointer
      headIdx = (headIdx + 1) & modulus
      arrayIdx += 1
    }

    arrayIdx - offset
  }

  override def drainToBuffer(buffer: mutable.Buffer[A], limit: Int): Int = {
    var count = 0
    while (headIdx != tailIdx && count < limit) {
      buffer += self.array(headIdx)
      // incrementing head pointer
      headIdx = (headIdx + 1) & modulus
      count += 1
    }

    count
  }

  override val hasDefiniteSize: Boolean =
    true

  override def size: Int = {
    if (tailIdx >= headIdx)
      tailIdx - headIdx
    else
      (maxSize - headIdx) + tailIdx
  }

  override def head: A = {
    if (headIdx == tailIdx)
      throw new NoSuchElementException("EvictingQueue is empty")
    else
      array(headIdx)
  }

  override def headOption: Option[A] = {
    try Some(head) catch {
      case _: NoSuchElementException =>
        None
    }
  }

  def iterator: Iterator[A] = {
    new Iterator[A] {
      private[this] var isStarted = false
      private[this] val initialHeadIdx = self.headIdx
      private[this] val initialTailIdx = self.tailIdx
      private[this] var tailIdx = 0
      private[this] var headIdx = 0

      def hasNext: Boolean = {
        if (initialHeadIdx != self.headIdx || initialTailIdx != self.tailIdx)
          throw new ConcurrentModificationException(s"headIdx != $initialHeadIdx || tailIdx != $initialTailIdx")

        if (!isStarted) init()
        headIdx != tailIdx
      }

      def next(): A = {
        if (!isStarted) init()
        if (headIdx == tailIdx)
          throw new NoSuchElementException("EvictingQueue.iterator is empty")
        else {
          val elem = array(headIdx)
          // incrementing head pointer
          headIdx = (headIdx + 1) & modulus
          elem
        }
      }

      private[this] def init(): Unit = {
        isStarted = true
        if (self.headIdx != self.tailIdx) {
          headIdx = initialHeadIdx
          tailIdx = initialTailIdx
        }
      }
    }
  }

  def clear(): Unit = {
    headIdx = 0
    tailIdx = 0
  }

  def length: Int = size
}

private[monix] object DropAllOnOverflowQueue {
  /**
    * Builder for [[DropAllOnOverflowQueue]]
    *
    * @param recommendedCapacity is the recommended capacity that this queue will support,
    *        however the actual capacity will be the closest power of 2 that is bigger or
    *        equal to the given number minus one, or a maximum of 2^30^-1 (the maximum
    *        positive int that can be expressed as a power of 2, minus 1)
   */
  def apply[A : ClassTag](recommendedCapacity: Int): DropAllOnOverflowQueue[A] = {
    new DropAllOnOverflowQueue[A](recommendedCapacity)
  }
}