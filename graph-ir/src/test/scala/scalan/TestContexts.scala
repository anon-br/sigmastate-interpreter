package scalan

import scalan.compilation.GraphVizExport
import scalan.reflection.RMethod
import scalan.util.FileUtil
import special.CoreLibReflection

trait TestContexts extends TestUtils {
  protected[this] def stage[Ctx <: Scalan](scalan: Ctx)(testName: String, name: String, sfs: Seq[() => scalan.Sym]): Unit = {
    val directory = FileUtil.file(prefix, testName)
    val gv = new GraphVizExport(scalan)
    implicit val graphVizConfig = gv.defaultGraphVizConfig
    try {
      val ss = sfs.map(_.apply()).asInstanceOf[Seq[gv.scalan.Sym]]
      gv.emitDepGraph(ss, directory, name)(graphVizConfig)
    } catch {
      case e: Exception =>
        val graphMsg = gv.emitExceptionGraph(e, directory, name) match {
          case Some(graphFile) =>
            s"See ${graphFile.file.getAbsolutePath} for exception graph."
          case None =>
            s"No exception graph produced."
        }
        fail(s"Staging $name failed. $graphMsg", e)
    }
  }

  trait TestContextApi { scalan: Scalan =>
    def invokeAll: Boolean
    def isInvokeEnabled(d: Def[_], m: RMethod): Boolean
    def shouldUnpack(e: Elem[_]): Boolean
    def testName: String
    def emitF(name: String, sfs: (() => Sym)*): Unit
    //    def emit(name: String, s1: => Sym): Unit = emitF(name, () => s1)
    def emit(name: String, ss: Sym*): Unit = {
      emitF(name, ss.map((s: Ref[_]) => () => s): _*)
    }
    def emit(s1: => Sym): Unit = emitF(testName, () => s1)
    def emit(s1: => Sym, s2: Sym*): Unit = {
      emitF(testName, Seq(() => s1) ++ s2.map((s: Ref[_]) => () => s): _*)
    }
  }
  abstract class TestContext(val testName: String) extends Scalan with TestContextApi {
    def this() = this(currentTestNameAsFileName)

    override val invokeAll = true
    override def isInvokeEnabled(d: Def[_], m: RMethod) = invokeAll
    override def shouldUnpack(e: Elem[_]) = true

    // workaround for non-existence of by-name repeated parameters
    def emitF(name: String, sfs: (() => Sym)*): Unit = stage(this)(testName, name, sfs)
  }


}

abstract class BaseCtxTests extends BaseTests with TestContexts {
  val reflection = (CoreLibReflection, GraphIRReflection)
}

abstract class BaseNestedCtxTests extends BaseNestedTests with TestContexts

abstract class BaseShouldCtxTests extends BaseShouldTests with TestContexts