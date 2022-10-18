package org.utbot.engine.typed

import org.utbot.engine.UtExpressionVisitor

interface TypeExpressionVisitor<TResult> : UtExpressionVisitor<TResult> {
    fun visit(expr: UtIsExpression): TResult
    fun visit(expr: UtGenericExpression): TResult
    fun visit(expr: UtIsGenericTypeExpression): TResult
    fun visit(expr: UtEqGenericTypeParametersExpression): TResult
    fun visit(expr: UtInstanceOfExpression): TResult
}