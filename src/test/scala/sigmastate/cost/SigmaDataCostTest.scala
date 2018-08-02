package sigmastate.cost

import sigmastate.lang.{TransformingSigmaBuilder, LangTests, SigmaCompiler}

import scalan.BaseCtxTests

class SigmaDataCostTest extends BaseCtxTests with LangTests {
  lazy val ctx = new TestContext with DataCostCtx {
    import TestSigmaDslBuilder._
    val sigmaDslBuilder = RTestSigmaDslBuilder()
    val builder = TransformingSigmaBuilder
  }
  import ctx._
  import Col._
  lazy val compiler = new SigmaCompiler(builder)

  test("split cols") {
    ctx.emit("split_cols",
      split(fun { in: Rep[(Col[Int], Byte)] =>
        dataCost(in)
      })
    )
  }

  test("split pair cols") {
    ctx.emit("split_pair_col",
      split(fun { in: Rep[(Col[(Int, Short)], Byte)] =>
        dataCost(in)
      })
    )
    ctx.emit("split_pair_cols2",
      split(fun { in: Rep[(Col[(Int, (Short, Boolean))], Byte)] =>
        dataCost(in)
      })
    )
  }

  test("split nested cols") {
    ctx.emit("split_nested_cols",
      split(fun { in: Rep[(Col[Col[Int]], Byte)] =>
        dataCost(in)
      })
    )
  }

  test("split nested pair cols") {
    ctx.emit("split_nested_pair_cols",
      split(fun { in: Rep[(Col[Col[(Int, Short)]], Byte)] =>
        dataCost(in)
      })
    )
  }

  test("split nested nested cols") {
    ctx.emit("split_nested_nested_cols",
      split(fun { in: Rep[(Col[Col[Col[Int]]], Byte)] =>
        dataCost(in)
      })
    )
  }

  test("split nested nested pair cols") {
    ctx.emit("split_nested_nested_pair_cols",
      split(fun { in: Rep[(Col[Col[Col[(Int, Short)]]], Byte)] =>
        dataCost(in)
      })
    )
  }

  test("split complex1 cols") {
    ctx.emit("split_complex1_cols",
      split(fun { in: Rep[(Col[Col[(Col[(Int, Short)], Boolean)]], Byte)] =>
        dataCost(in)
      })
    )
  }

  test("split complex2 cols") {
    ctx.emit("split_complex2_cols",
      split(fun { in: Rep[(Col[(Col[(Col[(Int, Boolean)], Short)], Char)], Byte)] =>
        dataCost(in)
      })
    )
  }

}
