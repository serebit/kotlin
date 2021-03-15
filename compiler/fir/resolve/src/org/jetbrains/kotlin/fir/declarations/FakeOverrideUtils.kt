/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.scopes.FirTypeScope
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenFunctions
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenProperties
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol

sealed class FirFakeOverrideStub(val baseSymbols: List<FirCallableSymbol<*>>) {
    class FromScope(
        val symbol: FirCallableSymbol<*>,
        baseSymbols: List<FirCallableSymbol<*>>
    ) : FirFakeOverrideStub(baseSymbols)

    class Trivial(baseSymbol: FirCallableSymbol<*>) : FirFakeOverrideStub(listOf(baseSymbol))

    val baseSymbol get() = baseSymbols.single()
}

private object FakeOverrideStubKey : FirDeclarationDataKey()

var FirClass<*>.fakeOverrideStubs: List<FirFakeOverrideStub>?
        by FirDeclarationDataRegistry.data(FakeOverrideStubKey)

fun FirClass<*>.computeFakeOverrideStubs(
    realDeclarations: Collection<FirDeclaration>,
    session: FirSession,
    scopeSession: ScopeSession,
): List<FirFakeOverrideStub> {
    fakeOverrideStubs?.let { return it }
    val result = mutableListOf<FirFakeOverrideStub>()
    val classScope = unsubstitutedScope(session, scopeSession, withForcedTypeCalculator = false)

    val classLookupTag = symbol.toLookupTag()
    val realDeclarationNames = realDeclarations.mapNotNullTo(mutableSetOf()) l@{
        val callableDeclaration = (it as? FirCallableMemberDeclaration<*>) ?: return@l null
        // We may need to create a new fake override; compute base symbols; or deal with name conflicts.
        if (callableDeclaration.symbol.shouldHaveComputedBaseSymbolsForClass(classLookupTag) ||
            callableDeclaration.allowsToHaveFakeOverrideIn(this) ||
            callableDeclaration.visibility == Visibilities.Private
        ) null
        // Otherwise, bail out early based on the name of the contributed member declaration
        else callableDeclaration.symbol.callableId.callableName
    }

    val superTypesCallableNames = classScope.getCallableNames().filter { it !in realDeclarationNames }
    for (name in superTypesCallableNames) {
        classScope.processFunctionsByName(name) { functionSymbol ->
            // TODO: MANY_* as well as some conflict diagnostics
            //if (functionSymbol is FirIntersectionOverrideFunctionSymbol)
            //    functionSymbol.intersections.forEach(::checkFunctionSymbolAndAddToResult)
            //else
            computeFakeOverrideStubsForSymbol(
                functionSymbol,
                realDeclarations,
                result,
                classScope,
                FirTypeScope::getDirectOverriddenFunctions
            )
        }

        classScope.processPropertiesByName(name) { propertySymbol ->
            // TODO: MANY_* as well as some conflict diagnostics
            //if (propertySymbol is FirIntersectionOverridePropertySymbol)
            //    propertySymbol.intersections.forEach(::checkPropertySymbolAndAddToResult)
            //else
            computeFakeOverrideStubsForSymbol(
                propertySymbol,
                realDeclarations,
                result,
                classScope,
                FirTypeScope::getDirectOverriddenProperties
            )
        }
    }

    fakeOverrideStubs = result
    return result
}

private inline fun <
        reified D : FirCallableMemberDeclaration<D>,
        reified S : FirCallableSymbol<D>
        > FirClass<*>.computeFakeOverrideStubsForSymbol(
    originalSymbol: FirCallableSymbol<*>,
    realDeclarations: Collection<FirDeclaration>,
    result: MutableList<FirFakeOverrideStub>,
    scope: FirTypeScope,
    computeDirectOverridden: FirTypeScope.(S) -> List<S>,
) {
    if (originalSymbol !is S || originalSymbol.fir in realDeclarations) return
    val classLookupTag = symbol.toLookupTag()
    val originalDeclaration = originalSymbol.fir
    if (originalSymbol.dispatchReceiverClassOrNull() == classLookupTag && !originalDeclaration.origin.fromSupertypes) return
    if (originalDeclaration.visibility == Visibilities.Private) return
    when {
        originalSymbol.shouldHaveComputedBaseSymbolsForClass(classLookupTag) -> {
            // Substitution or intersection case.
            // The current one is a FIR declaration for that fake override, and we can compute base symbols from it.
            result.add(
                FirFakeOverrideStub.FromScope(
                    originalSymbol, computeBaseSymbols(originalSymbol, computeDirectOverridden, scope, classLookupTag)
                )
            )
        }
        originalDeclaration.allowsToHaveFakeOverrideIn(firClass = this) -> {
            // Trivial fake override case.
            // FIR2IR will create a fake override in BE IR directly, and the current one _is_ the base declaration.
            result.add(FirFakeOverrideStub.Trivial(originalSymbol))
        }
    }
}

private fun FirCallableMemberDeclaration<*>.allowsToHaveFakeOverrideIn(firClass: FirClass<*>): Boolean {
    if (!allowsToHaveFakeOverride) {
        return false
    }
    return visibility.canSeePackage(symbol.callableId.packageName, firClass.symbol.classId.packageFqName)
}

