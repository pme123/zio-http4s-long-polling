package pme123.longpolling.server

import java.util.concurrent.TimeUnit

import io.circe.{Decoder, Encoder}
import org.http4s._
import org.http4s.implicits._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import pme123.longpolling.server.NumberGenerator.GeneratorEnv
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.putStrLn
import zio.duration._
import zio.interop.catz._
import zio.random.Random
import zio.{App, Queue, RIO, Runtime, ZEnv, ZIO}

object HttpServer extends App {
  type AppEnvironment = GeneratorEnv

  type AppTask[A] = RIO[AppEnvironment, A]

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]

  import dsl._

  def service(queue: Queue[Int], rts: Runtime[Random with Clock]): HttpRoutes[AppTask] =
    HttpRoutes.of[AppTask] {
      case GET -> Root / IntVar(duration) =>
        NumberGenerator.>.nextNumbers(queue, Duration(duration, TimeUnit.SECONDS), rts)
          .tapError(e => putStrLn(s"Server Exception: $e"))
          .tap(r => putStrLn(s"Result is $r"))
          .foldM(_ => InternalServerError(), Ok(_))
    }

  def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    program
      .provideSome[ZEnv] { _ =>
        new zio.console.Console.Live with Clock.Live with Random.Live with Blocking.Live with NumberGenerator.Live
      }.foldM(
      err => putStrLn(s"Program failed: $err") *> ZIO.succeed(1),
      v => putStrLn(s"Program succeded: $v") *> ZIO.succeed(0)
    )

  private def program = {
    ZIO.runtime[AppEnvironment]
      .flatMap { implicit rts =>
        for {
          queue <- NumberGenerator.>.initQueue(rts)
          httpApp = Router[AppTask](
            "/" -> service(queue, rts)
          ).orNotFound
        } yield
          BlazeServerBuilder[AppTask]
            .bindHttp(8088, "localhost")
            .withHttpApp(httpApp)
            .serve
            .compile
            .drain

      }.flatten
  }


  implicit def circeJsonDecoder[A](implicit decoder: Decoder[A]): EntityDecoder[AppTask, A] = jsonOf[AppTask, A]

  implicit def circeJsonEncoder[A](implicit decoder: Encoder[A]): EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

}