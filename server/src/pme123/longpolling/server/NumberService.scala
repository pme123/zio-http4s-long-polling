package pme123.longpolling.server

import java.util.concurrent.TimeUnit

import pme123.longpolling.server.NumberService.NumberServiceEnv
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.duration.{Duration, _}
import zio.random.Random


trait NumberService extends Serializable {
  val generator: NumberService.Service[NumberServiceEnv]
}

object NumberService {

  type NumberServiceEnv = Console with Clock with Random

  type GeneratorTask[A] = RIO[NumberServiceEnv, A]

  trait Service[R <: NumberServiceEnv] {

    def nextNumbers(maxDuration: Duration): RIO[R, List[Int]]
  }

  object > extends Service[NumberService with NumberServiceEnv] {

    final def nextNumbers(maxDuration: Duration): RIO[NumberService with NumberServiceEnv, List[Int]] =
      ZIO.accessM(_.generator.nextNumbers(maxDuration))

  }

  trait Live extends NumberService {

    def queue: Queue[Int]

    val generator: Service[NumberServiceEnv] = new Service[NumberServiceEnv] {

      def nextNumbers(maxDuration: Duration): RIO[NumberServiceEnv, List[Int]] =
        for {
          numbers <- getAtLeastOne(queue, maxDuration)
          // add some random exception
          _ <- if (numbers.nonEmpty && numbers.head > 4500) ZIO.fail(new IllegalStateException(s"Problem generating number ${numbers.head} > 900")) else ZIO.unit
        } yield numbers
    }

    private def getAtLeastOne(queue: Queue[Int], maxDuration: Duration): RIO[Clock with Random, List[Int]] =
      for {
        numbers <- queue.takeAll
        result <-
          if (numbers.nonEmpty || maxDuration <= 0.second)
            ZIO.effectTotal(numbers)
          else
            ZIO.sleep(50.millis) *> getAtLeastOne(queue, Duration(maxDuration.toMillis - 50, TimeUnit.MILLISECONDS))
      } yield result

  }

}