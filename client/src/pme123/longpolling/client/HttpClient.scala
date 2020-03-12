package pme123.longpolling.client


import java.util.concurrent.TimeUnit

import sttp.client._
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.client.circe._
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.duration._


object HttpClient extends zio.App {

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    program
      .fold(_ => -1, _ => 0)

  def program: ZIO[ZEnv, Throwable, Unit] =
    (for {
      _ <- zio.console.putStrLn(s"New Request")
      backend <- AsyncHttpClientZioBackend()
      _ <- fetchNumbers(backend)
    } yield ())
      .catchAll { ex =>
        console.putStrLn(s"There was an exception on the Client: ${ex.getMessage}")
      }

  private def fetchNumbers(implicit sttpBackend: SttpBackend[Task, Nothing, NothingT]): RIO[Clock with Console, Unit] =
    fetchNumbers("http://localhost:8088/3")
      .tap(l => console.putStrLn(s"Result has ${l.size}"))
      .flatMap(handleNumbers)
      .forever

  private def fetchNumbers(url: String)(implicit sttpBackend: SttpBackend[Task, Nothing, NothingT]) = {
    basicRequest
      .get(uri"$url")
      .response(asJson[List[Int]])
      .send()
      .map(_.body)
      .flatMap {
        case Left(msg) =>
          console.putStrLn(s"Problem with the service: $msg") *>
            ZIO.succeed(Nil)
        case Right(value) =>
          ZIO.succeed(value)
      }
      .tapError(error =>
        for {
          t <- clock.currentTime(TimeUnit.SECONDS)
          _ <- console.putStrLn(s"Failing attempt (${t % 100} s): ${error.getMessage}")
        } yield ()
      )
      .retry(Schedule.recurs(5) && Schedule.exponential(1.second))

  }

  private def handleNumbers(numbers: Seq[Int]) =
    ZIO.foreachPar(numbers) { number =>
      for {
        _ <- ZIO.sleep(1.second)
        t <- clock.currentTime(TimeUnit.SECONDS)
        _ <- console.putStrLn(s"Result (${t % 1000} s): $number")
      } yield ()
    }
}

case class ServiceException(msg: String) extends Throwable

case class DeserializeException(msg: String) extends Throwable
