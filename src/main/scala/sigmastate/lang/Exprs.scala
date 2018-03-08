package sigmastate.lang

import fastparse.noApi._
import sigmastate._
import Terms._
import sigmastate.lang.syntax.Basic._

import scala.collection.mutable

//noinspection ForwardReference,TypeAnnotation
trait Exprs extends Core with Types {

  import WhitespaceApi._
  def AnonTmpl: P0
  def BlockDef: P[Value[SType]]

  // Depending on where an expression is located, subtle behavior around
  // semicolon inference and arrow-type-ascriptions like i: a => b
  // varies.

  // Expressions used as statements, directly within a {block}
  object StatCtx extends WsCtx(semiInference=true, arrowTypeAscriptions=false)
  // Expressions nested within other expressions
  object ExprCtx extends WsCtx(semiInference=false, arrowTypeAscriptions=true)
  // Expressions directly within a `val x = ...` or `def x = ...`
  object FreeCtx extends WsCtx(semiInference=true, arrowTypeAscriptions=true)

  val TypeExpr = ExprCtx.Expr

  //noinspection TypeAnnotation,ForwardReference
  class WsCtx(semiInference: Boolean, arrowTypeAscriptions: Boolean){

    val OneSemiMax = if (semiInference) OneNLMax else Pass
    val NoSemis = if (semiInference) NotNewline else Pass

    val Expr: P[Value[SType]] = {
      val If = {
        val Else = P( Semi.? ~ `else` ~/ Expr )
        P( `if` ~/ "(" ~ ExprCtx.Expr ~ ")" ~ Expr ~ Else ).map {
          case (c, t, e) => sigmastate.If(c.asValue[SBoolean.type], t, e)
        }
      }
      val Fun = P( `fun` ~/ LambdaDef)

      val LambdaRhs = if (semiInference) P( BlockChunk.map(mkBlock) ) else P( Expr )
//      val ParenedLambda = P( Parened ~~ (WL ~ `=>` ~ LambdaRhs.? /*| ExprSuffix ~~ PostfixSuffix ~ SuperPostfixSuffix*/) ).map {
//        case (args, None) => mkLambda(args, UnitConstant)
//        case (args, Some(body)) => mkLambda(args, body)
//      }
      val PostfixLambda = P( PostfixExpr ~ ((`=>` ~ LambdaRhs.?) | SuperPostfixSuffix) ).map {
        case (e, None) => e
//        case (i: Ident, Some(None)) => mkLambda(Seq(i), UnitConstant)
//        case (Tuple(args), None) => mkLambda(args.toSeq, UnitConstant)
        case (Tuple(args), None) => mkLambda(args.toSeq, UnitConstant)
        case (Tuple(args), Some(body)) => mkLambda(args.toSeq, body)
      }
      val SmallerExprOrLambda = P( /*ParenedLambda |*/ PostfixLambda )
//      val Arg = (Id.! ~ `:` ~/ Type).map { case (n, t) => Ident(IndexedSeq(n), t)}
      P( If | Fun | SmallerExprOrLambda )
    }

    val SuperPostfixSuffix = P( (`=` ~/ Expr).? /*~ MatchAscriptionSuffix.?*/ )
    val AscriptionType = (if (arrowTypeAscriptions) P( Type ) else P( InfixType )).ignore
    val Ascription = P( `:` ~/ (`_*` |  AscriptionType | Annot.rep(1)) )
    val MatchAscriptionSuffix = P(`match` ~/ "{" ~ CaseClauses | Ascription)
    val ExprPrefix = P( WL ~ CharIn("-+!~").! ~~ !syntax.Basic.OpChar ~ WS)
    val ExprSuffix = P(
      (WL ~ "." ~/ Id.!.map(Ident(_))
      | /*WL ~ TypeArgs |*/ NoSemis ~ ArgList ).repX /* ~~ (NoSemis  ~ `_`).? */ )

    val PrefixExpr = P( ExprPrefix.? ~ SimpleExpr ).map {
      case (Some(op), e) => mkUnaryOp(op, e)
      case (None, e) => e
    }

    // Intermediate `WL` needs to always be non-cutting, because you need to
    // backtrack out of `InfixSuffix` into `PostFixSuffix` if it doesn't work out
    val InfixSuffix = P( NoSemis ~~ WL ~~ Id.! /*~ TypeArgs.?*/ ~~ OneSemiMax ~ PrefixExpr ~~ ExprSuffix).map {
      case (op, f, args) =>
        val rhs = applySuffix(f, args)
        (op, rhs)
    }
    val PostFix = P( NoSemis ~~ WL ~~ Id.! ~ Newline.? ).map(Ident(_))

    val PostfixSuffix = P( InfixSuffix.repX ~~ PostFix.?)

    val PostfixExpr = P( PrefixExpr ~~ ExprSuffix ~~ PostfixSuffix ).map {
      case (prefix, suffix, (infixOps, postfix)) =>
        val lhs = applySuffix(prefix, suffix)
        val obj = mkInfixTree(lhs, infixOps)
        postfix.fold(obj) {
          case Ident(IndexedSeq(name), _) =>
            MethodCall(obj, name, IndexedSeq.empty)
        }
    }

    val Parened = P ( "(" ~/ TypeExpr.repTC() ~ ")" )
    val SimpleExpr = {
//      val New = P( `new` ~/ AnonTmpl )

      P( /*New | */ BlockExpr
        | ExprLiteral
        | StableId.map { case Ident(ps, t) => mkIdent(ps, t) }
        | `_`.!.map(Ident(_))
        | Parened.map(items =>
            if (items.isEmpty) UnitConstant
            else if (items.size == 1) items(0)
            else Tuple(items)) )
    }
    val Guard : P0 = P( `if` ~/ PostfixExpr ).ignore
  }

  protected def mkIdent(nameParts: IndexedSeq[String], tpe: SType = NoType): SValue = {
    require(nameParts.nonEmpty)
    if (nameParts.size == 1)
      Ident(nameParts, tpe)
    else {
      val first: SValue = Ident(nameParts(0))
      nameParts.iterator.drop(1).foldLeft(first)((acc, p) => Select(acc, p))
    }
  }

  protected def mkLambda(args: Seq[Value[SType]], body: Value[SType]): Value[SType] = {
    val names = args.map { case Ident(IndexedSeq(n), t) => (n, t) }
    Lambda(names.toIndexedSeq, None, body)
//    error(s"Cannot create Lambda($args, $body)")
  }

  protected def mkApply(func: Value[SType], args: IndexedSeq[Value[SType]]): Value[SType] = (func, args) match {
    case (Ident(Vector("Array"), _), args) =>
      val tpe = if (args.isEmpty) NoType else args(0).tpe
      ConcreteCollection(args)(tpe)
    case _ => Apply(func, args)
  }

  /** The precedence of an infix operator is determined by the operator's first character.
    * Characters are listed below in increasing order of precedence, with characters on the same line
    * having the same precedence. */
  val priorityList = Seq(
    // all letters have lowerst precedence 0
    Seq('|'),
    Seq('^'),
    Seq('&'),
    Seq('=', '!'),
    Seq('<', '>'),
    Seq(':', '>'),
    Seq('+', '-'),
    Seq('*', '/', '%')
  )
  
  val priorityMap = (for { 
    (xs, p) <- priorityList.zipWithIndex.map { case (xs, i) => (xs, i + 1) }
    x <- xs
  } yield (x, p)).toMap

  @inline def precedenceOf(ch: Char): Int = if (priorityMap.contains(ch)) priorityMap(ch) else 0
  @inline def precedenceOf(op: String): Int = precedenceOf(op(0))

  protected[lang] def mkInfixTree(lhs: SValue, rhss: Seq[(String, SValue)]): SValue = {
    def build(first: SValue, op: String, second: SValue, rest: List[(String, SValue)]): SValue = rest match {
      case Nil => mkBinaryOp(op, first, second)
      case (op2, third) :: t =>
        if (precedenceOf(op) >= precedenceOf(op2))
          build(mkBinaryOp(op, first, second), op2, third, t)
        else {
          val n = build(second, op2, third, t)
          mkBinaryOp(op, first, n)
        }
    }
    if (rhss.isEmpty) lhs
    else {
      val (op, second) :: t = rhss.toList
      build(lhs, op, second, t)
    }
  }

  protected def applySuffix(f: Value[SType], args: Seq[Value[SType]]): Value[SType] = {
    val rhs = args.foldLeft(f)((acc, arg) => arg match {
      case Ident(parts, t) =>
        assert(parts.size == 1, s"Ident with many parts are not supported: $parts")
        Select(acc, parts(0))
      case UnitConstant => mkApply(acc, IndexedSeq.empty)
      case Tuple(xs) => mkApply(acc, xs)
      case arg => mkApply(acc, IndexedSeq(arg))
    })
    rhs
  }

  val LambdaDef = {
    val Body = P( WL ~ `=` ~ StatCtx.Expr )
    P( FunSig ~ (`:` ~/ Type).? ~~ Body ).map {
      case (secs @ Seq(args), resType, body) => Lambda(args.toIndexedSeq, resType, body)
      case (secs, resType, body) => error(s"Function can only have single argument list: fun ($secs): $resType = $body")
    }
  }

  val SimplePattern = {
    val TupleEx = P( "(" ~/ Pattern.repTC() ~ ")" )
    val Extractor = P( StableId /* ~ TypeArgs.?*/ ~ TupleEx.? )
//    val Thingy = P( `_` ~ (`:` ~/ TypePat).? ~ !("*" ~~ !syntax.Basic.OpChar) )
    P( /*Thingy | PatLiteral |*/ TupleEx | Extractor | VarId.!.map(Ident(_)))
  }

  val BlockExpr = P( "{" ~/ (/*CaseClauses |*/ Block ~ "}") )

  val BlockLambdaHead: P0 = P( "(" ~ BlockLambdaHead ~ ")" | `this` | Id | `_` )
  val BlockLambda = P( BlockLambdaHead  ~ (`=>` | `:` ~ InfixType ~ `=>`.?) ).ignore

  val BlockChunk = {
    val Prelude = P( Annot.rep ~ `lazy`.? )
    val BlockStat = P( Prelude ~ BlockDef | StatCtx.Expr )
    P( BlockLambda.rep ~ BlockStat.rep(sep = Semis) )
  }
  protected def mkBlock(stats: Seq[SValue]): SValue = {
    if (stats.isEmpty)
      Terms.Block(None, UnitConstant)
    else
      stats.take(stats.size - 1).foldRight(stats.last) {
        case (r, curr) => Terms.Block(Some(r), curr)
      }
  }

  def BaseBlock(end: P0)(implicit name: sourcecode.Name): P[Value[SType]] = {
    val BlockEnd = P( Semis.? ~ &(end) )
    val Body = P( BlockChunk.repX(sep = Semis) )
    P( Semis.? /*~ BlockLambda.?*/ ~ Body ~/ BlockEnd ).map(ss => mkBlock(ss.flatten))
  }
  val Block = BaseBlock("}")
  val CaseBlock = BaseBlock("}" | `case`)

  val Patterns: P0 = P( Pattern.rep(1, sep = ",".~/) )
  val Pattern: P0 = P( (WL ~ TypeOrBindPattern).rep(1, sep = "|".~/) )
  val TypePattern = P( (`_` | BacktickId | VarId) ~ `:` ~ TypePat )
  val TypeOrBindPattern: P0 = P( TypePattern | BindPattern ).ignore
  val BindPattern = {
    val InfixPattern = P( SimplePattern /*~ (Id ~/ SimplePattern).rep | `_*`*/ )
//    val Binding = P( (Id | `_`) ~ `@` )
    P( /*Binding ~ InfixPattern | */ InfixPattern /*| VarId*/ )
  }

  val TypePat = P( CompoundType )
  val ParenArgList = P( "(" ~/ (Exprs /*~ (`:` ~/ `_*`).?*/).? ~ TrailingComma ~ ")" ).map {
    case Some(exprs) => Tuple(exprs)
    case None => UnitConstant
  }
  val ArgList = P( ParenArgList | OneNLMax ~ BlockExpr )

  val CaseClauses: P0 = {
    // Need to lookahead for `class` and `object` because
    // the block { case object X } is not a case clause!
    val CaseClause: P0 = P( `case` ~/ Pattern ~ ExprCtx.Guard.? ~ `=>` ~ CaseBlock  ).ignore
    P( CaseClause.rep(1) ~ "}"  )
  }
}
