/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js tools             **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2014, LAMP/EPFL   **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package scala.scalajs.tools.optimizer

import scala.annotation.tailrec

import scala.collection.mutable

import net.liftweb.json._

import scala.scalajs.tools.logging._
import scala.scalajs.tools.io._
import scala.scalajs.tools.classpath._
import OptData._

/** Scala.js optimizer: does type-aware global dce. */
class ScalaJSOptimizer {
  import ScalaJSOptimizer._

  private[this] var logger: Logger = _
  private[this] val encodedNameToClassfile =
    mutable.Map.empty[String, VirtualScalaJSClassfile]

  /** Applies Scala.js-specific optimizations to a sequence of .js files.
   *  See [[ScalaJSOptimizer.Inputs]] for details about the required and
   *  optional inputs.
   *  See [[ScalaJSOptimizer.OutputConfig]] for details about the configuration
   *  for the output of this method.
   *  Returns a [[ScalaJSOptimizer.Result]] containing the result of the
   *  optimizations. Its output file will contain, in that order:
   *  1. The Scala.js core lib,
   *  2. The result of dead code elimination applied to Scala.js class files,
   *  3. The custom .js files, in the same order as they were listed in inputs.
   */
  def optimize(inputs: Inputs, outputConfig: OutputConfig,
      logger: Logger): Unit = {
    this.logger = logger
    try {
      val analyzer = parseInfoFiles(inputs.classpath)
      analyzer.computeReachability()
      writeDCEedOutput(inputs, outputConfig, analyzer)
    } finally {
      this.logger = null
    }
  }

  private def parseInfoFiles(classpath: ScalaJSClasspath): Analyzer = {
    val coreData = classpath.coreInfoFiles.map(f => readData(f.content))
    val userData = classpath.classFiles map { classfile =>
      val data = readData(classfile.info)
      val encodedName = data.encodedName
      encodedNameToClassfile += encodedName -> classfile
      data
    }
    new Analyzer(logger, coreData ++ userData)
  }

  private def readData(infoFile: String): ClassInfoData = {
    implicit val formats = DefaultFormats
    Extraction.extract[ClassInfoData](JsonParser.parse(infoFile))
  }

  private def writeDCEedOutput(inputs: Inputs, outputConfig: OutputConfig,
      analyzer: Analyzer): Unit = {

    val writer = outputConfig.writer.contentWriter

    def pasteFile(f: VirtualFile): Unit =
      pasteLines(f.readLines())
    def pasteLines(lines: TraversableOnce[String]): Unit =
      lines foreach pasteLine
    def pasteLine(line: String): Unit = {
      writer.write(line)
      writer.write('\n')
    }

    pasteFile(inputs.classpath.coreJSLibFile)

    def compareClassInfo(lhs: analyzer.ClassInfo, rhs: analyzer.ClassInfo) = {
      if (lhs.ancestorCount != rhs.ancestorCount) lhs.ancestorCount < rhs.ancestorCount
      else lhs.encodedName.compareTo(rhs.encodedName) < 0
    }

    for {
      classInfo <- analyzer.classInfos.values.toSeq.sortWith(compareClassInfo)
      if classInfo.isNeededAtAll
      classfile <- encodedNameToClassfile.get(classInfo.encodedName)
    } {
      def pasteReachableMethods(methodLines: List[String], methodLinePrefix: String): Unit = {
        for (p <- methodChunks(methodLines, methodLinePrefix)) {
          val (optMethodName, methodLines) = p
          val isReachable = optMethodName.forall(
              classInfo.methodInfos(_).isReachable)
          if (isReachable)
            pasteLines(methodLines)
        }
      }

      val lines = classfile.readLines().filterNot(_.startsWith("//@"))
      if (classInfo.isImplClass) {
        pasteReachableMethods(lines, "ScalaJS.impls")
      } else if (!classInfo.hasInstantiation) {
        // there is only the data anyway
        pasteLines(lines)
      } else {
        val className = classInfo.encodedName
        val (implementation, afterImpl) = lines.span(!_.startsWith("ScalaJS.is."))
        val (classData, setClassData :: moduleAccessor) = afterImpl.span(!_.startsWith("ScalaJS.c."))

        if (classInfo.isAnySubclassInstantiated) {
          // constructor
          val (constructorLines0, constructorLine1 :: afterConstructor) =
            implementation.span(!_.startsWith(s"ScalaJS.c.$className.prototype.constructor ="))
          pasteLines(constructorLines0)
          pasteLine(constructorLine1)

          // methods
          val (methodLines, afterMethods) = afterConstructor.span(_ != "/** @constructor */")
          pasteReachableMethods(methodLines, s"ScalaJS.c.$className.prototype")

          // inheritable constructor
          pasteLines(afterMethods)
        }

        if (classInfo.isDataAccessed)
          pasteLines(classData)
        if (classInfo.isAnySubclassInstantiated)
          pasteLines(setClassData :: Nil)
        if (classInfo.isModuleAccessed)
          pasteLines(moduleAccessor)
      }
    }

    for (file <- inputs.customScripts)
      pasteFile(file)
  }

  private def methodChunks(methodLines: List[String],
      methodLinePrefix: String): JustAForeach[(Option[String], List[String])] = {
    new JustAForeach[(Option[String], List[String])] {
      private[this] val prefixLength = methodLinePrefix.length

      override def foreach[U](f: ((Option[String], List[String])) => U): Unit = {
        @tailrec
        def loop(remainingLines: List[String]): Unit = {
          if (remainingLines.nonEmpty) {
            val firstLine = remainingLines.head
            val (methodLines, nextLines) = remainingLines.tail.span(!_.startsWith(methodLinePrefix))
            val encodedName = if (firstLine(prefixLength) == '.') {
              val name = firstLine.substring(prefixLength+1).takeWhile(_ != ' ')
              val unquoted = parse('"'+name+'"').asInstanceOf[JString].s // see #330
              Some(unquoted)
            } else {
              None // this is an exported method with []-select
            }
            f((encodedName, firstLine :: methodLines))
            loop(nextLines)
          }
        }
        loop(methodLines)
      }
    }
  }
}

object ScalaJSOptimizer {
  /** Inputs of the Scala.js optimizer. */
  final case class Inputs(
      /** The Scala.js classpath entries. */
      classpath: ScalaJSClasspath,
      /** Additional scripts to be appended in the output. */
      customScripts: Seq[VirtualJSFile] = Nil
  )

  object Inputs {
    @deprecated("Use the primary constructor/apply method", "0.4.2")
    def apply(coreJSLib: VirtualJSFile, coreInfoFiles: Seq[VirtualFile],
        scalaJSClassfiles: Seq[VirtualScalaJSClassfile],
        customScripts: Seq[VirtualJSFile]): Inputs = {
      apply(
          ScalaJSClasspath(coreJSLib, coreInfoFiles, scalaJSClassfiles),
          customScripts)
    }

    @deprecated("Use the primary constructor/apply method", "0.4.2")
    def apply(coreJSLib: VirtualJSFile, coreInfoFiles: Seq[VirtualFile],
        scalaJSClassfiles: Seq[VirtualScalaJSClassfile]): Inputs = {
      apply(
          ScalaJSClasspath(coreJSLib, coreInfoFiles, scalaJSClassfiles))
    }
  }

  /** Configuration for the output of the Scala.js optimizer. */
  final case class OutputConfig(
      /** Name of the output file. (used to refer to sourcemaps) */
      name: String,
      /** Writer for the output. */
      writer: VirtualJSFileWriter,
      /** Ask to produce source map for the output (currently ignored). */
      wantSourceMap: Boolean = false
  )

  private trait JustAForeach[A] {
    def foreach[U](f: A => U): Unit
  }
}
