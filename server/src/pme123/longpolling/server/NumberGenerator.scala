package pme123.longpolling.server

import java.util.concurrent.TimeUnit

import pme123.longpolling.server.NumberGenerator.GeneratorEnv
import zio._
import zio.blocking._
import zio.clock.Clock
import zio.console.Console
import zio.duration.{Duration, _}
import zio.random.Random
import zio.stream.ZStream


trait NumberGenerator extends Serializable {
  val generator: NumberGenerator.Service[GeneratorEnv]
}

object NumberGenerator {

  type GeneratorEnv = Console with Clock with Random with Blocking with NumberGenerator

  type GeneratorTask[A] = RIO[GeneratorEnv, A]

  trait Service[R <: Clock] {
    def initQueue(rts: Runtime[GeneratorEnv]): RIO[R, Queue[Int]]

    def nextNumbers(queue: Queue[Int], maxDuration: Duration, rts: Runtime[Random with Clock]): RIO[R, List[Int]]
  }

  object > extends Service[GeneratorEnv] {

    final def initQueue(rts: Runtime[GeneratorEnv]): RIO[GeneratorEnv, Queue[Int]] =
      ZIO.accessM(_.generator.initQueue(rts))

    final def nextNumbers(queue: Queue[Int], maxDuration: Duration, rts: Runtime[Random with Clock]): ZIO[GeneratorEnv, Throwable, List[Int]] =
      ZIO.accessM(_.generator.nextNumbers(queue, maxDuration, rts))

  }

  trait Live extends NumberGenerator {

    val generator: Service[GeneratorEnv] = new Service[GeneratorEnv] {

      def initQueue(rts: Runtime[GeneratorEnv]): RIO[GeneratorEnv, Queue[Int]] =
        for {
          _ <- console.putStrLn("Start next Numbers")
          queue <- Queue.bounded[Int](1000)
          _ <- numberProvider(queue, rts).fork
        } yield queue

      def nextNumbers(queue: Queue[Int], maxDuration: Duration, rts: Runtime[Random with Clock]): RIO[GeneratorEnv, List[Int]] =
        for {
          numbers <- getAtLeastOne(queue, maxDuration)
          // add some random exception
          _ <- if (numbers.nonEmpty && numbers.head > 900) ZIO.fail(new IllegalStateException(s"Problem generating number ${numbers.head} > 900")) else ZIO.unit
        } yield numbers

    }

    private def numberProvider(queue: Queue[Int], rts: Runtime[Random with Clock]) =
      ZStream
        .fromEffect {
          effectBlocking {
            rts.unsafeRun(
              for {
                nr <- random.nextInt(1000)
                _ = Thread.sleep(nr)
                _ <- queue.offer(nr)
              } yield ()
            )
          }
        }
        .forever
        .foreach(_ => ZIO.unit)

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