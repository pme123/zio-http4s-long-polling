package pme123.longpolling.server

import java.util.concurrent.TimeUnit

import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.console.Console
import zio.duration._
import zio.random.Random
import zio.stream.ZStream
import zio.{Has, Queue, RIO, Runtime, ZIO, ZLayer, random}

object number {

  object provider {
    type NumberProvider = Has[NumberProvider.Service]

    object NumberProvider {

      type NumberProviderEnv = Blocking

      trait Service {
        def run(queue: Queue[Int], rts: Runtime[Random with Clock]): RIO[NumberProviderEnv, Unit]
      }

      def run(queue: Queue[Int], rts: Runtime[Random with Clock]): RIO[NumberProvider with NumberProviderEnv, Unit] =
        ZIO.accessM(_.get.run(queue, rts))

      val live: ZLayer[Blocking, Nothing, NumberProvider] = ZLayer.succeed(
        new Service {
          def run(queue: Queue[Int], rts: Runtime[Random with Clock]): RIO[NumberProviderEnv, Unit] =
            ZStream
              .fromEffect {
                effectBlocking {
                  rts.unsafeRun(
                    for {
                      count <- random.nextInt(10)
                      nr <- random.nextInt(6000)
                      _ <- ZIO.sleep(nr.millis)
                      _ <- queue.offerAll((1 to (count + 1)).map(nr / _))
                    } yield ()
                  )
                }
              }
              .forever
              .foreach(_ => ZIO.unit)
        }
      )
    }

  }

  object service {

    type NumberService = Has[NumberService.Service]

    object NumberService {

      type NumberServiceEnv = Console with Clock with Random

      trait Service {
        def nextNumbers(qu: Queue[Int], maxDuration: Duration): ZIO[Clock, Throwable, List[Int]]
      }

      val live: ZLayer[Blocking with Clock, Nothing, NumberService] = ZLayer.fromFunction{ bloWithClock: Blocking with Clock =>
        def getAtLeastOne(queue: Queue[Int], maxDuration: Duration): RIO[Clock, List[Int]] =
          for {
            numbers <- queue.takeAll
            result <-
              if (numbers.nonEmpty || maxDuration <= 0.second)
                ZIO.effectTotal(numbers)
              else
                ZIO.sleep(50.millis) *> getAtLeastOne(queue, Duration(maxDuration.toMillis - 50, TimeUnit.MILLISECONDS))
          } yield result

        new Service {
          def nextNumbers(qu: Queue[Int], maxDuration: Duration): ZIO[Clock, Throwable, List[Int]] =
            for {
              numbers <- getAtLeastOne(qu, maxDuration)
              _ <-  // add some random exception
                ZIO.fail(new IllegalStateException(s"Problem generating number ${numbers.head} > 5500"))
                  .when(numbers.nonEmpty && numbers.head > 5500)
            } yield numbers
        }
      }


      final def nextNumbers(qu: Queue[Int], maxDuration: Duration): RIO[NumberService with NumberServiceEnv, List[Int]] =
        ZIO.accessM(_.get.nextNumbers(qu, maxDuration))

    }

  }

}



