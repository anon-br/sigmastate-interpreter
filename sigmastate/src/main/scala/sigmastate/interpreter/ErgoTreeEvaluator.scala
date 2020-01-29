package sigmastate.interpreter

import org.ergoplatform.{ErgoLikeContext, Context}
import scalan.Nullable
import sigmastate.SType
import sigmastate.Values._
import sigmastate.interpreter.ErgoTreeEvaluator.DataEnv
import sigmastate.interpreter.Interpreter.{ReductionResult, ScriptEnv}
import sigmastate.lang.Terms._
import sigmastate.lang.exceptions.CostLimitException
import sigmastate.utils.SparseArrayContainer
import sigmastate.utxo.GetVar
import special.sigma.Context

class EvalContext(
  val context: Context,
  val constants: Seq[Constant[SType]],
  val costAccumulator: CostAccumulator)

trait Evaluator {
  def eval(env: DataEnv, exp: SValue): Any
}

class ErgoTreeEvaluator(val evalContext: EvalContext) extends Evaluator {
  import sigmastate.interpreter.ErgoTreeEvaluator._


  def eval(env: DataEnv, exp: SValue): Any = {
    exp match {
      case Context => evalContext.context
      case _ =>
        exp.eval(this, env)
    }
  }

}

object ErgoTreeEvaluator {
  /** Immutable data environment used to assign data values to graph nodes. */
  type DataEnv = Map[Int, Any]

  def eval(context: ErgoLikeContext, ergoTree: ErgoTree): ReductionResult = {
    val costAccumulator = new CostAccumulator(0, Some(context.costLimit))
    val sigmaContext = context.toSigmaContext(isCost = false)

    val ctx = new EvalContext(sigmaContext, ergoTree.constants, costAccumulator)
    val evaluator = new ErgoTreeEvaluator(ctx)
    val res = evaluator.eval(Map(), ergoTree.toProposition(false))
    val cost = ctx.costAccumulator.totalCost
    val sb = res match {
      case sb: SigmaBoolean => sb
      case _ => error(s"Expected SigmaBoolean but was: $res")
    }
    (sb, cost)
  }

  def error(msg: String) = sys.error(msg)

  def msgCostLimitError(cost: Long, limit: Long) = s"Estimated execution cost $cost exceeds the limit $limit"

  val operations: SparseArrayContainer[ValueCompanion] = SparseArrayContainer.buildForOperations(Array(
    Apply,
    GetVar,
    ValUse,
    FuncValue,
    MethodCall
  ))
//  /** State monad for ValDef nodes computed in a data environment.
//    * `DataEnv` is used as the state of the state monad.
//    * And ValDefs are represented by Int identifiers
//    */
//  case class EnvRep[A](run: DataEnv => (DataEnv, Int)) {
//    def flatMap[B](f: Int => EnvRep[B]): EnvRep[B] = EnvRep { env =>
//      val (env1, x) = run(env)
//      val res = f(x).run(env1)
//      res
//    }
//    def map[B](f: Int => Int): EnvRep[B] = EnvRep { env =>
//      val (env1, x) = run(env)
//      val y = f(x)
//      (env1, y)
//    }
//  }
//  object EnvRep {
//    def add[T](entry: (Int, AnyRef)): EnvRep[T] =
//      EnvRep { env => val (sym, value) = entry; (env + (sym -> value), sym) }
//  }

}

/** Incapsulate simple monotonic (add only) counter with reset. */
class CostCounter(val initialCost: Int) {
  private var _currentCost: Int = initialCost

  @inline def += (n: Int) = {
    // println(s"${_currentCost} + $n")
    this._currentCost = java.lang.Math.addExact(this._currentCost, n)
  }
  @inline def currentCost: Int = _currentCost
  @inline def resetCost() = { _currentCost = initialCost }
}

/** Implements finite state machine with stack of graph blocks (scopes),
  * which correspond to lambdas and thunks.
  * It accepts messages: startScope(), endScope(), add(), reset()
  * At any time `totalCost` is the currently accumulated cost. */
class CostAccumulator(initialCost: Int, costLimit: Option[Long]) {

  @inline private def initialStack() = List(new Scope(0))
  private var _scopeStack: List[Scope] = initialStack

  @inline def currentScope: Scope = _scopeStack.head

  /** Represents a single scope during execution of the graph.
    * The lifetime of each instance is bound to scope execution.
    * When the evaluation enters a new scope (e.g. calling a lambda) a new Scope instance is created and pushed
    * to _scopeStack, then is starts receiving `add` method calls.
    * When the evaluation leaves the scope, the top is popped off the stack. */
  class Scope(initialCost: Int) extends CostCounter(initialCost) {


    @inline def add(opCost: Int): Unit = {
          this += opCost
    }

    /** Called by nested Scopes to communicate accumulated cost back to parent scope.
      * When current scope terminates, it communicates accumulated cost up to its parent scope.
      * This value is used at the root scope to obtain total accumulated scope.
      */
    private var _resultRegister: Int = 0
    @inline def childScopeResult: Int = _resultRegister
    @inline def childScopeResult_=(resultCost: Int): Unit = {
      _resultRegister = resultCost
    }

  }

  /** Called once for each operation of a scope (lambda or thunk).
    */
  def add(opCost: Int): Unit = {
    currentScope.add(opCost)

    // check that we are still withing the limit
    if (costLimit.isDefined) {
      val limit = costLimit.get
      // the cost we accumulated so far
      val accumulatedCost = currentScope.currentCost
      if (accumulatedCost > limit) {
        //          if (cost < limit)
        //            println(s"FAIL FAST in loop: $accumulatedCost > $limit")
        throw new CostLimitException(
          accumulatedCost, ErgoTreeEvaluator.msgCostLimitError(accumulatedCost, limit), None)
      }
    }
  }

  /** Resets this accumulator into initial state to be ready for new graph execution. */
  @inline def reset() = {
    _scopeStack = initialStack()
  }

  /** Returns total accumulated cost */
  @inline def totalCost: Int = currentScope.currentCost
}

