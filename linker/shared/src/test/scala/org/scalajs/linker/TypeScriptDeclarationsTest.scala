/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.linker

import java.nio.charset.StandardCharsets

import scala.concurrent.{ExecutionContext, Future}

import org.junit.Test
import org.junit.Assert._

import org.scalajs.ir.ClassKind
import org.scalajs.ir.Names._
import org.scalajs.ir.Trees._
import org.scalajs.ir.Types._
import org.scalajs.ir.WellKnownNames._

import org.scalajs.junit.async._

import org.scalajs.linker.interface._
import org.scalajs.linker.testutils._
import org.scalajs.linker.testutils.TestIRBuilder._

class TypeScriptDeclarationsTest {
  import scala.concurrent.ExecutionContext.Implicits.global

  import TypeScriptDeclarationsTest._

  /** A top-level exported method recovers the return type of its underlying
   *  (statically typed) method: `Int` becomes `number`.
   */
  @Test
  def exportsTypedFunction(): AsyncResult = await {
    val classDefs = List(
      classDef(TestClass,
        superClass = Some(ObjectClass),
        methods = List(
          trivialCtor(TestClass),
          MethodDef(SMF, m("answer", Nil, I), NON, Nil, IntType, Some(int(42)))(
              EOH.withNoinline(true), UNV)
        ),
        topLevelExportDefs = List(
          TopLevelMethodExportDef("main",
            JSMethodDef(SMF, str("answer"), Nil, None,
              ApplyStatic(EAF, TestClass, m("answer", Nil, I), Nil)(IntType))(
              EOH, UNV))
        )
      )
    )

    linkDTS(classDefs).map { dts =>
      assertTrue("no .d.ts emitted", dts.isDefined)
      assertTrue(dts.get, dts.get.contains("export function answer(): number;"))
    }
  }

  /** `@JSExportTopLevel` on a plain Scala class (lowered to a constructor-
   *  function export) is rendered as a TS `class`, with the constructor and the
   *  class's `@JSExport`ed members, recovering real member types.
   */
  @Test
  def exportsConstructorClass(): AsyncResult = await {
    val classDefs = List(
      classDef(TestClass,
        superClass = Some(ObjectClass),
        methods = List(
          trivialCtor(TestClass),
          // Underlying instance method whose type is recovered for the export.
          MethodDef(EMF, m("greet", Nil, I), NON, Nil, IntType, Some(int(7)))(
              EOH.withNoinline(true), UNV)
        ),
        jsMethodProps = List(
          // Exported instance member forwarding to `greet`.
          JSMethodDef(EMF, str("greet"), Nil, None,
            Apply(EAF, This()(ClassType(TestClass, nullable = false, exact = false)),
                m("greet", Nil, I), Nil)(IntType))(
              EOH, UNV)
        ),
        topLevelExportDefs = List(
          // Constructor-function export: `new Test()`.
          TopLevelMethodExportDef("main",
            JSMethodDef(SMF, str("Test"), Nil, None,
              New(TestClass, NoArgConstructorName, Nil))(
              EOH, UNV))
        )
      )
    )

    linkDTS(classDefs).map { dts =>
      assertTrue("no .d.ts emitted", dts.isDefined)
      val content = dts.get
      assertTrue(content, content.contains("export class Test {"))
      assertTrue(content, content.contains("constructor();"))
      assertTrue(content, content.contains("greet(): number;"))
    }
  }

  /** A top-level exported field is rendered as a `let` binding.
   *
   *  Top-level exported fields are required by the IR to have type `any` (the
   *  compiler boxes them at the export boundary), so the declared type is `any`.
   */
  @Test
  def exportsField(): AsyncResult = await {
    // Top-level field exports live on a static field of a module class.
    val fieldFlags = EMF.withNamespace(MemberNamespace.PublicStatic)

    val classDefs = List(
      classDef(TestClass,
        kind = ClassKind.ModuleClass,
        superClass = Some(ObjectClass),
        fields = List(
          FieldDef(fieldFlags, FieldName(TestClass, "count"), NON, AnyType)
        ),
        methods = List(trivialCtor(TestClass, forModuleClass = true)),
        topLevelExportDefs = List(
          TopLevelFieldExportDef("main", "count", FieldName(TestClass, "count"))
        )
      )
    )

    linkDTS(classDefs).map { dts =>
      assertTrue("no .d.ts emitted", dts.isDefined)
      assertTrue(dts.get, dts.get.contains("export let count: any;"))
    }
  }

  /** No `.d.ts` is written when the feature is disabled (default). */
  @Test
  def noDeclarationsWhenDisabled(): AsyncResult = await {
    val classDefs = List(
      classDef(TestClass,
        superClass = Some(ObjectClass),
        methods = List(
          trivialCtor(TestClass),
          MethodDef(SMF, m("answer", Nil, I), NON, Nil, IntType, Some(int(42)))(
              EOH.withNoinline(true), UNV)
        ),
        topLevelExportDefs = List(
          TopLevelMethodExportDef("main",
            JSMethodDef(SMF, str("answer"), Nil, None,
              ApplyStatic(EAF, TestClass, m("answer", Nil, I), Nil)(IntType))(
              EOH, UNV))
        )
      )
    )

    linkDTS(classDefs, outputDeclarations = false).map { dts =>
      assertTrue("unexpected .d.ts emitted", dts.isEmpty)
    }
  }

  /** The set of names exported by the `.d.ts` matches the set exported by the
   *  `.js` (a function export and a constructor-class export).
   */
  @Test
  def declarationsAreConsistentWithJS(): AsyncResult = await {
    val classDefs = List(
      classDef(TestClass,
        superClass = Some(ObjectClass),
        methods = List(
          trivialCtor(TestClass),
          MethodDef(SMF, m("answer", Nil, I), NON, Nil, IntType, Some(int(42)))(
              EOH.withNoinline(true), UNV)
        ),
        topLevelExportDefs = List(
          TopLevelMethodExportDef("main",
            JSMethodDef(SMF, str("answer"), Nil, None,
              ApplyStatic(EAF, TestClass, m("answer", Nil, I), Nil)(IntType))(
              EOH, UNV)),
          TopLevelMethodExportDef("main",
            JSMethodDef(SMF, str("Make"), Nil, None,
              New(TestClass, NoArgConstructorName, Nil))(
              EOH, UNV))
        )
      )
    )

    link(classDefs, config()).map { output =>
      val js = jsContent(output)
      val dts = dtsContent(output).getOrElse(throw new AssertionError("no .d.ts emitted"))
      assertEquals(Set("answer", "Make"), jsExportNames(js))
      assertEquals("exported names in .d.ts must match those in .js",
          jsExportNames(js), dtsExportNames(dts))
    }
  }

  /** For a `NoModule` output (globals, no ES exports) the `.d.ts` uses ambient
   *  `declare` declarations rather than `export`, matching the `.js`.
   */
  @Test
  def globalDeclarationsForNoModule(): AsyncResult = await {
    val classDefs = List(
      classDef(TestClass,
        superClass = Some(ObjectClass),
        methods = List(
          trivialCtor(TestClass),
          MethodDef(SMF, m("answer", Nil, I), NON, Nil, IntType, Some(int(42)))(
              EOH.withNoinline(true), UNV)
        ),
        topLevelExportDefs = List(
          TopLevelMethodExportDef("main",
            JSMethodDef(SMF, str("answer"), Nil, None,
              ApplyStatic(EAF, TestClass, m("answer", Nil, I), Nil)(IntType))(
              EOH, UNV))
        )
      )
    )

    link(classDefs, config(moduleKind = ModuleKind.NoModule)).map { output =>
      val dts = dtsContent(output).getOrElse(throw new AssertionError("no .d.ts emitted"))
      assertTrue(dts, dts.contains("declare function answer(): number;"))
      assertFalse(dts, dts.contains("export "))
    }
  }

  /** A reference to a class exported from the *same* module resolves to its
   *  name, with no import.
   */
  @Test
  def resolvesSameModuleClassReference(): AsyncResult = await {
    val thisType = ClassType(TestClass, nullable = false, exact = false)
    // A class-typed method result is nullable in the IR (its name carries no
    // nullability), so it renders as `Test | null`.
    val resultType = ClassType(TestClass, nullable = true, exact = false)

    val classDefs = List(
      classDef(TestClass,
        superClass = Some(ObjectClass),
        methods = List(
          trivialCtor(TestClass),
          MethodDef(EMF, m("self", Nil, ClassRef(TestClass)), NON, Nil, resultType,
              Some(This()(thisType)))(EOH.withNoinline(true), UNV)
        ),
        jsMethodProps = List(
          JSMethodDef(EMF, str("self"), Nil, None,
            Apply(EAF, This()(thisType), m("self", Nil, ClassRef(TestClass)), Nil)(resultType))(
              EOH, UNV)
        ),
        topLevelExportDefs = List(
          TopLevelMethodExportDef("main",
            JSMethodDef(SMF, str("Test"), Nil, None,
              New(TestClass, NoArgConstructorName, Nil))(EOH, UNV))
        )
      )
    )

    linkDTS(classDefs).map { dts0 =>
      val dts = dts0.getOrElse(throw new AssertionError("no .d.ts emitted"))
      assertTrue(dts, dts.contains("self(): Test | null;"))
      assertFalse("must not import a same-module class", dts.contains("import"))
    }
  }

  /** A reference to a class exported from *another* module resolves to its name
   *  and emits an `import type` from that module.
   */
  @Test
  def resolvesCrossModuleClassReference(): AsyncResult = await {
    val LibClass = ClassName("Lib")
    val AppClass = ClassName("App")
    // Class-typed method result is nullable in the IR (see above).
    val libType = ClassType(LibClass, nullable = true, exact = false)

    val classDefs = List(
      // Module "lib": exports class `Lib`.
      classDef(LibClass,
        superClass = Some(ObjectClass),
        methods = List(trivialCtor(LibClass)),
        topLevelExportDefs = List(
          TopLevelMethodExportDef("lib",
            JSMethodDef(SMF, str("Lib"), Nil, None,
              New(LibClass, NoArgConstructorName, Nil))(EOH, UNV))
        )
      ),
      // Module "app": exports `makeLib(): Lib`.
      classDef(AppClass,
        superClass = Some(ObjectClass),
        methods = List(
          trivialCtor(AppClass),
          MethodDef(SMF, m("makeLib", Nil, ClassRef(LibClass)), NON, Nil, libType,
              Some(New(LibClass, NoArgConstructorName, Nil)))(EOH.withNoinline(true), UNV)
        ),
        topLevelExportDefs = List(
          TopLevelMethodExportDef("app",
            JSMethodDef(SMF, str("makeLib"), Nil, None,
              ApplyStatic(EAF, AppClass, m("makeLib", Nil, ClassRef(LibClass)), Nil)(libType))(
              EOH, UNV))
        )
      )
    )

    link(classDefs, config()).map { output =>
      val appDts = stringContent(output, "app.d.ts")
      assertTrue(appDts, appDts.contains("""import type { Lib } from "./lib.js";"""))
      assertTrue(appDts, appDts.contains("export function makeLib(): Lib | null;"))
    }
  }
}

object TypeScriptDeclarationsTest {
  private val TestClass = ClassName("Test")

  /** Static-method member flags. */
  private val SMF = EMF.withNamespace(MemberNamespace.PublicStatic)

  /** Base config; the optimizer is disabled so that exported forwarders are not
   *  inlined away, which keeps the underlying typed members available for type
   *  recovery.
   */
  private def config(moduleKind: ModuleKind = ModuleKind.ESModule,
      outputDeclarations: Boolean = true): StandardConfig = {
    StandardConfig()
      .withModuleKind(moduleKind)
      .withSourceMap(false)
      .withOptimizer(false)
      .withOutputDeclarations(outputDeclarations)
  }

  /** Links the given classDefs and returns the output directory. */
  private def link(classDefs: List[ClassDef], config: StandardConfig)(
      implicit ec: ExecutionContext): Future[MemOutputDirectory] = {
    val output = MemOutputDirectory()
    LinkingUtils.testLink(classDefs, Nil, config = config, output = output).map(_ => output)
  }

  private def stringContent(output: MemOutputDirectory, name: String): String =
    new String(output.content(name).get, StandardCharsets.UTF_8)

  /** The single generated `.d.ts`, if any (there is one per public module). */
  private def dtsContent(output: MemOutputDirectory): Option[String] =
    output.fileNames().find(_.endsWith(".d.ts")).map(stringContent(output, _))

  /** The single generated `.js` (excluding the source map). */
  private def jsContent(output: MemOutputDirectory): String = {
    val name = output.fileNames().find(n => n.endsWith(".js")).get
    stringContent(output, name)
  }

  /** Links and returns the content of the generated `.d.ts`, if any. */
  private def linkDTS(classDefs: List[ClassDef], outputDeclarations: Boolean = true)(
      implicit ec: ExecutionContext): Future[Option[String]] =
    link(classDefs, config(outputDeclarations = outputDeclarations)).map(dtsContent)

  /** Extracts the exported names from a generated `.js` module (from its
   *  `export { local as Name, ... }` clauses).
   */
  private def jsExportNames(js: String): Set[String] = {
    raw"export\s*\{([^}]*)\}".r.findAllMatchIn(js).flatMap { m =>
      m.group(1).split(",").iterator.map(_.trim).filter(_.nonEmpty).map { item =>
        item.split("\\s+as\\s+").last.trim
      }
    }.toSet
  }

  /** Extracts the exported names from a generated `.d.ts` (its top-level
   *  `export`/`declare` declarations).
   */
  private def dtsExportNames(dts: String): Set[String] = {
    raw"(?m)^(?:export|declare)\s+(?:class|function|let|const)\s+(\w+)".r
      .findAllMatchIn(dts).map(_.group(1)).toSet
  }
}
