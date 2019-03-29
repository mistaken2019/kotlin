/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.resolve.calls.NewCommonSuperTypeCalculator
import org.jetbrains.kotlin.resolve.calls.inference.components.TypeVariableDirectionCalculator.ResolveDirection
import org.jetbrains.kotlin.resolve.calls.inference.model.Constraint
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintKind
import org.jetbrains.kotlin.resolve.calls.inference.model.VariableWithConstraints
import org.jetbrains.kotlin.resolve.calls.inference.model.checkConstraint
import org.jetbrains.kotlin.resolve.constants.IntegerLiteralTypeConstructor
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.intersectTypes

class ResultTypeResolver(
    val typeApproximator: TypeApproximator,
    val trivialConstraintTypeInferenceOracle: TrivialConstraintTypeInferenceOracle
) {
    interface Context {
        fun isProperType(type: UnwrappedType): Boolean
    }

    fun findResultType(c: Context, variableWithConstraints: VariableWithConstraints, direction: ResolveDirection): UnwrappedType {
        variableWithConstraints.getErrorTypeFromConstraints()?.let {
            return it
        }

        findResultTypeOrNull(c, variableWithConstraints, direction)?.let { return it }

        // no proper constraints
        return variableWithConstraints.typeVariable.freshTypeConstructor.builtIns.run {
            if (direction == ResolveDirection.TO_SUBTYPE) nothingType else nullableAnyType
        }
    }

    fun findResultTypeOrNull(c: Context, variableWithConstraints: VariableWithConstraints, direction: ResolveDirection): UnwrappedType? {
        findResultIfThereIsEqualsConstraint(c, variableWithConstraints)?.let { return it }

        val subType = findSubType(c, variableWithConstraints)
        val superType = findSuperType(c, variableWithConstraints)
        val result = if (direction == ResolveDirection.TO_SUBTYPE || direction == ResolveDirection.UNKNOWN) {
            c.resultType(subType, superType, variableWithConstraints)
        } else {
            c.resultType(superType, subType, variableWithConstraints)
        }

        return result
    }

    private fun Context.resultType(
        firstCandidate: UnwrappedType?,
        secondCandidate: UnwrappedType?,
        variableWithConstraints: VariableWithConstraints
    ): UnwrappedType? {
        if (firstCandidate == null || secondCandidate == null) return firstCandidate ?: secondCandidate

        if (isSuitableType(firstCandidate, variableWithConstraints)) return firstCandidate

        if (isSuitableType(secondCandidate, variableWithConstraints)) {
            return secondCandidate
        } else {
            return firstCandidate
        }
    }

    private fun Context.isSuitableType(resultType: UnwrappedType, variableWithConstraints: VariableWithConstraints): Boolean {
        for (constraint in variableWithConstraints.constraints) {
            if (!isProperType(constraint.type)) continue
            if (!checkConstraint(constraint.type, constraint.kind, resultType)) return false
        }

        if (!trivialConstraintTypeInferenceOracle.isSuitableResultedType(resultType)) return false

        return true
    }

    private fun findSubType(c: Context, variableWithConstraints: VariableWithConstraints): UnwrappedType? {
        val lowerConstraints = variableWithConstraints.constraints.filter { it.kind == ConstraintKind.LOWER && c.isProperType(it.type) }
        if (lowerConstraints.isNotEmpty()) {
            val commonSuperType = NewCommonSuperTypeCalculator.commonSuperType(lowerConstraints.map { it.type })
            /**
             *
             * fun <T> Array<out T>.intersect(other: Iterable<T>) {
             *      val set = toMutableSet()
             *      set.retainAll(other)
             * }
             * fun <X> Array<out X>.toMutableSet(): MutableSet<X> = ...
             * fun <Y> MutableCollection<in Y>.retainAll(elements: Iterable<Y>) {}
             *
             * Here, when we solve type system for `toMutableSet` we have the following constrains:
             * Array<C(out T)> <: Array<out X> => C(out X) <: T.
             * If we fix it to T = C(out X) then return type of `toMutableSet()` will be `MutableSet<C(out X)>`
             * and type of variable `set` will be `MutableSet<out T>` and the following line will have contradiction.
             *
             * To fix this problem when we fix variable, we will approximate captured types before fixation.
             *
             */

            return typeApproximator.approximateToSuperType(
                commonSuperType,
                TypeApproximatorConfiguration.CapturedAndIntegerLiteralsTypesApproximation
            ) ?: commonSuperType
        }

        return null
    }

    private fun findSuperType(c: Context, variableWithConstraints: VariableWithConstraints): UnwrappedType? {
        val upperConstraints = variableWithConstraints.constraints.filter { it.kind == ConstraintKind.UPPER && c.isProperType(it.type) }
        if (upperConstraints.isNotEmpty()) {
            val upperType = intersectTypes(upperConstraints.map { it.type })

            return typeApproximator.approximateToSubType(upperType, TypeApproximatorConfiguration.CapturedAndIntegerLiteralsTypesApproximation) ?: upperType
        }
        return null
    }

    fun findResultIfThereIsEqualsConstraint(c: Context, variableWithConstraints: VariableWithConstraints): UnwrappedType? {
        val properEqualityConstraints = variableWithConstraints.constraints.filter {
            it.kind == ConstraintKind.EQUALITY && c.isProperType(it.type)
        }

        return representativeFromEqualityConstraints(properEqualityConstraints)
    }

    // Discriminate integer literal types as they are less specific than separate integer types (Int, Short...)
    private fun representativeFromEqualityConstraints(constraints: List<Constraint>): UnwrappedType? {
        if (constraints.isEmpty()) return null

        val constraintTypes = constraints.map { it.type }
        val nonLiteralTypes = constraintTypes.filter { it.constructor !is IntegerLiteralTypeConstructor }
        return nonLiteralTypes.singleBestRepresentative()?.unwrap()
            ?: constraintTypes.singleBestRepresentative()?.unwrap()
            ?: constraintTypes.first() // seems like constraint system has contradiction
    }
}