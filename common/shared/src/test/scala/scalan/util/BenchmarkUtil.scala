package scalan.util

import debox.cfor

import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

/** Helper classes for measuring time and printing timings. */
object BenchmarkUtil {
  /** Execute `action` given number of iterations printing time for each iteration
    * and the total time. */
  def measure[T](nIters: Int, okShowIterTime: Boolean = true, okShowTotalTime: Boolean = true)
      (action: Int => Unit): Unit = {
    var sum = 0L
    cfor(0)(_ < nIters, _ + 1) { i =>
      val start = System.currentTimeMillis()
      val res = action(i)
      val end = System.currentTimeMillis()
      val iterTime = end - start
      if (okShowIterTime)
        println(s"Iter $i: $iterTime ms")
      sum += iterTime
    }
    if (okShowTotalTime) println(s"Total time: $sum ms")
  }

  /** Execute block and measure the time of its execution. */
  def measureTime[T](action: => T): (T, Long) = {
    val t0 = System.currentTimeMillis()
    val res = action
    val t = System.currentTimeMillis()
    (res, t - t0)
  }

  /** Execute block and measure the time of its execution in nanoseconds. */
  def measureTimeNano[T](block: => T): (T, Long) = {
    val start = System.nanoTime()
    val res = block
    val end = System.nanoTime()
    (res, end - start)
  }

  def runTasks(nTasks: Int)(block: Int => Unit) = {
    val (_, total) = measureTime {
      val tasks = (1 to nTasks).map(iTask => Future(block(iTask)))
      val res = Await.result(Future.sequence(tasks), Duration.Inf)
    }
    println(s"Completed $nTasks tasks in $total msec")
  }
}
