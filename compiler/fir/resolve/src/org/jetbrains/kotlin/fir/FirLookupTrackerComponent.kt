/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import org.jetbrains.kotlin.fir.resolve.calls.CallInfo
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.SmartList

abstract class FirLookupTrackerComponent : FirSessionComponent {

    abstract fun recordLookup(name: Name, source: FirSourceElement?, fileSource: FirSourceElement?, inScopes: List<String>)

    fun recordLookup(name: Name, source: FirSourceElement?, fileSource: FirSourceElement?, inScope: String) {
        recordLookup(name, source, fileSource, SmartList(inScope))
    }

    fun recordLookup(callInfo: CallInfo, type: ConeKotlinType) {
        if (type.classId?.isLocal == true) return
        val scopes = SmartList(type.render().replace('/', '.'))
        if (type.classId?.shortClassName?.asString() == "Companion") {
            scopes.add(type.classId!!.outerClassId!!.asString().replace('/', '.'))
        }
        recordLookup(callInfo.name, callInfo.callSite.source, callInfo.containingFile.source, scopes)
    }

    fun recordLookup(callInfo: CallInfo, scopes: List<String>) {
        recordLookup(callInfo.name, callInfo.callSite.source, callInfo.containingFile.source, scopes)
    }

    fun recordLookup(typeRef: FirTypeRef, fileSource: FirSourceElement?, scopes: List<String>) {
        if (typeRef is FirUserTypeRef) recordLookup(typeRef.qualifier.first().name, typeRef.source, fileSource, scopes)
    }

    fun recordTypeResolve(typeRef: FirTypeRef, source: FirSourceElement?, fileSource: FirSourceElement?) {
        if (typeRef !is FirResolvedTypeRef) return // TODO: check if this is the correct behavior
        if (source == null && fileSource == null) return // TODO: investigate all cases

        fun recordIfValid(type: ConeKotlinType) {
            if (type is ConeKotlinErrorType) return // TODO: investigate whether some cases should be recorded, e.g. unresolved
            type.classId?.let {
                if (!it.isLocal) {
                    if (it.shortClassName.asString() != "Companion") {
                        recordLookup(it.shortClassName, source, fileSource, it.packageFqName.asString())
                    } else {
                        recordLookup(it.outerClassId!!.shortClassName, source, fileSource, it.outerClassId!!.packageFqName.asString())
                    }
                }
            }
            type.typeArguments.forEach {
                if (it is ConeKotlinType) recordIfValid(it)
            }
        }

        recordIfValid(typeRef.type)
    }

    abstract fun flushLookups()
}

val Iterable<FirScope>.scopeLookupNames: Array<String>
    get() {
        val scopesLookupNames = ArrayList<String>()
        for (scope in this) {
            scopesLookupNames.addAll(scope.scopeLookupNames)
        }
        return scopesLookupNames.toTypedArray()
    }

val FirSession.lookupTracker: FirLookupTrackerComponent? by FirSession.nullableSessionComponentAccessor()
