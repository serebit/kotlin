/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.generators

import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.backend.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.*
import org.jetbrains.kotlin.fir.scopes.impl.FirFakeOverrideGenerator
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name

class FakeOverrideGenerator(
    private val components: Fir2IrComponents,
    private val conversionScope: Fir2IrConversionScope
) : Fir2IrComponents by components {

    private val baseFunctionSymbols = mutableMapOf<IrFunction, List<FirNamedFunctionSymbol>>()
    private val basePropertySymbols = mutableMapOf<IrProperty, List<FirPropertySymbol>>()

    private fun IrSimpleFunction.withFunction(f: IrSimpleFunction.() -> Unit): IrSimpleFunction {
        return conversionScope.withFunction(this, f)
    }

    private fun IrProperty.withProperty(f: IrProperty.() -> Unit): IrProperty {
        return conversionScope.withProperty(this, firProperty = null, f)
    }

    private fun IrType.containsErrorType(): Boolean {
        return when (this) {
            is IrErrorType -> true
            is IrSimpleType -> arguments.any { it is IrTypeProjection && it.type.containsErrorType() }
            else -> false
        }
    }

    fun IrClass.addFakeOverrides(klass: FirClass<*>, declarations: Collection<FirDeclaration>) {
        this.declarations += getFakeOverrides(
            klass,
            declarations
        )
    }

    fun IrClass.getFakeOverrides(klass: FirClass<*>, realDeclarations: Collection<FirDeclaration>): List<IrDeclaration> {
        val fakeOverrideStubs = klass.computeFakeOverrideStubs(realDeclarations, session, scopeSession)
        val result = mutableListOf<IrDeclaration>()
        val isLocal = klass !is FirRegularClass || klass.isLocal
        for (fakeOverrideStub in fakeOverrideStubs) {
            createFakeOverrideForStub(klass, irClass = this, isLocal, fakeOverrideStub, result)
        }
        return result
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun generateFakeOverridesForName(
        irClass: IrClass,
        name: Name,
        firClass: FirClass<*>
    ): List<IrDeclaration> = buildList {
        val fakeOverrideStubs = firClass.computeFakeOverrideStubsForName(name, scopeSession)
        val isLocal = firClass !is FirRegularClass || firClass.isLocal
        for (fakeOverrideStub in fakeOverrideStubs) {
            createFakeOverrideForStub(firClass, irClass, isLocal, fakeOverrideStub, this)
        }
    }

    private fun createFakeOverrideForStub(
        klass: FirClass<*>,
        irClass: IrClass,
        isLocal: Boolean,
        fakeOverrideStub: FirFakeOverrideStub,
        result: MutableList<IrDeclaration>,
    ) {
        when (fakeOverrideStub.baseSymbols.first()) {
            is FirNamedFunctionSymbol -> createFakeOverrideForStub(
                irClass, isLocal, fakeOverrideStub,
                declarationStorage::getCachedIrFunction,
                declarationStorage::createIrFunction,
                createFakeOverrideSymbol = { firFunction, callableSymbol ->
                    FirFakeOverrideGenerator.createSubstitutionOverrideFunction(
                        session, firFunction, callableSymbol,
                        newDispatchReceiverType = klass.defaultType(),
                        derivedClassId = klass.symbol.classId,
                        isExpect = (klass as? FirRegularClass)?.isExpect == true
                    )
                },
                baseFunctionSymbols,
                result,
                containsErrorTypes = { irFunction ->
                    irFunction.returnType.containsErrorType() || irFunction.valueParameters.any { it.type.containsErrorType() }
                },
            )
            is FirPropertySymbol -> createFakeOverrideForStub(
                irClass, isLocal, fakeOverrideStub,
                declarationStorage::getCachedIrProperty,
                declarationStorage::createIrProperty,
                createFakeOverrideSymbol = { firProperty, callableSymbol ->
                    FirFakeOverrideGenerator.createSubstitutionOverrideProperty(
                        session, firProperty, callableSymbol,
                        newDispatchReceiverType = klass.defaultType(),
                        derivedClassId = klass.symbol.classId,
                        isExpect = (klass as? FirRegularClass)?.isExpect == true
                    )
                },
                basePropertySymbols,
                result,
                containsErrorTypes = { irProperty ->
                    irProperty.backingField?.type?.containsErrorType() == true ||
                            irProperty.getter?.returnType?.containsErrorType() == true
                },
            )
        }
    }

    private inline fun <reified D : FirCallableMemberDeclaration<D>, reified S : FirCallableSymbol<D>, reified I : IrDeclaration> createFakeOverrideForStub(
        irClass: IrClass,
        isLocal: Boolean,
        fakeOverrideStub: FirFakeOverrideStub,
        cachedIrDeclaration: (D, (D) -> IdSignature?) -> I?,
        createIrDeclaration: (D, irParent: IrClass, thisReceiverOwner: IrClass?, origin: IrDeclarationOrigin, isLocal: Boolean) -> I,
        createFakeOverrideSymbol: (D, S) -> S,
        baseSymbols: MutableMap<I, List<S>>,
        result: MutableList<in I>,
        containsErrorTypes: (I) -> Boolean,
    ) {
        val unwrappedSymbol = fakeOverrideStub.unwrappedSymbol
        val (fakeOverrideFirDeclaration, baseFirSymbolsForFakeOverride) = when (fakeOverrideStub) {
            is FirFakeOverrideStub.FromScope -> {
                // Substitution or intersection case
                // We have already a FIR declaration for such fake override
                fakeOverrideStub.symbol.fir to fakeOverrideStub.baseSymbols
            }
            is FirFakeOverrideStub.Trivial -> {
                // Trivial fake override case
                // We've got no relevant declaration in FIR world for such a fake override in current class, thus we're creating it here
                val originalSymbol = fakeOverrideStub.baseSymbol as S
                val originalDeclaration = originalSymbol.fir
                val fakeOverrideSymbol = createFakeOverrideSymbol(originalDeclaration, unwrappedSymbol as S)
                declarationStorage.saveFakeOverrideInClass(irClass, originalDeclaration, fakeOverrideSymbol.fir)
                classifierStorage.preCacheTypeParameters(originalDeclaration)
                fakeOverrideSymbol.fir to listOf(originalSymbol)
            }
        }
        val irDeclaration = cachedIrDeclaration(fakeOverrideFirDeclaration as D) {
            // Sometimes we can have clashing here when FIR substitution/intersection override
            // have the same signature.
            // Now we avoid this problem by signature caching,
            // so both FIR overrides correspond to one IR fake override
            signatureComposer.composeSignature(fakeOverrideFirDeclaration)
        }?.takeIf { it.parent == irClass }
            ?: createIrDeclaration(
                fakeOverrideFirDeclaration,
                irClass,
                /* thisReceiverOwner = */ declarationStorage.findIrParent(unwrappedSymbol.fir) as? IrClass,
                IrDeclarationOrigin.FAKE_OVERRIDE,
                isLocal
            )
        if (containsErrorTypes(irDeclaration)) {
            return
        }
        @Suppress("UNCHECKED_CAST")
        baseSymbols[irDeclaration] = baseFirSymbolsForFakeOverride as List<S>
        result += irDeclaration
    }

    internal fun calcBaseSymbolsForFakeOverrideFunction(
        klass: FirClass<*>,
        fakeOverride: IrSimpleFunction,
        originalSymbol: FirNamedFunctionSymbol,
    ) {
        val scope = klass.unsubstitutedScope(session, scopeSession, withForcedTypeCalculator = true)
        val classLookupTag = klass.symbol.toLookupTag()
        val baseFirSymbolsForFakeOverride =
            if (originalSymbol.shouldHaveComputedBaseSymbolsForClass(classLookupTag)) {
                computeBaseSymbols(originalSymbol, FirTypeScope::getDirectOverriddenFunctions, scope, classLookupTag)
            } else {
                listOf(originalSymbol)
            }
        baseFunctionSymbols[fakeOverride] = baseFirSymbolsForFakeOverride
    }

    internal fun getOverriddenSymbolsForFakeOverride(function: IrSimpleFunction): List<IrSimpleFunctionSymbol>? {
        val baseSymbols = baseFunctionSymbols[function] ?: return null
        return getOverriddenSymbolsInSupertypes(
            function,
            baseSymbols
        ) { declarationStorage.getIrFunctionSymbol(it) as IrSimpleFunctionSymbol }
    }

    internal fun getOverriddenSymbolsInSupertypes(
        overridden: FirNamedFunctionSymbol,
        superClasses: Set<IrClass>,
    ): List<IrSimpleFunctionSymbol> {
        return getOverriddenSymbolsInSupertypes(
            overridden,
            superClasses
        ) { declarationStorage.getIrFunctionSymbol(it) as IrSimpleFunctionSymbol }
    }

    internal fun getOverriddenSymbolsInSupertypes(
        overridden: FirPropertySymbol,
        superClasses: Set<IrClass>,
    ): List<IrPropertySymbol> {
        return getOverriddenSymbolsInSupertypes(
            overridden, superClasses
        ) { declarationStorage.getIrPropertySymbol(it) as IrPropertySymbol }
    }

    private fun <I : IrDeclaration, S : IrSymbol, F : FirCallableSymbol<*>> getOverriddenSymbolsInSupertypes(
        declaration: I,
        baseSymbols: List<F>,
        irProducer: (F) -> S,
    ): List<S> {
        val irClass = declaration.parentAsClass
        val superClasses = irClass.superTypes.mapNotNull { it.classifierOrNull?.owner as? IrClass }.toSet()

        return baseSymbols.flatMap { overridden ->
            getOverriddenSymbolsInSupertypes(overridden, superClasses, irProducer)
        }.distinct()
    }

    private fun <F : FirCallableSymbol<*>, S : IrSymbol> getOverriddenSymbolsInSupertypes(
        overridden: F,
        superClasses: Set<IrClass>,
        irProducer: (F) -> S
    ): List<S> {
        val overriddenContainingClass =
            overridden.containingClass()?.toSymbol(session)?.fir as? FirClass<*> ?: return emptyList()

        val overriddenContainingIrClass =
            declarationStorage.classifierStorage.getIrClassSymbol(overriddenContainingClass.symbol).owner as? IrClass
                ?: return emptyList()

        return if (overriddenContainingIrClass in superClasses) {
            // `overridden` was a FIR declaration in some of the supertypes
            listOf(irProducer(overridden))
        } else {
            // There were no FIR declaration in supertypes, but we know that we have fake overrides in some of them
            superClasses.mapNotNull {
                declarationStorage.getFakeOverrideInClass(it, overridden.fir)?.let { fakeOverrideInClass ->
                    @Suppress("UNCHECKED_CAST")
                    irProducer(fakeOverrideInClass.symbol as F)
                }
            }.run {
                // TODO: Get rid of this hack
                //  It's only needed for built-in super classes, because they are built via descriptors and
                //  don't register fake overrides at org.jetbrains.kotlin.fir.backend.Fir2IrDeclarationStorage.fakeOverridesInClass
                if (isEmpty())
                    listOf(irProducer(overridden))
                else
                    this
            }
        }
    }

    fun bindOverriddenSymbols(declarations: List<IrDeclaration>) {
        for (declaration in declarations) {
            if (declaration.origin != IrDeclarationOrigin.FAKE_OVERRIDE) continue
            when (declaration) {
                is IrSimpleFunction -> {
                    val baseSymbols = getOverriddenSymbolsForFakeOverride(declaration)!!
                    declaration.withFunction {
                        overriddenSymbols = baseSymbols
                    }
                }
                is IrProperty -> {
                    val baseSymbols = basePropertySymbols[declaration]!!
                    declaration.withProperty {
                        discardAccessorsAccordingToBaseVisibility(baseSymbols)
                        setOverriddenSymbolsForAccessors(declarationStorage, declaration.isVar, baseSymbols)
                    }
                }
            }
        }
    }

    private fun IrProperty.discardAccessorsAccordingToBaseVisibility(baseSymbols: List<FirPropertySymbol>) {
        for (baseSymbol in baseSymbols) {
            val unwrappedSymbol = baseSymbol.unwrapFakeOverrides()
            val unwrappedProperty = unwrappedSymbol.fir
            // Do not create fake overrides for accessors if not allowed to do so, e.g., private lateinit var.
            if (!(unwrappedProperty.getter?.allowsToHaveFakeOverride ?: unwrappedProperty.allowsToHaveFakeOverride)) {
                getter = null
            }
            // or private setter
            if (!(unwrappedProperty.setter?.allowsToHaveFakeOverride ?: unwrappedProperty.allowsToHaveFakeOverride)) {
                setter = null
            }
        }
    }

    private fun IrProperty.setOverriddenSymbolsForAccessors(
        declarationStorage: Fir2IrDeclarationStorage,
        isVar: Boolean,
        firOverriddenSymbols: List<FirPropertySymbol>
    ): IrProperty {
        val overriddenIrProperties = getOverriddenSymbolsInSupertypes(this, firOverriddenSymbols) {
            declarationStorage.getIrPropertySymbol(it) as IrPropertySymbol
        }.map { it.owner }

        getter?.apply {
            overriddenSymbols = overriddenIrProperties.mapNotNull { it.getter?.symbol }
        }
        if (isVar) {
            setter?.apply {
                overriddenSymbols = overriddenIrProperties.mapNotNull { it.setter?.symbol }
            }
        }
        return this
    }
}

