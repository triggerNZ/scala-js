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
}

object TypeScriptDeclarationsTest {
  private val TestClass = ClassName("Test")

  /** Static-method member flags. */
  private val SMF = EMF.withNamespace(MemberNamespace.PublicStatic)

  /** Links the given classDefs and returns the content of `main.d.ts`, if any.
   *
   *  The optimizer is disabled so that exported forwarders are not inlined away,
   *  which keeps the underlying typed members available for type recovery.
   */
  private def linkDTS(classDefs: List[ClassDef], outputDeclarations: Boolean = true)(
      implicit ec: ExecutionContext): Future[Option[String]] = {
    val config = StandardConfig()
      .withModuleKind(ModuleKind.ESModule)
      .withSourceMap(false)
      .withOptimizer(false)
      .withOutputDeclarations(outputDeclarations)

    val output = MemOutputDirectory()
    LinkingUtils.testLink(classDefs, Nil, config = config, output = output).map { _ =>
      output.content("main.d.ts").map(bytes => new String(bytes, StandardCharsets.UTF_8))
    }
  }
}
