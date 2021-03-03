package sigmastate.interpreter

import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

import com.google.common.cache.{CacheBuilder, RemovalNotification, RemovalListener, LoadingCache, CacheLoader, CacheStats}
import org.ergoplatform.settings.ErgoAlgos
import org.ergoplatform.validation.{ValidationRules, SigmaValidationSettings}
import org.ergoplatform.validation.ValidationRules.{CheckCostFunc, CheckCalcFunc, trySoftForkable}
import scalan.{AVHashMap, Nullable}
import sigmastate.{Values, TrivialProp}
import sigmastate.Values.ErgoTree
import sigmastate.eval.{RuntimeIRContext, IRContext, CompiletimeIRContext}
import sigmastate.interpreter.Interpreter.ReductionResult
import sigmastate.serialization.ErgoTreeSerializer
import sigmastate.utils.Helpers._

import scala.collection.mutable

/** A reducer which represents precompiled script reduction function.
  * The function takes script execution context and produces the [[ReductionResult]],
  * which contains both sigma proposition and the approximation of the cost taken by the
  * reduction.
  */
trait ScriptReducer {
  /** Reduce this pre-compiled script in the given context.
    * This is equivalent to reduceToCrypto, except that graph construction is
    * completely avoided.
    */
  def reduce(context: InterpreterContext): ReductionResult
}

/** Used as a fallback reducer when precompilation failed due to soft-fork condition. */
case object WhenSoftForkReducer extends ScriptReducer {
  override def reduce(context: InterpreterContext): (Values.SigmaBoolean, Long) = {
    TrivialProp.TrueProp -> 0
  }
}

/** This class implements optimized reduction of the given pre-compiled script.
  * Pre-compilation of the necessary graphs is performed as part of constructor and
  * the graphs are stored in the given IR instance.
  *
  * The code make the following assumptions:
  * 1) the given script doesn't contain both [[sigmastate.utxo.DeserializeContext]] and
  * [[sigmastate.utxo.DeserializeRegister]]
  *
  * The code should correspond to reduceToCrypto method, but some operations may be
  * optimized due to assumptions above.
  */
case class PrecompiledScriptReducer(scriptBytes: Seq[Byte])(implicit val IR: IRContext)
  extends ScriptReducer {

  /** The following operations create [[RCostingResultEx]] structure for the given
    * `scriptBytes` and they should be the same as in `reduceToCrypto` method.
    * This can be viewed as ahead of time pre-compilation of the cost and calc graphs
    * which are reused over many invocations of the `reduce` method.
    */
  val costingRes = {
    val tree = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(scriptBytes.toArray)
    val prop = tree.toProposition(tree.isConstantSegregation)
    val validProp = Interpreter.toValidScriptType(prop)
    val res = IR.doCostingEx(Interpreter.emptyEnv, validProp, true)
    val costF = res.costF
    CheckCostFunc(IR)(IR.asRep[Any => Int](costF))
    val calcF = res.calcF
    CheckCalcFunc(IR)(calcF)
    res
  }

  /** Reduce this pre-compiled script in the given context.
    * This is equivalent to reduceToCrypto, except that graph construction is
    * completely avoided.
    */
  def reduce(context: InterpreterContext): ReductionResult = {
    import IR._
    implicit val vs = context.validationSettings
    val maxCost = context.costLimit
    val initCost = context.initCost
    trySoftForkable[ReductionResult](whenSoftFork = TrivialProp.TrueProp -> 0) {
      val costF = costingRes.costF
      val costingCtx = context.toSigmaContext(isCost = true)
      val estimatedCost = IR.checkCostWithContext(costingCtx, costF, maxCost, initCost).getOrThrow

      // check calc
      val calcF = costingRes.calcF
      val calcCtx = context.toSigmaContext(isCost = false)
      val res = Interpreter.calcResult(IR)(calcCtx, calcF)
      SigmaDsl.toSigmaBoolean(res) -> estimatedCost
    }
  }
}

/** Represents keys in the cache of precompiled ErgoTrees for repeated evaluation.
  * Note, [[SigmaValidationSettings]] are part of the key, which is important, because
  * the output of compilation depends on validation settings.
  *
  * @param ergoTreeBytes serialized bytes of ErgoTree instance (returned by ErgoTreeSerializer)
  * @param vs validation settings which where used for soft-forkable compilation.
  */
case class CacheKey(ergoTreeBytes: Seq[Byte], vs: SigmaValidationSettings)

/** Settings to configure script processor.
  * @param predefScripts collection of scripts to ALWAYS pre-compile (each given by ErgoTree bytes)
  * @param maxCacheSize  maximum number of entries in the cache
  * @param recordCacheStats if true, then cache statistics is recorded
  * @param reportingInterval number of cache load operations between two reporting events
  */
case class ScriptProcessorSettings(
  predefScripts: Seq[CacheKey],
  maxCacheSize: Int = 1000,
  recordCacheStats: Boolean = false,
  reportingInterval: Int = 100
)

/** Script processor which holds pre-compiled reducers for the given scripts.
  * This class is thread-safe.
  */
class PrecompiledScriptProcessor(val settings: ScriptProcessorSettings) {

  /** Creates a new instance of IRContex to be used in reducers.
    * The default implementation can be overriden in derived classes.
    */
  protected def createIR(): IRContext = new RuntimeIRContext

  /** Holds for each ErgoTree bytes the corresponding pre-compiled reducer. */
  protected val predefReducers = {
    implicit val IR: IRContext = createIR()
    val predefScripts = settings.predefScripts
    val res = AVHashMap[CacheKey, ScriptReducer](predefScripts.length)
    predefScripts.foreach { s =>
      val r = PrecompiledScriptReducer(s.ergoTreeBytes)
      val old = res.put(s, r)
      require(old == null, s"duplicate predefined script: '${ErgoAlgos.encode(s.ergoTreeBytes.toArray)}'")
    }
    res
  }

  protected def onEvictedCacheEntry(key: CacheKey): Unit = {
  }

  /** Called when a cache item is removed. */
  protected val cacheListener = new RemovalListener[CacheKey, ScriptReducer]() {
    override def onRemoval(notification: RemovalNotification[CacheKey, ScriptReducer]): Unit = {
      if (notification.wasEvicted()) {
        onEvictedCacheEntry(notification.getKey)
      }
    }
  }

  protected def onReportStats(stats: CacheStats) = {
  }

  /** Loader to be used on a cache miss. The loader creates a new [[ScriptReducer]] for
    * the given [[CacheKey]]. The default loader creates an instance of
    * [[PrecompiledScriptReducer]] which stores its own IRContext and compiles costF,
    * calcF graphs. */
  protected val cacheLoader = new CacheLoader[CacheKey, ScriptReducer]() {
    /** Internal counter of all load operations happening an different threads. */
    private val loadCounder = new AtomicInteger(1)

    override def load(key: CacheKey): ScriptReducer = {
      val r = trySoftForkable[ScriptReducer](whenSoftFork = WhenSoftForkReducer) {
        PrecompiledScriptReducer(key.ergoTreeBytes)(createIR())
      }(key.vs)

      val c = loadCounder.incrementAndGet()
      if (c > settings.reportingInterval) {
        if (loadCounder.compareAndSet(c, 1)) {
          // call reporting only if we was able to reset the counter
          // avoid double reporting
          onReportStats(cache.stats())
        }
      }

      r
    }
  }

  /** The cache which stores MRU set of pre-compiled reducers. */
  val cache: LoadingCache[CacheKey, ScriptReducer] = {
    var b = CacheBuilder.newBuilder
      .maximumSize(settings.maxCacheSize)
      .removalListener(cacheListener)
    if (settings.recordCacheStats) {
      b = b.recordStats()
    }
    b.build(cacheLoader)
  }

  /** Looks up verifier for the given ErgoTree using its 'bytes' property.
    * It first looks up for predefReducers, if not found it looks up in the cache.
    * If there is no cache entry, the `cacheLoader` is used to load a new `ScriptReducer`.
    *
    * @param ergoTree a tree to lookup pre-compiled verifier.
    * @return non-empty Nullable instance with verifier for the given tree, otherwise
    *         Nullable.None
    */
  def getReducer(ergoTree: ErgoTree, vs: SigmaValidationSettings): ScriptReducer = {
    val key = CacheKey(ergoTree.bytes, vs)
    predefReducers.get(key) match {
      case Nullable(r) => r
      case _ =>
        val r = try {
          cache.get(key)
        } catch {
          case e: ExecutionException =>
            throw e.getCause
        }
        r
    }
  }
}

object PrecompiledScriptProcessor {
  /** Default script processor which uses [[RuntimeIRContext]] to process graphs. */
  val Default = new PrecompiledScriptProcessor(
    ScriptProcessorSettings(mutable.WrappedArray.empty[CacheKey]))

  /** Script processor which uses [[CompiletimeIRContext]] to process graphs. */
  val WithCompiletimeIRContext = new PrecompiledScriptProcessor(
    ScriptProcessorSettings(
      predefScripts = mutable.WrappedArray.empty,
      recordCacheStats = true,
      reportingInterval = 10)) {
    override protected def createIR(): IRContext = new CompiletimeIRContext

    override protected def onReportStats(stats: CacheStats): Unit = {
      println(s"Cache Stats: $stats")
    }

    override protected def onEvictedCacheEntry(key: CacheKey): Unit = {
      val scriptHex = ErgoAlgos.encode(key.ergoTreeBytes.toArray)
      println(s"Evicted: ${scriptHex}")
    }
  }
}
