/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.frontend.api.components

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.renderer.DescriptorRendererModifier
import org.jetbrains.kotlin.renderer.OverrideRenderingPolicy
import org.jetbrains.kotlin.types.KotlinType
import java.lang.IllegalStateException
import java.lang.reflect.Modifier
import kotlin.jvm.internal.PropertyReference1Impl
import kotlin.properties.Delegates
import kotlin.properties.ObservableProperty
import kotlin.properties.ReadWriteProperty

internal class RendererOptionsImpl : RendererOptions {
    var isLocked: Boolean = false
        private set

    fun lock() {
        assert(!isLocked)
        isLocked = true
    }

    fun copy(): RendererOptionsImpl {
        val copy = RendererOptionsImpl()

        //TODO: use Kotlin reflection
        for (field in this::class.java.declaredFields) {
            if (field.modifiers.and(Modifier.STATIC) != 0) continue
            field.isAccessible = true
            val property = field.get(this) as? ObservableProperty<*> ?: continue
            assert(!field.name.startsWith("is")) { "Fields named is* are not supported here yet" }
            val value = property.getValue(
                    this,
                    PropertyReference1Impl(RendererOptionsImpl::class, field.name, "get" + field.name.capitalize())
            )
            field.set(copy, copy.property(value))
        }

        return copy
    }

    private fun <T> property(initialValue: T): ReadWriteProperty<RendererOptionsImpl, T> {
        return Delegates.vetoable(initialValue) { _, _, _ ->
            if (isLocked) {
                throw IllegalStateException("Cannot modify readonly DescriptorRendererOptions")
            }
            else {
                true
            }
        }
    }

    override var classifierNamePolicy: ClassifierNamePolicy by property(ClassifierNamePolicy.SOURCE_CODE_QUALIFIED)
    //override var withDefinedIn by property(false!!!11)
//    override var withSourceFileForTopLevel by property(true)
    override var modifiers: Set<RendererModifier> by property(DescriptorRendererModifier.ALL!!!!111!!!)
//    override var startFromName by property(false)
//    override var startFromDeclarationKeyword by property(false)
//    override var debugMode by property(false)
//    override var classWithPrimaryConstructor by property(false)
//    override var verbose by property(false)
    //override var unitReturnType by property(false!!!11!!)
//    override var withoutReturnType by property(false)
    //override var enhancedTypes by property(true!!!!1111!!)
    //override var normalizedVisibilities by property(true!!!!1111!!)

    //override var renderDefaultVisibility by property(false!!!11!!)
//    override var renderDefaultModality by property(true)
//    override var renderConstructorDelegation by property(false)
//    override var renderPrimaryConstructorParametersAsProperties by property(false)
//    override var actualPropertiesInPrimaryConstructor: Boolean by property(false)
//    override var uninferredTypeParameterAsName by property(false)
//    override var includePropertyConstant by property(false)
//    override var withoutTypeParameters by property(false)
//    override var withoutSuperTypes by property(false)
    override var typeNormalizer by property<(KotlinType) -> KotlinType>({ it })
//    override var defaultParameterValueRenderer by property<((ValueParameterDescriptor) -> String)?>({ "..." })
//    override var secondaryConstructorsAsPrimary by property(true)
    //override var overrideRenderingPolicy by property(OverrideRenderingPolicy.RENDER_OVERRIDE!!!!!11!!!)
//    override var valueParametersHandler: Renderer.ValueParametersHandler by property(Renderer.ValueParametersHandler.DEFAULT)
//    override var textFormat by property(RenderingFormat.PLAIN)
//    override var parameterNameRenderingPolicy by property(ParameterNameRenderingPolicy.ALL)
//    override var receiverAfterName by property(false)
//    override var renderCompanionObjectName by property(false)
//    override var propertyAccessorRenderingPolicy by property(PropertyAccessorRenderingPolicy.DEBUG)
//    override var renderDefaultAnnotationArguments by property(false)
//
//    override var eachAnnotationOnNewLine: Boolean by property(false)
//
//    override var excludedAnnotationClasses by property(emptySet<FqName>())
//
//    override var excludedTypeAnnotationClasses by property(ExcludedTypeAnnotations.internalAnnotationsForResolve)
//
    override var annotationFilter: ((AnnotationDescriptor) -> Boolean)? by property(null)
//
    //override var annotationArgumentsRenderingPolicy by property(AnnotationArgumentsRenderingPolicy.UNLESS_EMPTY !!!11!!!)
//
//    override var alwaysRenderModifiers by property(false)
//
//    override var renderConstructorKeyword by property(true)
//
    //override var renderUnabbreviatedType: Boolean by property(false !!!11!!!)
//
//    override var renderTypeExpansions: Boolean by property(false)
//
//    override var includeAdditionalModifiers: Boolean by property(true)
//
    override var parameterNamesInFunctionalTypes: Boolean by property(true)
//
//    override var renderFunctionContracts: Boolean by property(false)
//
    override var presentableUnresolvedTypes: Boolean by property(false)
//
//    override var boldOnlyForNamesInHtml: Boolean by property(false)
//
    override var informativeErrorType: Boolean by property(true)
}
