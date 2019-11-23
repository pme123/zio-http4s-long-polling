# Long Polling with ZIO

After checking out some Blogs (see https://github.com/pme123/zio-examples), 
I wanted to try _ZIO_ with an own problem.

## Scenario ##
In our Play Application we use a Rest Service that provides _Long Polling_ for getting Task from a Queue internally.

So our first solution was just to pull this Service:
```
  actorSystem.scheduler.schedule(initialDelay = 5.seconds, interval = 1.seconds) {
    fetchAndProcessTasks()
  }

  def fetchAndProcessTasks(): Future[Unit] = {
    externalTaskApi.fetchAndLock(taskRequest).flatMap { externalTasks =>
      if (externalTasks.nonEmpty)
        processExternalTasks(externalTasks)
      else
        Future.successful({})
    }.recover {
      case NonFatal(ex) => 
		error(s"Unable to fetch external tasks - $ex")
    }
  }
```

`externalTaskApi.fetchAndLock(taskRequest)` supports _Long Polling_, we just needed to add this to the `taskRequest`.

This was my first solution:

```
 fetchAndProcessTasks() // start polling

  def fetchAndProcessTasks(): Future[Unit] = {
    externalTaskApi.fetchAndLock(taskRequest).map { externalTasks =>
      if (externalTasks.nonEmpty)
        processExternalTasks(externalTasks)
      else
        Future.successful({})
    }.recover {
      case NonFatal(ex) =>
        error(s"Unable to fetch external tasks - $ex")
    }.flatMap(_ => fetchAndProcessTasks())
  }
```

You may spot the problem: `Future[Future[_]]`. 
If the Class is restarted there are 2 `fetchAndProcessTasks()` running now - Really Bad!

Adding a flag works (because it is a singleton) but is not so _Scala_ style:

```
  @volatile var fetchNextTasks = true // flag to stop recursive async call
  fetchAndProcessTasks() // start polling
  // Shut-down hook to stop the recursive call
  coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "Shutdown Report Engine") { () =>
    Future.successful {
      fetchNextTasks = false
      Done
    }
  }

  def fetchAndProcessTasks(): Future[Unit] = {
    externalTaskApi.fetchAndLock(taskRequest).map { externalTasks =>
      if (externalTasks.nonEmpty)
        processExternalTasks(externalTasks)
      else
        Future.successful({})
    }.recover {
      case NonFatal(ex) =>
        error(s"Unable to fetch external tasks - $ex")
    }.flatMap(_ =>
      if (fetchNextTasks)
        fetchAndProcessTasks()
      else
        Future.unit
    )
  }
```

So in the end we introduced a _Typed Actor_:

```
  ...
  val system: ActorSystem[ExternalTasksFetchActor.Message] =
    ActorSystem(ExternalTasksFetchActor(externalTaskProcessors, externalTaskApi,testSpecsGeneration).stopped(), "ExternalTaskFetchActor")
  system ! ExternalTasksFetchActor.Start


  // Shut-down hook to stop the recursive call
  coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "Shutdown Report Engine") { () =>
    Future.successful {
      system ! ExternalTasksFetchActor.Stop
      Done
    }
  }
}

sealed case class ExternalTasksFetchActor(
                              externalTaskProcessors: Set[ExternalTaskProcessor],
                              externalTaskApi: ExternalTaskApi,
                              testSpecsGeneration: TestSpecsGeneration,
                            )(implicit cred: Cred, executionContext: ExecutionContext)
  extends Logging {

  def stopped(): Behavior[Message] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case Start =>
          debug("Start Fetching ...")
          context.self ! Fetch
          running()
        case other =>
          warn(s"Unexpected Message: $other")
          Behavior.same
      }
    }

  def running(): Behavior[Message] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case Fetch =>
          debug("Fetch Tasks")
          fetchTasks(context.self)
          Behavior.same
        case Stop =>
          debug("Stop fetching")
          Behaviors.stopped
        case Start =>
          warn("Start received that was not expected")
          Behavior.same
      }
    }
   ....
```

This is now safe but Wow so much code!

## With ZIO
So let's start with the request call.
I use [sttp](https://github.com/softwaremill/sttp) for this.
```
      for {
            numbers <- sttp
              .get(uri"""$url/5""") // long polling of 5 seconds
              .response(asString)
              .send()
              .map(_.body)
            _ <- zio.console.putStrLn(s"Result: $numbers")
          } yield ()
```
First we have a simple _GET_ request to the defined _url_. 
The result we log to the console (as an Effect).

So this will work exactly once. So do this **forever**:
```
(for {
     ...
    } yield ()).forever
```
All we need to add is, of course `forever`

What about some _Resilience_? 
For example with this code, whenever you restart the server, the client will die.
```
numbers <- sttp
        .get(uri"""$url/5""") // long polling of 5 seconds
        ...
        .tapError(error => console.putStrLn(s"Failing attempt: ${error.getMessage}"))
        .retry(ZSchedule.recurs(5) && ZSchedule.exponential(1.second))
```
Again we can add this with one line. 
I choose 5 attempts each of them waits twice the time as the one before.
`tapError` provides a nice way to log the failing attempts.
```
Failing attempt (141 s): Connection refused: localhost/0:0:0:0:0:0:0:1:8088
Failing attempt (143 s): Connection refused: localhost/0:0:0:0:0:0:0:1:8088
Failing attempt (147 s): Connection refused: localhost/0:0:0:0:0:0:0:1:8088
Failing attempt (155 s): Connection refused: localhost/0:0:0:0:0:0:0:1:8088
Failing attempt (171 s): Connection refused: localhost/0:0:0:0:0:0:0:1:8088
Failing attempt (203 s): Connection refused: localhost/0:0:0:0:0:0:0:1:8088
There was an exception: Connection refused: localhost/0:0:0:0:0:0:0:1:8088
```
The Log shows nicely how each attempt needed twice the time as the one before.

Next thing is the result type is `Either[String, String]` from _sttp_.
I would like the result to be a String when success, or an exception handled by ZIO if failure.
```
numbers <- sttp
        ...
        .flatMap {
          case Left(msg) =>
            IO.fail(ServiceException(msg))
          case Right(value) => ZIO.effectTotal(value)
        }
``` 
To be fair in the code above we encapsulated the code that accessed the REST-Service and the one that handles the numbers.
So in essence this would be the code left:
```
(for {
      numbers <- fetchNumbers("http://localhost:8088/5") // long polling of 5 seconds
      _ <- handleNumber(numbers)
    } yield ()).forever
```

Running the Client  is extending `zio.App` and implementing its `run` function:

```
object HttpClient extends zio.App {
  
  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    fetchNumbers
      .tapError { ex => console.putStrLn(s"There was an exception: ${ex.getMessage}")}
      .fold(_ => -1, _ => 0)
...
```
We log the error and finish the program either successful (0) of failing (-1).

## The Server
The server part just simulates this behaviour of providing Numbers at different times.

# Buildtool Mill

## Update dependencies in Intellij

    mill mill.scalalib.GenIdea/idea