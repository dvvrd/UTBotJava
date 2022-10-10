package org.utbot.engine.soot

import org.utbot.engine.*
import soot.jimple.BinopExpr
import soot.jimple.internal.JAddExpr
import soot.jimple.internal.JAndExpr
import soot.jimple.internal.JCmpExpr
import soot.jimple.internal.JCmpgExpr
import soot.jimple.internal.JCmplExpr
import soot.jimple.internal.JDivExpr
import soot.jimple.internal.JEqExpr
import soot.jimple.internal.JGeExpr
import soot.jimple.internal.JGtExpr
import soot.jimple.internal.JLeExpr
import soot.jimple.internal.JLtExpr
import soot.jimple.internal.JMulExpr
import soot.jimple.internal.JNeExpr
import soot.jimple.internal.JOrExpr
import soot.jimple.internal.JRemExpr
import soot.jimple.internal.JShlExpr
import soot.jimple.internal.JShrExpr
import soot.jimple.internal.JSubExpr
import soot.jimple.internal.JUshrExpr
import soot.jimple.internal.JXorExpr
import kotlin.reflect.KClass

private val ops: Map<KClass<*>, UtOperator<*>> = mapOf(
    JLeExpr::class to Le,
    JLtExpr::class to Lt,
    JGeExpr::class to Ge,
    JGtExpr::class to Gt,
    JEqExpr::class to Eq,
    JNeExpr::class to Ne,
    JDivExpr::class to Div,
    JRemExpr::class to Rem,
    JMulExpr::class to Mul,
    JAddExpr::class to Add,
    JSubExpr::class to Sub,
    JShlExpr::class to Shl,
    JShrExpr::class to Shr,
    JUshrExpr::class to Ushr,
    JXorExpr::class to Xor,
    JOrExpr::class to Or,
    JAndExpr::class to And,
    JCmpExpr::class to Cmp,
    JCmplExpr::class to Cmpl,
    JCmpgExpr::class to Cmpg
)

fun doOperation(
    sootExpression: BinopExpr,
    left: PrimitiveValue,
    right: PrimitiveValue
): UtExpression =
    ops[sootExpression::class]!!(left.expr, right.expr)
