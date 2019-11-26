package pme123.longpolling.client


import java.util.concurrent.TimeUnit

import com.softwaremill.sttp._
import com.softwaremill.sttp.asynchttpclient.zio.AsyncHttpClientZioBackend
import com.softwaremill.sttp.circe._
import io.circe
import zio.clock.Clock
import zio.console.Console
import zio.duration._
import zio.{DefaultRuntime, RIO, ZEnv, ZIO, ZSchedule, clock, console}


object HttpClient extends zio.App {
  //noinspection TypeAnnotation
  private implicit val sttpBackend = AsyncHttpClientZioBackend()

  def fetchNumbers: RIO[Clock with Console, Unit] =
    fetchNumbers("http://localhost:8088/5")
      .flatMap(handleNumbers)
      .forever

  private def fetchNumbers(url: String) = {
    sttp
      .get(uri"$url")
      .response(asJson[List[Int]])
      .send()
      .map(_.body)
      .flatMap {
        case o: Either[String, Either[DeserializationError[circe.Error], List[Int]]] =>ZIO.succeed(o)
        case Left(msg) =>
          console.putStrLn(s"Problem with the service: $msg") *>
          ZIO.succeed(Nil)
        case Right(Left(errors)) =>
          console.putStrLn(s"Problem to deserialize the result: $errors") *>
            ZIO.succeed(Nil)
        case Right(Right(value)) =>
          ZIO.succeed(value)
      }
      .tapError(error =>
        for {
          t <- clock.currentTime(TimeUnit.SECONDS)
          _ <- console.putStrLn(s"Failing attempt (${t % 100} s): ${error.getMessage}")
        } yield ()
      )
      .retry(ZSchedule.recurs(5) && ZSchedule.exponential(1.second))

  }

  private def handleNumbers(numbers: Seq[Int]) =
    ZIO.foreachPar(numbers) { number =>
      (for {
        _ <- ZIO.sleep(1.second)
        t <- clock.currentTime(TimeUnit.SECONDS)
        _ <- console.putStrLn(s"Result (${t % 1000} s): $number")
      } yield ())
    }

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    fetchNumbers
      .tapError { ex => console.putStrLn(s"There was an exception: ${ex.getMessage}") }
      .fold(_ => -1, _ => 0)

  def program: ZIO[ZEnv, Throwable, Unit] =
    ZIO.runtime[ZEnv].flatMap { rt =>
      (for {
        _ <- zio.console.putStrLn(s"New Request")
        start <- clock.currentDateTime
        numbers <- fetchNumbers
        end <- clock.currentDateTime
        _ <- zio.console.putStrLn(s"Time taken: ${end.toInstant.toEpochMilli - start.toInstant.toEpochMilli} ms")
        _ <- zio.console.putStrLn(s"Result: $numbers")
        _ <- ZIO.sleep(100.millis)
      } yield ())
        .catchAll { ex =>
          console.putStrLn(s"There was an exception: ${ex.getMessage}")
        }
    }
}

case class ServiceException(msg: String) extends Throwable

case class DeserializeException(msg: String) extends Throwable

object MyApp extends App {
  new DefaultRuntime {}
    .unsafeRun(
      HttpClient.fetchNumbers
        .tapError { ex => console.putStrLn(s"There was an exception: ${ex.getMessage}") }
        .fold(_ => -1, _ => 0)
    )
}