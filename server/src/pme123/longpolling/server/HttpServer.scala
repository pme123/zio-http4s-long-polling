package pme123.longpolling.server

import java.util.concurrent.TimeUnit

import io.circe.{Decoder, Encoder}
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import pme123.longpolling.server.number.provider.NumberProvider
import pme123.longpolling.server.number.service.NumberService
import pme123.longpolling.server.number.service.NumberService.NumberServiceEnv
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console._
import zio.duration._
import zio.interop.catz._

object HttpServer extends App {

  type AppEnvironment = NumberServiceEnv with NumberService

  type AppTask[A] = RIO[AppEnvironment, A]

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]

  import dsl._

  def routes(qu: Queue[Int]): HttpRoutes[AppTask] =
    HttpRoutes.of[AppTask] {
      case GET -> Root / IntVar(duration) =>
        NumberService.nextNumbers(qu, Duration(duration, TimeUnit.SECONDS))
          .tapError(e => putStrLn(s"Server Exception: $e"))
          .tap(r => putStrLn(s"Result is $r"))
          .foldM(_ => InternalServerError(), Ok(_))
    }

  def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    program
      .foldM(
        err => putStrLn(s"Program failed: $err").as(1),
        v => putStrLn(s"Program succeded: $v").as(0)
      )

  private def program = {
    ZIO.runtime[ZEnv]
      .flatMap { implicit rts =>
        for {
          _ <- zio.console.putStrLn("Server startup...")
          queue <- Queue.bounded[Int](1000)
          _ <- numberService(queue).fork // runs forever
          _ <- numberProvider(rts, queue) // runs forever
          _ <- zio.console.putStrLn("Server shutdown finished")
        } yield ()
      }.provideCustomLayer((Blocking.live >>> NumberProvider.live) ++
      ((Blocking.live ++ Clock.live) >>> NumberService.live))
  }

  private def numberProvider(rts: Runtime[zio.ZEnv], qu: Queue[Int]) =
    NumberProvider.run(qu, rts)

  //   .provideCustomLayer(NumberProvider.live(qu))

  private def numberService(qu: Queue[Int]) = {
    ZIO.runtime[AppEnvironment].flatMap { implicit rts =>
      BlazeServerBuilder[AppTask]
        .bindHttp(8088, "localhost")
        .withHttpApp(routes(qu).orNotFound)
        .serve
        .compile
        .drain
    } //.provideCustomLayer(NumberService.live(qu))
  }

  implicit def circeJsonDecoder[A](implicit decoder: Decoder[A]): EntityDecoder[AppTask, A] = jsonOf[AppTask, A]

  implicit def circeJsonEncoder[A](implicit decoder: Encoder[A]): EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

}