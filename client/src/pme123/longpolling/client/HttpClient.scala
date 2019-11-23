package pme123.longpolling.client


import java.util.concurrent.TimeUnit

import com.softwaremill.sttp._
import com.softwaremill.sttp.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.duration._

object HttpClient extends App {
  val url = "http://localhost:8088"
  //noinspection TypeAnnotation
  private implicit val sttpBackend = AsyncHttpClientZioBackend()

  def fetchNumbers: RIO[Clock with Console, Unit] =
    (for {
      numbers <- fetchNumbers("http://localhost:8088/5") // long polling of 5 seconds
      _ <- handleNumber(numbers)
    } yield ()).forever

  private def fetchNumbers(url: String) = {
    sttp
      .get(uri"$url")
      .response(asString)
      .send()
      .map(_.body)
      .map {
        case Left(msg) =>
          s"Exception: $msg"
        case Right(value) =>
          value
      }
      .tapError(error =>
        for{
          t <- clock.currentTime(TimeUnit.SECONDS)
          _ <- console.putStrLn(s"Failing attempt (${t % 1000} s): ${error.getMessage}")
        }yield ()
      )
      .retry(ZSchedule.recurs(5) && ZSchedule.exponential(1.second))

  }

  private def handleNumber(numbers: String) = {
    zio.console.putStrLn(s"Result: $numbers")
  }

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    fetchNumbers
      .tapError { ex => console.putStrLn(s"There was an exception: ${ex.getMessage}")}
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
        .catchAll{ ex =>
          console.putStrLn(s"There was an exception: ${ex.getMessage}")
        }
    }
}

case class ServiceException(msg: String) extends Throwable

case class DeserializeException(msg: String) extends Throwable