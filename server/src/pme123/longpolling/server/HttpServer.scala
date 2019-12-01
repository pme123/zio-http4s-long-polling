package pme123.longpolling.server

import java.util.concurrent.TimeUnit

import io.circe.{Decoder, Encoder}
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import pme123.longpolling.server.NumberService.NumberServiceEnv
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console._
import zio.duration._
import zio.interop.catz._
import zio.random.Random

object HttpServer extends App {
  type AppEnvironment = NumberServiceEnv with NumberService

  type AppTask[A] = RIO[AppEnvironment, A]

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]

  import dsl._

  def routes: HttpRoutes[AppTask] =
    HttpRoutes.of[AppTask] {
      case GET -> Root / IntVar(duration) =>
        NumberService.>.nextNumbers(Duration(duration, TimeUnit.SECONDS))
          .tapError(e => putStrLn(s"Server Exception: $e"))
          .tap(r => putStrLn(s"Result is $r"))
          .foldM(_ => InternalServerError(), Ok(_))
    }

  def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    program
      .foldM(
        err => putStrLn(s"Program failed: $err") *> ZIO.succeed(1),
        v => putStrLn(s"Program succeded: $v") *> ZIO.succeed(0)
      )

  private def program = {
    ZIO.runtime[ZEnv]
      .flatMap { implicit rts =>
        for {
          _ <- zio.console.putStrLn("Server startup...")
          queue <- Queue.bounded[Int](1000)
          numberProvider <- numberProvider(rts, queue).fork
          numberService <- numberService(queue).fork
          _ <- zio.console.putStrLn("Server startup finished")
          _ <- numberProvider.join
          _ <- numberService.join
          _ <- zio.console.putStrLn("Server shutdown finished")
        } yield numberService
      }
  }

  private def numberProvider(rts: Runtime[zio.ZEnv], qu: Queue[Int]) = {
    NumberProvider.>.run(rts)
      .provideSome[ZEnv](_ =>
        new Blocking.Live with NumberProvider.Live {
          def queue: Queue[Int] = qu
        })
  }

  private def numberService(qu: Queue[Int]) = {
    ZIO.runtime[AppEnvironment].flatMap { implicit rts =>
      BlazeServerBuilder[AppTask]
        .bindHttp(8088, "localhost")
        .withHttpApp(routes.orNotFound)
        .serve
        .compile
        .drain
    }.provideSome[ZEnv](_ =>
      new Console.Live with Clock.Live with Random.Live with NumberService.Live {
        def queue: Queue[Int] = qu
      })
  }


  implicit def circeJsonDecoder[A](implicit decoder: Decoder[A]): EntityDecoder[AppTask, A] = jsonOf[AppTask, A]

  implicit def circeJsonEncoder[A](implicit decoder: Encoder[A]): EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

}