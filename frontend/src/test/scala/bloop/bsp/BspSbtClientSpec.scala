package bloop.bsp

import bloop.util.{TestUtil, TestProject}
import bloop.cli.BspProtocol
import bloop.logging.RecordingLogger
import bloop.cli.ExitStatus
import monix.eval.Task
import bloop.engine.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import scala.util.Random

object LocalBspSbtClientSpec extends BspSbtClientSpec(BspProtocol.Local)
object TcpBspSbtClientSpec extends BspSbtClientSpec(BspProtocol.Tcp)

class BspSbtClientSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  test("compilations with same origin id are cached if used from sbt") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Foo.scala` =
          """/Foo.scala
            |class Foo
          """.stripMargin
        val `Bar.scala` =
          """/Bar.scala
            |class Bar extends Foo
          """.stripMargin
        val `Baz.scala` =
          """/Baz.scala
            |class Baz extends Bar
          """.stripMargin
        val `Zaz.scala` =
          """/Zaz.scala
            |class Zaz extends Baz
          """.stripMargin
        val `Zut.scala` =
          """/Zut.scala
            |class Zut extends Zaz
          """.stripMargin

        val `FooHelper.scala` =
          """/FooHelper.scala
            |object FooHelper {
            |  def forceChange: Boolean = true
            |}
          """.stripMargin

        val `BazHelper.scala` =
          """/BazHelper.scala
            |object BazHelper {
            |  def forceChange2: Boolean = true
            |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Foo.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`Bar.scala`), List(`A`))
      val `C` = TestProject(workspace, "c", List(Sources.`Baz.scala`), List(`B`))
      val `D` = TestProject(workspace, "d", List(Sources.`Zaz.scala`), List(`C`))
      val `E` = TestProject(workspace, "e", List(Sources.`Zut.scala`), List(`D`))
      val projects = List(`A`, `B`, `C`, `D`, `E`)

      loadBspStateAsSbtClient(workspace, projects, logger) { state =>
        val firstOriginId = "32131"
        val compiledState = compileProjectsOutOfOrderWith(state, projects, firstOriginId)

        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertNoDiff(
          s"""# task start 1
             |  -> Msg: Compiling a (1 Scala source)
             |  -> Data kind: compile-task
             |# task finish 1
             |  -> errors 0, warnings 0
             |  -> origin = $firstOriginId
             |  -> Msg: Compiled 'a'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(compiledState, `A`)
        )

        assertNoDiff(
          s"""# task start 2
             |  -> Msg: Compiling b (1 Scala source)
             |  -> Data kind: compile-task
             |# task finish 2
             |  -> errors 0, warnings 0
             |  -> origin = $firstOriginId
             |  -> Msg: Compiled 'b'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(compiledState, `B`)
        )

        assertNoDiff(
          s"""# task start 3
             |  -> Msg: Compiling c (1 Scala source)
             |  -> Data kind: compile-task
             |# task finish 3
             |  -> errors 0, warnings 0
             |  -> origin = $firstOriginId
             |  -> Msg: Compiled 'c'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(compiledState, `C`)
        )

        assertNoDiff(
          s"""# task start 4
             |  -> Msg: Compiling d (1 Scala source)
             |  -> Data kind: compile-task
             |# task finish 4
             |  -> errors 0, warnings 0
             |  -> origin = $firstOriginId
             |  -> Msg: Compiled 'd'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(compiledState, `D`)
        )

        assertNoDiff(
          s"""# task start 5
             |  -> Msg: Compiling e (1 Scala source)
             |  -> Data kind: compile-task
             |# task finish 5
             |  -> errors 0, warnings 0
             |  -> origin = $firstOriginId
             |  -> Msg: Compiled 'e'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(compiledState, `E`)
        )

        // Make a repeated compilation for `E` and guarantee same invariants
        val repeatedCompiledState =
          compiledState.compile(`E`, originId = Some(firstOriginId), clearDiagnostics = true)

        assertExitStatus(repeatedCompiledState, ExitStatus.Ok)
        assertValidCompilationState(repeatedCompiledState, projects)
        assertNoDiff("", diagnosticsWithoutTaskIds(repeatedCompiledState, `E`))

        // Now, let's clear diagnostics, compile with another origin id and see new compilations
        repeatedCompiledState.diagnostics.clear()
        writeFile(`A`.srcFor("FooHelper.scala", exists = false), Sources.`FooHelper.scala`)
        writeFile(`C`.srcFor("BazHelper.scala", exists = false), Sources.`BazHelper.scala`)

        val initialStateBackup = repeatedCompiledState.backup
        val secondOriginId = "15544"
        val newCompiledState = repeatedCompiledState.compile(
          `E`,
          originId = Some(secondOriginId),
          clearDiagnostics = true
        )

        assertExitStatus(newCompiledState, ExitStatus.Ok)
        assertValidCompilationState(newCompiledState, projects)

        assertNoDiff(
          s"""# task start 6
             |  -> Msg: Compiling a (1 Scala source)
             |  -> Data kind: compile-task
             |# task finish 6
             |  -> errors 0, warnings 0
             |  -> origin = $secondOriginId
             |  -> Msg: Compiled 'a'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(newCompiledState, `A`)
        )

        assertNoDiff(
          s"""# task start 7
             |  -> Msg: Start no-op compilation for b
             |  -> Data kind: compile-task
             |# task finish 7
             |  -> errors 0, warnings 0
             |  -> origin = $secondOriginId
             |  -> Msg: Compiled 'b'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(newCompiledState, `B`)
        )

        assertNoDiff(
          s"""# task start 8
             |  -> Msg: Compiling c (1 Scala source)
             |  -> Data kind: compile-task
             |# task finish 8
             |  -> errors 0, warnings 0
             |  -> origin = $secondOriginId
             |  -> Msg: Compiled 'c'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(newCompiledState, `C`)
        )

        assertNoDiff(
          s"""# task start 9
             |  -> Msg: Start no-op compilation for d
             |  -> Data kind: compile-task
             |# task finish 9
             |  -> errors 0, warnings 0
             |  -> origin = $secondOriginId
             |  -> Msg: Compiled 'd'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(newCompiledState, `D`)
        )

        assertNoDiff(
          s"""# task start 10
             |  -> Msg: Start no-op compilation for e
             |  -> Data kind: compile-task
             |# task finish 10
             |  -> errors 0, warnings 0
             |  -> origin = $secondOriginId
             |  -> Msg: Compiled 'e'
             |  -> Data kind: compile-report""".stripMargin,
          diagnosticsWithoutTaskIds(newCompiledState, `E`)
        )

        assertSameExternalClassesDirs(newCompiledState, initialStateBackup, List(`B`, `D`, `E`))
        assertDifferentExternalClassesDirs(newCompiledState, initialStateBackup, List(`A`, `C`))
      }
    }
  }

  test("compilations with same origin id are cached even if they are deduplicated") {
    //val i: Int = ""
    ()
  }

  val random = new scala.util.Random()
  private def compileProjectsOutOfOrderWith(
      state: ManagedBspTestState,
      projects: List[TestProject],
      originId: String
  ): ManagedBspTestState = {
    val allCompilationTasks = projects.map(
      project => state.compileTask(project, originId = Some(originId), clearDiagnostics = false)
    )

    val duration = new FiniteDuration(20, TimeUnit.SECONDS)
    val allCompiledStatesTask = Task.gatherUnordered(random.shuffle(allCompilationTasks))
    val compiledStates =
      TestUtil.await(duration, ExecutionContext.ioScheduler)(allCompiledStatesTask)

    var compiledStateForE = compiledStates.head
    compiledStates.foreach { compiledState =>
      val isComplete = compiledState.results.all.forall(_._2 != bloop.Compiler.Result.Empty)
      if (isComplete) {
        compiledStateForE = compiledState
      }
    }
    compiledStateForE
  }

  private def diagnosticsWithoutTaskIds(
      state: ManagedBspTestState,
      project: TestProject
  ): String = {
    state.lastDiagnostics(project).replaceAll("\\d+:", "")
  }
}