/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.components

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtAnnotationCall
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.renderer.DescriptorRendererModifier
import org.jetbrains.kotlin.renderer.OverrideRenderingPolicy
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.TypeProjection

abstract class Renderer {
    fun withOptions(changeOptions: RendererOptions.() -> Unit): Renderer {
        val options = (this as RendererImpl).options.copy()
        options.changeOptions()
        options.lock()
        return RendererImpl(options)
    }

    abstract fun renderMessage(message: String): String

    abstract fun renderType(type: KotlinType): String

    abstract fun renderFlexibleType(lowerRendered: String, upperRendered: String, builtIns: KotlinBuiltIns): String

    abstract fun renderTypeArguments(typeArguments: List<TypeProjection>): String

    abstract fun renderTypeProjection(typeProjection: TypeProjection): String

    abstract fun renderTypeConstructor(typeConstructor: TypeConstructor): String

    abstract fun renderClassifierName(klass: ClassifierDescriptor): String

    abstract fun renderAnnotation(annotation: FirAnnotationCall, target: AnnotationUseSiteTarget? = null): String

    abstract fun render(declarationDescriptor: DeclarationDescriptor): String

    abstract fun renderValueParameters(parameters: Collection<ValueParameterDescriptor>, synthesizedParameterNames: Boolean): String

    fun renderFunctionParameters(functionDescriptor: FunctionDescriptor): String =
        renderValueParameters(functionDescriptor.valueParameters, functionDescriptor.hasSynthesizedParameterNames())

    abstract fun renderName(name: Name, rootRenderedElement: Boolean): String

    abstract fun renderFqName(fqName: FqNameUnsafe): String

    interface ValueParametersHandler {
        fun appendBeforeValueParameters(parameterCount: Int, builder: StringBuilder)
        fun appendAfterValueParameters(parameterCount: Int, builder: StringBuilder)

        fun appendBeforeValueParameter(
            parameter: ValueParameterDescriptor,
            parameterIndex: Int,
            parameterCount: Int,
            builder: StringBuilder
        )

        fun appendAfterValueParameter(parameter: ValueParameterDescriptor, parameterIndex: Int, parameterCount: Int, builder: StringBuilder)

        object DEFAULT : ValueParametersHandler {
            override fun appendBeforeValueParameters(parameterCount: Int, builder: StringBuilder) {
                builder.append("(")
            }

            override fun appendAfterValueParameters(parameterCount: Int, builder: StringBuilder) {
                builder.append(")")
            }

            override fun appendBeforeValueParameter(
                parameter: ValueParameterDescriptor,
                parameterIndex: Int,
                parameterCount: Int,
                builder: StringBuilder
            ) {
            }

            override fun appendAfterValueParameter(
                parameter: ValueParameterDescriptor,
                parameterIndex: Int,
                parameterCount: Int,
                builder: StringBuilder
            ) {
                if (parameterIndex != parameterCount - 1) {
                    builder.append(", ")
                }
            }
        }
    }

    companion object {
        fun withOptions(changeOptions: RendererOptions.() -> Unit): Renderer {
            val options = RendererOptionsImpl()
            options.changeOptions()
            options.lock()
            return RendererImpl(options)
        }

        @JvmField
        val COMPACT_WITH_MODIFIERS: Renderer = withOptions {
            withDefinedIn = false
        }

        @JvmField
        val COMPACT: Renderer = withOptions {
            withDefinedIn = false
            modifiers = emptySet()
        }

        @JvmField
        val COMPACT_WITHOUT_SUPERTYPES: Renderer = withOptions {
            withDefinedIn = false
            modifiers = emptySet()
            withoutSuperTypes = true
        }

        @JvmField
        val COMPACT_WITH_SHORT_TYPES: Renderer = withOptions {
            modifiers = emptySet()
            classifierNamePolicy = ClassifierNamePolicy.SHORT
            parameterNameRenderingPolicy = ParameterNameRenderingPolicy.ONLY_NON_SYNTHESIZED
        }

        @JvmField
        val ONLY_NAMES_WITH_SHORT_TYPES: Renderer = withOptions {
            withDefinedIn = false
            modifiers = emptySet()
            classifierNamePolicy = ClassifierNamePolicy.SHORT
            withoutTypeParameters = true
            parameterNameRenderingPolicy = ParameterNameRenderingPolicy.NONE
            receiverAfterName = true
            renderCompanionObjectName = true
            withoutSuperTypes = true
            startFromName = true
        }

        @JvmField
        val FQ_NAMES_IN_TYPES: Renderer = withOptions {
            modifiers = RendererModifier.ALL_EXCEPT_ANNOTATIONS
        }

        @JvmField
        val FQ_NAMES_IN_TYPES_WITH_ANNOTATIONS: Renderer = withOptions {
            modifiers = RendererModifier.ALL
        }

        @JvmField
        val SHORT_NAMES_IN_TYPES: Renderer = withOptions {
            classifierNamePolicy = ClassifierNamePolicy.SHORT
            parameterNameRenderingPolicy = ParameterNameRenderingPolicy.ONLY_NON_SYNTHESIZED
        }

        @JvmField
        val DEBUG_TEXT: Renderer = withOptions {
            debugMode = true
            classifierNamePolicy = ClassifierNamePolicy.FULLY_QUALIFIED
            modifiers = RendererModifier.ALL
        }

        @JvmField
        val HTML: Renderer = withOptions {
            textFormat = RenderingFormat.HTML
            modifiers = RendererModifier.ALL
        }


    }
}

enum class AnnotationArgumentsRenderingPolicy(
    val includeAnnotationArguments: Boolean = false,
    //val includeEmptyAnnotationArguments: Boolean = false
) {
    UNLESS_EMPTY(true)
}

interface RendererOptions {
    var classifierNamePolicy: ClassifierNamePolicy
    var withDefinedIn: Boolean
    var modifiers: Set<RendererModifier>
    var unitReturnType: Boolean
    var enhancedTypes: Boolean
    var normalizedVisibilities: Boolean
    var renderDefaultVisibility: Boolean
    var typeNormalizer: (KotlinType) -> KotlinType
    var overrideRenderingPolicy: OverrideRenderingPolicy
    var annotationFilter: ((FirAnnotationCall) -> Boolean)?
    var annotationArgumentsRenderingPolicy: AnnotationArgumentsRenderingPolicy
    var renderUnabbreviatedType: Boolean
    var parameterNamesInFunctionalTypes: Boolean
    var presentableUnresolvedTypes: Boolean
    var informativeErrorType: Boolean
}

object ExcludedTypeAnnotations {
    val internalAnnotationsForResolve = setOf(
        FqName("kotlin.internal.NoInfer"),
        FqName("kotlin.internal.Exact")
    )
}

enum class RenderingFormat {
    PLAIN {
        override fun escape(string: String) = string
    },
    HTML {
        override fun escape(string: String) = string.replace("<", "&lt;").replace(">", "&gt;")
    };

    abstract fun escape(string: String): String
}

enum class OverrideRenderingPolicy {
    RENDER_OVERRIDE,
    RENDER_OPEN,
    RENDER_OPEN_OVERRIDE
}

enum class ParameterNameRenderingPolicy {
    ALL,
    ONLY_NON_SYNTHESIZED,
    NONE
}

enum class PropertyAccessorRenderingPolicy {
    PRETTY,
    DEBUG,
    NONE
}

enum class RendererModifier(val includeByDefault: Boolean) {
    VISIBILITY(true),
    MODALITY(true),
    OVERRIDE(true),
    ANNOTATIONS(false),
    INNER(true),
    MEMBER_KIND(true),
    DATA(true),
    INLINE(true),
    EXPECT(true),
    ACTUAL(true),
    CONST(true),
    LATEINIT(true),
    FUN(true),
    VALUE(true)
    ;

    companion object {
        @JvmField
        val ALL_EXCEPT_ANNOTATIONS = values().filter { it.includeByDefault }.toSet()

        @JvmField
        val ALL = values().toSet()
    }
}
