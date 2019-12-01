package pme123.longpolling.server

import pme123.longpolling.server.NumberProvider.NumberProviderEnv
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.random.Random
import zio.stream.ZStream
import zio.{Queue, RIO, Runtime, ZIO, random}
import zio.duration._

trait NumberProvider extends Serializable {
  val numberProvider: NumberProvider.Service[NumberProviderEnv]
}

object NumberProvider {

  type NumberProviderEnv = Blocking

  type ProviderTask[A] = RIO[NumberProviderEnv, A]

  trait Service[R <: NumberProviderEnv] {

    def run(rts: Runtime[Random with Clock]): RIO[R, Unit]
  }

  object > extends Service[NumberProvider with NumberProviderEnv] {


    def run(rts: Runtime[Random with Clock]): RIO[NumberProvider with NumberProviderEnv, Unit] =
      ZIO.accessM(_.numberProvider.run(rts))
  }

  trait Live extends NumberProvider {
    def queue: Queue[Int]

    val numberProvider: Service[NumberProviderEnv] = new Service[NumberProviderEnv] {

      def run(rts: Runtime[Random with Clock]): RIO[NumberProviderEnv, Unit] =
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
  }
}

