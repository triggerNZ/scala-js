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

package org.scalajs.linker.backend

import scala.concurrent._

import java.io._
import java.nio.ByteBuffer

import org.scalajs.linker.interface.{OutputDirectory, Report}
import org.scalajs.linker.interface.unstable.{OutputDirectoryImpl, OutputPatternsImpl, ReportImpl}
import org.scalajs.linker.standard.{ModuleSet, IOThrottler}
import org.scalajs.linker.standard.ModuleSet.ModuleID

import org.scalajs.linker.backend.javascript.ByteArrayWriter

private[backend] abstract class OutputWriter(output: OutputDirectory,
    config: LinkerBackendImpl.Config, skipContentCheck: Boolean) {

  private val outputImpl = OutputDirectoryImpl.fromOutputDirectory(output)
  private val moduleKind = config.commonConfig.coreSpec.moduleKind

  protected def writeModuleWithoutSourceMap(moduleID: ModuleID, force: Boolean): Option[ByteBuffer]

  protected def writeModuleWithSourceMap(moduleID: ModuleID, force: Boolean): Option[(ByteBuffer,
      ByteBuffer)]

  /** Produces the TypeScript declaration file (`.d.ts`) content for a module.
   *
   *  Returns `None` when declaration output is disabled (the default). Backends
   *  that support it override this method.
   */
  protected def genModuleDeclarations(moduleID: ModuleID): Option[ByteBuffer] = None

  def write(moduleSet: ModuleSet)(implicit ec: ExecutionContext): Future[Report] = {
    val ioThrottler = new IOThrottler(config.maxConcurrentWrites)

    def filesToRemove(seen: Set[String], reports: List[Report.Module],
        extraFiles: Set[String]): Set[String] = {
      seen -- reports.flatMap(r => r.jsFileName :: r.sourceMapName.toList) -- extraFiles
    }

    for {
      currentFilesList <- outputImpl.listFiles()
      currentFiles = currentFilesList.toSet
      results <- Future.traverse(moduleSet.modules) { m =>
        ioThrottler.throttle(writeModule(m.id, currentFiles))
      }
      reports = results.map(_._1)
      extraFiles = results.flatMap(_._2).toSet
      _ <- Future.traverse(filesToRemove(currentFiles, reports, extraFiles)) { f =>
        ioThrottler.throttle(outputImpl.delete(f))
      }
    } yield {
      val publicModules = for {
        (module, report) <- moduleSet.modules.zip(reports)
        if module.public
      } yield {
        report
      }

      new ReportImpl(publicModules)
    }
  }

  /** Writes the files for one module.
   *
   *  Returns the module's report and the list of additional files (beyond the
   *  `.js` and source map) that were written and must be preserved from cleanup
   *  (currently the `.d.ts` file, when enabled).
   */
  private def writeModule(moduleID: ModuleID, existingFiles: Set[String])(
      implicit ec: ExecutionContext): Future[(Report.Module, List[String])] = {
    val jsFileName = OutputPatternsImpl.jsFile(config.outputPatterns, moduleID.id)

    val reportFuture: Future[Report.Module] = if (config.sourceMap) {
      val sourceMapFileName = OutputPatternsImpl.sourceMapFile(config.outputPatterns, moduleID.id)
      val report =
        new ReportImpl.ModuleImpl(moduleID.id, jsFileName, Some(sourceMapFileName), moduleKind)
      val force = !existingFiles.contains(jsFileName) || !existingFiles.contains(sourceMapFileName)

      writeModuleWithSourceMap(moduleID, force) match {
        case Some((code, sourceMap)) =>
          for {
            _ <- outputImpl.writeFull(jsFileName, code, skipContentCheck)
            _ <- outputImpl.writeFull(sourceMapFileName, sourceMap, skipContentCheck)
          } yield {
            report
          }
        case None =>
          Future.successful(report)
      }
    } else {
      val report = new ReportImpl.ModuleImpl(moduleID.id, jsFileName, None, moduleKind)
      val force = !existingFiles.contains(jsFileName)

      writeModuleWithoutSourceMap(moduleID, force) match {
        case Some(code) =>
          for {
            _ <- outputImpl.writeFull(jsFileName, code, skipContentCheck)
          } yield {
            report
          }
        case None =>
          Future.successful(report)
      }
    }

    if (config.outputDeclarations) {
      val dtsFileName = OutputPatternsImpl.dtsFile(config.outputPatterns, moduleID.id)
      genModuleDeclarations(moduleID) match {
        case Some(dts) =>
          for {
            report <- reportFuture
            _ <- outputImpl.writeFull(dtsFileName, dts, skipContentCheck)
          } yield {
            (report, dtsFileName :: Nil)
          }
        case None =>
          // Keep any previously written .d.ts around.
          reportFuture.map((_, dtsFileName :: Nil))
      }
    } else {
      reportFuture.map((_, Nil))
    }
  }
}
