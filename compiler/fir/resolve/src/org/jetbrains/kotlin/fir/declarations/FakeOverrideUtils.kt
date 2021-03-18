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
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.name.Name

sealed class FirFakeOverrideStub(val baseSymbols: List<FirCallableSymbol<*>>) {
    abstract val unwrappedSymbol: FirCallableSymbol<*>

    class FromScope(
        val symbol: FirCallableSymbol<*>,
        baseSymbols: List<FirCallableSymbol<*>>
    ) : FirFakeOverrideStub(baseSymbols) {
        override val unwrappedSymbol: FirCallableSymbol<*>
            get() = symbol.unwrapSubstitutionAndIntersectionOverrides()
    }

    class Trivial(baseSymbol: FirCallableSymbol<*>) : FirFakeOverrideStub(listOf(baseSymbol)) {
        override val unwrappedSymbol: FirCallableSymbol<*>
            get() = baseSymbol.unwrapSubstitutionAndIntersectionOverrides()
    }

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

    val result = mutableListOf<FirFakeOverrideStub>()
    val useSiteMemberScope = unsubstitutedScope(session, scopeSession, withForcedTypeCalculator = false)
    val superTypesCallableNames = useSiteMemberScope.getCallableNames().filter { it !in realDeclarationNames }

    val realDeclarationSymbols = realDeclarations.filterIsInstance<FirSymbolOwner<*>>().mapTo(mutableSetOf(), FirSymbolOwner<*>::symbol)

    for (name in superTypesCallableNames) {
        computeFakeOverrideStubsForName(useSiteMemberScope, name, result, realDeclarationSymbols)
    }

    fakeOverrideStubs = result
    return result
}

@OptIn(ExperimentalStdlibApi::class)
fun FirClass<*>.computeFakeOverrideStubsForName(
    name: Name,
    scopeSession: ScopeSession
): List<FirFakeOverrideStub> {
    return buildList {
        val useSiteMemberScope = unsubstitutedScope(session, scopeSession, withForcedTypeCalculator = true)
        computeFakeOverrideStubsForName(useSiteMemberScope, name, this, realDeclarationSymbols = emptySet())
    }
}

fun FirClass<*>.computeFakeOverrideStubsForName(
    useSiteMemberScope: FirTypeScope,
    name: Name,
    result: MutableList<FirFakeOverrideStub>,
    realDeclarationSymbols: Set<AbstractFirBasedSymbol<*>>
) {
    useSiteMemberScope.processFunctionsByName(name) { functionSymbol ->
        // TODO: MANY_* as well as some conflict diagnostics
        //if (functionSymbol is FirIntersectionOverrideFunctionSymbol)
        //    functionSymbol.intersections.forEach(::checkFunctionSymbolAndAddToResult)
        //else
        computeFakeOverrideStubsForSymbol(
            functionSymbol,
            realDeclarationSymbols,
            result,
            useSiteMemberScope,
            FirTypeScope::getDirectOverriddenFunctions
        )
    }

    useSiteMemberScope.processPropertiesByName(name) { propertySymbol ->
        // TODO: MANY_* as well as some conflict diagnostics
        //if (propertySymbol is FirIntersectionOverridePropertySymbol)
        //    propertySymbol.intersections.forEach(::checkPropertySymbolAndAddToResult)
        //else
        computeFakeOverrideStubsForSymbol(
            propertySymbol,
            realDeclarationSymbols,
            result,
            useSiteMemberScope,
            FirTypeScope::getDirectOverriddenProperties
        )
    }
}

private inline fun <
        reified D : FirCallableMemberDeclaration<D>,
        reified S : FirCallableSymbol<D>
        > FirClass<*>.computeFakeOverrideStubsForSymbol(
    originalSymbol: FirCallableSymbol<*>,
    realDeclarationSymbols: Set<AbstractFirBasedSymbol<*>>,
    result: MutableList<FirFakeOverrideStub>,
    scope: FirTypeScope,
    computeDirectOverridden: FirTypeScope.(S) -> List<S>,
) {
    if (originalSymbol !is S) return
    val classLookupTag = symbol.toLookupTag()
    val originalDeclaration = originalSymbol.fir

    if (originalSymbol.dispatchReceiverClassOrNull() == classLookupTag && !originalDeclaration.origin.fromSupertypes) return
    // Data classes' methods from Any (toString/equals/hashCode) are not handled by the line above because they have Any-typed dispatch receiver
    // (there are no special FIR method for them, it's just fake overrides)
    // But they are treated differently in IR (real declarations have already been declared before) and such methods are present among realDeclarationSymbols
    if (originalSymbol in realDeclarationSymbols) return

    if (originalDeclaration.visibility == Visibilities.Private) return
    when {
        originalSymbol.shouldHaveComputedBaseSymbolsForClass(classLookupTag) -> {
            // Substitution or intersection case.
            // The current one is a FIR declaration for that fake override, and we can compute base symbols from it.
            result.add(
                FirFakeOverrideStub.FromScope(
                    originalSymbol,
                    computeBaseSymbols(originalSymbol, computeDirectOverridden, scope, classLookupTag)
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

private tailrec fun FirCallableSymbol<*>.unwrapSubstitutionAndIntersectionOverrides(): FirCallableSymbol<*> {
    val originalForSubstitutionOverride = originalForSubstitutionOverride
    if (originalForSubstitutionOverride != null) return originalForSubstitutionOverride.unwrapSubstitutionAndIntersectionOverrides()

    val baseForIntersectionOverride = baseForIntersectionOverride
    if (baseForIntersectionOverride != null) return baseForIntersectionOverride.unwrapSubstitutionAndIntersectionOverrides()

    return this
}

private fun FirCallableMemberDeclaration<*>.allowsToHaveFakeOverrideIn(firClass: FirClass<*>): Boolean {
    if (!allowsToHaveFakeOverride) {
        return false
    }
    return visibility.canSeePackage(symbol.callableId.packageName, firClass.symbol.classId.packageFqName)
}

