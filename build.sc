import mill._
import mill.scalalib._

trait MyModule extends ScalaModule {
  def scalaVersion = "2.13.1"

  object version {
    val cats = "2.0.0"
    val circe = "0.12.1"
    val http4s = "0.21.0-M4"
    val sttp = "2.0.3"
    val zio = "1.0.0-RC18"
    val zioCats = "2.0.0.0-RC11"
    val zioConfig = "1.0.0-RC6"
  }

  object libs {
    val cats = ivy"org.typelevel::cats-core:${version.cats}"
    val circeCore = ivy"io.circe::circe-core:${version.circe}"
    val circeGeneric = ivy"io.circe::circe-generic:${version.circe}"
    val http4sBlazeServer =
      ivy"org.http4s::http4s-blaze-server:${version.http4s}"
    val http4sBlazeClient =
      ivy"org.http4s::http4s-blaze-client:${version.http4s}"
    val http4sCirce = ivy"org.http4s::http4s-circe:${version.http4s}"
    val http4sDsl = ivy"org.http4s::http4s-dsl:${version.http4s}"
    val sttpCore = ivy"com.softwaremill.sttp.client::core:${version.sttp}"
    val sttpClient =
      ivy"com.softwaremill.sttp.client::async-http-client-backend-zio:${version.sttp}"
    val sttpCirce = ivy"com.softwaremill.sttp.client::circe::${version.sttp}"
    val zio = ivy"dev.zio::zio:${version.zio}"
    val zioStream = ivy"dev.zio::zio-streams:${version.zio}"
    val zioCats = ivy"dev.zio::zio-interop-cats:${version.zioCats}"
  }

  object test extends Tests {
    override def ivyDeps = Agg(
      ivy"dev.zio::zio-test:${version.zio}",
      ivy"dev.zio::zio-test-sbt:${version.zio}"
    )

    def testOne(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }

    def testFrameworks =
      Seq("zio.test.sbt.ZTestFramework")
  }

  override def scalacOptions =
    defaultScalaOpts

  val defaultScalaOpts = Seq(
    "-deprecation", // Emit warning and location for usages of deprecated APIs.
    "-encoding", "UTF-8", // Specify character encoding used by source files.
    "-language:higherKinds", // Allow higher-kinded types
    "-language:postfixOps", // Allows operator syntax in postfix position (deprecated since Scala 2.10)
    "-feature" // Emit warning and location for usages of features that should be imported explicitly.
    //  "-Ypartial-unification",      // Enable partial unification in type constructor inference
    //  "-Xfatal-warnings"            // Fail the compilation if there are any warnings
  )

}

object server extends MyModule {
  override def ivyDeps = {
    Agg(
      libs.cats,
      libs.circeCore,
      libs.circeGeneric,
      libs.http4sBlazeServer,
      libs.http4sCirce,
      libs.http4sDsl,
      libs.sttpCore,
      libs.sttpClient,
      libs.sttpCirce,
      libs.zio,
      libs.zioStream,
      libs.zioCats
    )
  }
}

object client extends MyModule {
  override def ivyDeps = {
    Agg(
      libs.cats,
      libs.sttpCore,
      libs.sttpClient,
      libs.sttpCirce,
      libs.circeCore,
      libs.circeGeneric,
      libs.zio,
      libs.zioStream,
      libs.zioCats
    )
  }
}


