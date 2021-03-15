/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.scopes.FirTypeScope
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenFunctions
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenProperties
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol

fun FirClass<*>.calcBaseSymbolsForFakeOverrides(
    contributedDeclarations: Collection<FirDeclaration>,
    containerFile: FirFile,
    session: FirSession,
    scopeSession: ScopeSession,
): Collection<FirCallableSymbol<*>> {
    baseSymbolsForFakeOverrides?.let { return it }
    val result = mutableListOf<FirCallableSymbol<*>>()
    val classScope = unsubstitutedScope(session, scopeSession, withForcedTypeCalculator = false)

    fun checkFunctionSymbolAndAddToResult(originalSymbol: FirCallableSymbol<*>) {
        this.computeBaseDeclarations(
            originalSymbol,
            contributedDeclarations,
            result,
            classScope,
            FirTypeScope::getDirectOverriddenFunctions,
            containerFile,
            session
        )
    }

    fun checkPropertySymbolAndAddToResult(originalSymbol: FirCallableSymbol<*>) {
        this.computeBaseDeclarations(
            originalSymbol,
            contributedDeclarations,
            result,
            classScope,
            FirTypeScope::getDirectOverriddenProperties,
            containerFile,
            session
        )
    }

    val contributedDeclarationNames = contributedDeclarations.mapNotNullTo(mutableSetOf()) l@{
        val callableDeclaration = (it as? FirCallableMemberDeclaration<*>) ?: return@l null
        // TODO: for multi-inheritance, we may need much stronger conditions like something in [FakeOverrideGenerator]
        // For now, collect non-abstract members only
        if (callableDeclaration.status.modality == Modality.ABSTRACT) null
        // Otherwise, bail out early based on the name of the contributed member declaration
        else callableDeclaration.symbol.callableId.callableName
    }

    val superTypesCallableNames = classScope.getCallableNames().filter { it !in contributedDeclarationNames }
    for (name in superTypesCallableNames) {
        classScope.processFunctionsByName(name) { functionSymbol ->
            // TODO: MANY_* as well as some conflict diagnostics
            //if (functionSymbol is FirIntersectionOverrideFunctionSymbol)
            //    functionSymbol.intersections.forEach(::checkFunctionSymbolAndAddToResult)
            //else
            checkFunctionSymbolAndAddToResult(functionSymbol)
        }

        classScope.processPropertiesByName(name) { propertySymbol ->
            // TODO: MANY_* as well as some conflict diagnostics
            //if (propertySymbol is FirIntersectionOverridePropertySymbol)
            //    propertySymbol.intersections.forEach(::checkPropertySymbolAndAddToResult)
            //else
            checkPropertySymbolAndAddToResult(propertySymbol)
        }
    }

    baseSymbolsForFakeOverrides = result
    return result
}

private inline fun <reified D : FirCallableMemberDeclaration<D>, reified S : FirCallableSymbol<D>> FirClass<*>.computeBaseDeclarations(
    originalSymbol: FirCallableSymbol<*>,
    contributedDeclarations: Collection<FirDeclaration>,
    result: MutableList<FirCallableSymbol<*>>,
    scope: FirTypeScope,
    computeDirectOverridden: FirTypeScope.(S) -> List<S>,
    containerFile: FirFile,
    session: FirSession,
) {
    if (originalSymbol !is S || originalSymbol.fir in contributedDeclarations) return
    val classLookupTag = symbol.toLookupTag()
    val originalDeclaration = originalSymbol.fir
    if (originalSymbol.dispatchReceiverClassOrNull() == classLookupTag && !originalDeclaration.origin.fromSupertypes) return
    if (originalDeclaration.visibility == Visibilities.Private) return
    when {
        originalSymbol.shouldHaveComputedBaseSymbolsForClass(classLookupTag) -> {
            // Substitution or intersection case.
            // The current one is a FIR declaration for that fake override, and we can compute base symbols from it.
            computeBaseSymbols(originalSymbol, computeDirectOverridden, scope, classLookupTag)
        }
        originalDeclaration.allowsToHaveFakeOverrideIn(firClass = this, containerFile, session) -> {
            // Trivial fake override case.
            // FIR2IR will create a fake override in BE IR directly, and the current one _is_ the base declaration.
            result.add(originalSymbol)
        }
    }
}

private fun FirCallableMemberDeclaration<*>.allowsToHaveFakeOverrideIn(
    firClass: FirClass<*>,
    containerFile: FirFile,
    session: FirSession,
): Boolean {
    if (!allowsToHaveFakeOverride) {
        return false
    }
    if (!session.visibilityChecker.isVisible(this, session, containerFile, listOf(), dispatchReceiver = null)) {
        return false
    }
    return this.symbol.callableId.packageName == firClass.symbol.classId.packageFqName
}

