package org.utbot.engine.typed

import org.utbot.engine.*

class TypeRewritingVisitor(
   eqs: Map<UtExpression, UtExpression> = emptyMap(),
   lts: Map<UtExpression, Long> = emptyMap(),
   gts: Map<UtExpression, Long> = emptyMap()
) : RewritingVisitor(eqs, lts, gts), TypeExpressionVisitor<UtExpression> {

    override fun visit(expr: UtIsExpression): UtExpression = applySimplification(expr, false) {
        UtIsExpression(expr.addr.accept(this) as UtAddrExpression, expr.typeStorage, expr.numberOfTypes)
    }

    override fun visit(expr: UtGenericExpression): UtExpression = applySimplification(expr, false) {
        UtGenericExpression(expr.addr.accept(this) as UtAddrExpression, expr.types, expr.numberOfTypes)
    }

    override fun visit(expr: UtIsGenericTypeExpression): UtExpression = applySimplification(expr, false) {
        UtIsGenericTypeExpression(
            expr.addr.accept(this) as UtAddrExpression,
            expr.baseAddr.accept(this) as UtAddrExpression,
            expr.parameterTypeIndex
        )
    }

    override fun visit(expr: UtEqGenericTypeParametersExpression): UtExpression =
        applySimplification(expr, false) {
            UtEqGenericTypeParametersExpression(
                expr.firstAddr.accept(this) as UtAddrExpression,
                expr.secondAddr.accept(this) as UtAddrExpression,
                expr.indexMapping
            )
        }

    override fun visit(expr: UtInstanceOfExpression): UtExpression = applySimplification(expr, false) {
        val simplifiedHard = (expr.constraint.accept(this) as UtBoolExpression).asHardConstraint()
        UtInstanceOfExpression(expr.symbolicStateUpdate.copy(hardConstraints = simplifiedHard))
    }
}