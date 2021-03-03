/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.components

import org.jetbrains.kotlin.builtins.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.argumentMapping
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.renderer.render
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.TypeUtils.CANT_INFER_FUNCTION_PARAM_TYPE
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import java.util.*

internal class RendererImpl(
    val options: RendererOptionsImpl
) : Renderer(), RendererOptions by options/* this gives access to options without qualifier */ {
    init {
        assert(options.isLocked)
    }

    private val functionTypeAnnotationsRenderer: RendererImpl by lazy {
        withOptions {
            ExcludedTypeAnnotations.internalAnnotationsForResolve += listOf(StandardNames.FqNames.extensionFunctionType)
        } as RendererImpl
    }

    /* FORMATTING */
    private fun renderKeyword(keyword: String): String = keyword

    private fun renderError(keyword: String): String = keyword

    private fun escape(string: String) = string

    private fun lt() = escape("<")
    private fun gt() = escape(">")

    private fun arrow(): String = escape("->")

    override fun renderMessage(message: String): String = message

    /* NAMES RENDERING */
    override fun renderName(name: Name, rootRenderedElement: Boolean): String = escape(name.render())

    private fun renderName(descriptor: FirDeclaration, builder: StringBuilder, rootRenderedElement: Boolean) {
        val name = when(descriptor) {
            is FirRegularClass -> descriptor.name
            is FirSimpleFunction -> descriptor.name
            is FirProperty -> descriptor.name
            is FirValueParameter -> descriptor.name
            else -> Name.special("INVALID NAME") //TODO
        }
        builder.append(renderName(name, rootRenderedElement))
    }

    private fun renderCompanionObjectName(descriptor: FirRegularClass, builder: StringBuilder) {
        if (descriptor.name != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
            renderSpaceIfNeeded(builder)
            builder.append(renderName(descriptor.name, true))
        }
    }

    override fun renderFqName(fqName: FqNameUnsafe) = renderFqName(fqName.pathSegments())

    private fun renderFqName(pathSegments: List<Name>) = escape(org.jetbrains.kotlin.renderer.renderFqName(pathSegments))

    override fun renderClassifierName(klass: ClassifierDescriptor): String = if (ErrorUtils.isError(klass)) {
        klass.typeConstructor.toString()
    } else
        classifierNamePolicy.renderClassifier(klass, this)

    /* TYPES RENDERING */
    override fun renderType(type: KotlinType): String = buildString {
        renderNormalizedType(typeNormalizer(type))
    }

    override fun renderType(type: KtType): String = buildString {
        renderNormalizedType(typeNormalizer(type))
    }

    override fun renderType(type: FirTypeRef): String = buildString {
        renderNormalizedType(typeNormalizer(type))
    }

    override fun renderType(type: ConeClassLikeType): String = buildString {
        renderNormalizedType(typeNormalizer(type))
    }

    private fun StringBuilder.renderNormalizedType(type: KotlinType) {
        val abbreviated = type.unwrap() as? AbbreviatedType
        if (abbreviated != null) {
            // TODO nullability is lost for abbreviated type?
            renderNormalizedTypeAsIs(abbreviated.abbreviation)
            if (renderUnabbreviatedType) {
                renderAbbreviatedTypeExpansion(abbreviated)
            }
            return
        }

        renderNormalizedTypeAsIs(type)
    }

    private fun StringBuilder.renderAbbreviatedTypeExpansion(abbreviated: AbbreviatedType) {
        append(" /* = ")
        renderNormalizedTypeAsIs(abbreviated.expandedType)
        append(" */")
    }

    private fun StringBuilder.renderNormalizedTypeAsIs(type: KotlinType) {
        when (val unwrappedType = type.unwrap()) {
            is FlexibleType -> append(unwrappedType.render(this@RendererImpl, this@RendererImpl))
            is SimpleType -> renderSimpleType(unwrappedType)
        }
    }

    private fun StringBuilder.renderSimpleType(type: SimpleType) {
        if (type == CANT_INFER_FUNCTION_PARAM_TYPE || TypeUtils.isDontCarePlaceholder(type)) {
            append("???")
            return
        }
        if (ErrorUtils.isUninferredParameter(type)) {
            append("???")
            return
        }

        if (type.isError) {
            renderDefaultType(type)
            return
        }
        if (shouldRenderAsPrettyFunctionType(type)) {
            renderFunctionType(type)
        } else {
            renderDefaultType(type)
        }
    }

    private fun shouldRenderAsPrettyFunctionType(type: FirTypeRef): Boolean {
        return false //TODO
        //return type.isBuiltinFunctionalType && type.arguments.none { it.isStarProjection }
    }

    override fun renderFlexibleType(lowerRendered: String, upperRendered: String, builtIns: KotlinBuiltIns): String {
        if (differsOnlyInNullability(lowerRendered, upperRendered)) {
            if (upperRendered.startsWith("(")) {
                // the case of complex type, e.g. (() -> Unit)?
                return "($lowerRendered)!"
            }
            return "$lowerRendered!"
        }

        val kotlinCollectionsPrefix = classifierNamePolicy.renderClassifier(builtIns.collection, this).substringBefore("Collection")
        val mutablePrefix = "Mutable"
        // java.util.List<Foo> -> (Mutable)List<Foo!>!
        val simpleCollection = replacePrefixes(
            lowerRendered,
            kotlinCollectionsPrefix + mutablePrefix,
            upperRendered,
            kotlinCollectionsPrefix,
            "$kotlinCollectionsPrefix($mutablePrefix)"
        )
        if (simpleCollection != null) return simpleCollection
        // java.util.Map.Entry<Foo, Bar> -> (Mutable)Map.(Mutable)Entry<Foo!, Bar!>!
        val mutableEntry = replacePrefixes(
            lowerRendered,
            kotlinCollectionsPrefix + "MutableMap.MutableEntry",
            upperRendered,
            kotlinCollectionsPrefix + "Map.Entry",
            "$kotlinCollectionsPrefix(Mutable)Map.(Mutable)Entry"
        )
        if (mutableEntry != null) return mutableEntry

        val kotlinPrefix = classifierNamePolicy.renderClassifier(builtIns.array, this).substringBefore("Array")
        // Foo[] -> Array<(out) Foo!>!
        val array = replacePrefixes(
            lowerRendered,
            kotlinPrefix + escape("Array<"),
            upperRendered,
            kotlinPrefix + escape("Array<out "),
            kotlinPrefix + escape("Array<(out) ")
        )
        if (array != null) return array

        return "($lowerRendered..$upperRendered)"
    }

    override fun renderTypeArguments(typeArguments: List<TypeProjection>): String = if (typeArguments.isEmpty()) ""
    else buildString {
        append(lt())
        this.appendTypeProjections(typeArguments)
        append(gt())
    }

    private fun StringBuilder.renderDefaultType(type: KotlinType) {
        this.renderAnnotations(type)

        if (type.isError) {
            if (type is UnresolvedType && presentableUnresolvedTypes) {
                append(type.presentableName)
            } else {
                if (type is ErrorType && !informativeErrorType) {
                    append(type.presentableName)
                } else {
                    append(type.constructor.toString()) // Debug name of an error type is more informative
                }
            }
            append(renderTypeArguments(type.arguments))
        } else {
            renderTypeConstructorAndArguments(type)
        }

        if (type.isMarkedNullable) {
            append("?")
        }

        if (type.isDefinitelyNotNullType) {
            append("!!")
        }
    }

    private fun StringBuilder.renderTypeConstructorAndArguments(
        type: KotlinType,
        typeConstructor: TypeConstructor = type.constructor
    ) {
        val possiblyInnerType = type.buildPossiblyInnerType()
        if (possiblyInnerType == null) {
            append(renderTypeConstructor(typeConstructor))
            append(renderTypeArguments(type.arguments))
            return
        }

        renderPossiblyInnerType(possiblyInnerType)
    }

    private fun StringBuilder.renderPossiblyInnerType(possiblyInnerType: PossiblyInnerType) {
        possiblyInnerType.outerType?.let {
            renderPossiblyInnerType(it)
            append('.')
            append(renderName(possiblyInnerType.classifierDescriptor.name, false))
        } ?: append(renderTypeConstructor(possiblyInnerType.classifierDescriptor.typeConstructor))

        append(renderTypeArguments(possiblyInnerType.arguments))
    }

    override fun renderTypeConstructor(typeConstructor: TypeConstructor): String = when (val cd = typeConstructor.declarationDescriptor) {
        is TypeParameterDescriptor, is ClassDescriptor, is TypeAliasDescriptor -> renderClassifierName(cd)
        null -> typeConstructor.toString()
        else -> error("Unexpected classifier: " + cd::class.java)
    }

    override fun renderTypeProjection(typeProjection: TypeProjection) = buildString {
        appendTypeProjections(listOf(typeProjection))
    }

    private fun StringBuilder.appendTypeProjections(typeProjections: List<TypeProjection>) {
        typeProjections.joinTo(this, ", ") {
            if (it.isStarProjection) {
                "*"
            } else {
                val type = renderType(it.type)
                if (it.projectionKind == Variance.INVARIANT) type else "${it.projectionKind} $type"
            }
        }
    }

    private fun StringBuilder.renderFunctionType(type: KotlinType) {
        val lengthBefore = length
        // we need special renderer to skip @ExtensionFunctionType
        with(functionTypeAnnotationsRenderer) {
            renderAnnotations(type)
        }
        val hasAnnotations = length != lengthBefore

        val isSuspend = type.isSuspendFunctionType
        val isNullable = type.isMarkedNullable
        val receiverType = type.getReceiverTypeFromFunctionType()

        val needParenthesis = isNullable || (hasAnnotations && receiverType != null)
        if (needParenthesis) {
            if (isSuspend) {
                insert(lengthBefore, '(')
            } else {
                if (hasAnnotations) {
                    assert(last() == ' ')
                    if (get(lastIndex - 1) != ')') {
                        // last annotation rendered without parenthesis - need to add them otherwise parsing will be incorrect
                        insert(lastIndex, "()")
                    }
                }

                append("(")
            }
        }

        renderModifier(this, isSuspend, "suspend")

        if (receiverType != null) {
            val surroundReceiver = shouldRenderAsPrettyFunctionType(receiverType) && !receiverType.isMarkedNullable ||
                    receiverType.hasModifiersOrAnnotations()
            if (surroundReceiver) {
                append("(")
            }
            renderNormalizedType(receiverType)
            if (surroundReceiver) {
                append(")")
            }
            append(".")
        }

        append("(")

        val parameterTypes = type.getValueParameterTypesFromFunctionType()
        for ((index, typeProjection) in parameterTypes.withIndex()) {
            if (index > 0) append(", ")

            val name = if (parameterNamesInFunctionalTypes) typeProjection.type.extractParameterNameFromFunctionTypeArgument() else null
            if (name != null) {
                append(renderName(name, false))
                append(": ")
            }

            append(renderTypeProjection(typeProjection))
        }

        append(") ").append(arrow()).append(" ")
        renderNormalizedType(type.getReturnTypeFromFunctionType())

        if (needParenthesis) append(")")

        if (isNullable) append("?")
    }

    private fun KotlinType.hasModifiersOrAnnotations() =
        isSuspendFunctionType || !annotations.isEmpty()

    /* METHODS FOR ALL KINDS OF DESCRIPTORS */
    private fun StringBuilder.renderAnnotations(annotated: FirAnnotatedDeclaration, target: AnnotationUseSiteTarget? = null) {
        if (RendererModifier.ANNOTATIONS !in modifiers) return

        //TODO
        //val excluded = if (annotated is KotlinType) ExcludedTypeAnnotations.internalAnnotationsForResolve else emptySet<FqName>()
        val excluded = emptySet<FqName>()

        val annotationFilter = annotationFilter
        for (annotation in annotated.annotations) {
            if (annotation.toAnnotationClassId().asSingleFqName() !in excluded
                && !annotation.isParameterName()
                && (annotationFilter == null || annotationFilter(annotation))
            ) {
                append(renderAnnotation(annotation, target))
                append(" ")
            }
        }
    }

    private fun FirAnnotationCall.isParameterName(): Boolean {
        return toAnnotationClassId().asSingleFqName() == StandardNames.FqNames.parameterName
    }

    override fun renderAnnotation(annotation: FirAnnotationCall, target: AnnotationUseSiteTarget?): String {
        return buildString {
            append('@')
            if (target != null) {
                append(target.renderName + ":")
            }
            val annotationType = annotation.annotationTypeRef
            append(renderType(annotationType))

            val arguments = renderAndSortAnnotationArguments(annotation)
            if (arguments.isNotEmpty()) {
                arguments.joinTo(this, ", ", "(", ")")
            }
        }
    }

    private fun renderAndSortAnnotationArguments(descriptor: FirAnnotationCall): List<String> {
        val argumentList = descriptor.argumentMapping?.entries?.map { (name, value) ->
                "$name = ${renderConstant(value)}"
            }
        return argumentList?.sorted() ?: emptyList()
    }

    private fun renderConstant(value: FirValueParameter): String {
        return when (value) {
            is FirConstExpression<*> -> value.toString()
            else -> "RENDER ERROR"
        }
    }
    
    private fun renderVisibility(visibility: Visibility, builder: StringBuilder): Boolean {
        @Suppress("NAME_SHADOWING")
        var visibility = visibility
        if (RendererModifier.VISIBILITY !in modifiers) return false
        if (normalizedVisibilities) {
            visibility = visibility.normalize()
        }
        if (!renderDefaultVisibility && visibility == DescriptorVisibilities.DEFAULT_VISIBILITY) return false
        builder.append(renderKeyword(visibility.internalDisplayName)).append(" ")
        return true
    }

    private fun renderModality(modality: Modality, builder: StringBuilder, defaultModality: Modality) {
        renderModifier(builder, RendererModifier.MODALITY in modifiers, modality.name.toLowerCaseAsciiOnly())
    }

    private fun FirMemberDeclaration.implicitModalityWithoutExtensions(containingDeclaration: FirDeclaration?): Modality {
        if (this is FirRegularClass) {
            return if (classKind == ClassKind.INTERFACE) Modality.ABSTRACT else Modality.FINAL
        }
        val containingClassDescriptor = containingDeclaration as? FirRegularClass ?: return Modality.FINAL
        if (this !is FirCallableMemberDeclaration<*>) return Modality.FINAL
        if (overridesSomething(this)) {
            if (containingClassDescriptor.modality != Modality.FINAL) return Modality.OPEN
        }
        return if (containingClassDescriptor.classKind == ClassKind.INTERFACE && this.visibility != DescriptorVisibilities.PRIVATE) {
            if (this.modality == Modality.ABSTRACT) Modality.ABSTRACT else Modality.OPEN
        } else
            Modality.FINAL
    }

    private fun renderModalityForCallable(callable: FirCallableMemberDeclaration<*>, containingDeclaration: FirDeclaration?, builder: StringBuilder) {
        val modality = callable.modality ?: return
        val isTopLevel = containingDeclaration == null
        if (!isTopLevel || modality != Modality.FINAL) {
            if (modality == Modality.OPEN && overridesSomething(callable)) {
                return
            }
            renderModality(modality, builder, callable.implicitModalityWithoutExtensions(containingDeclaration))
        }
    }

    private fun renderOverride(callableMember: FirCallableMemberDeclaration<*>, builder: StringBuilder) {
        if (RendererModifier.OVERRIDE !in modifiers) return
        if (overridesSomething(callableMember)) {
            renderModifier(builder, true, "override")
        }
    }

    private fun renderModifier(builder: StringBuilder, value: Boolean, modifier: String) {
        if (value) {
            builder.append(renderKeyword(modifier))
            builder.append(" ")
        }
    }

    private fun renderMemberModifiers(descriptor: FirMemberDeclaration, builder: StringBuilder) {
        renderModifier(builder, descriptor.isExternal, "external")
        renderModifier(builder, RendererModifier.EXPECT in modifiers && descriptor.isExpect, "expect")
        renderModifier(builder, RendererModifier.ACTUAL in modifiers && descriptor.isActual, "actual")
    }

    private fun renderAdditionalModifiers(functionDescriptor: FirMemberDeclaration, builder: StringBuilder) {
        val isOperator =
            functionDescriptor.isOperator// && functionDescriptor.overriddenDescriptors.none { it.isOperator } //TODO
        val isInfix =
            functionDescriptor.isInfix// && functionDescriptor.overriddenDescriptors.none { it.isInfix } //TODO

        renderModifier(builder, functionDescriptor.isTailRec, "tailrec")
        renderSuspendModifier(functionDescriptor, builder)
        renderModifier(builder, functionDescriptor.isInline, "inline")
        renderModifier(builder, isInfix, "infix")
        renderModifier(builder, isOperator, "operator")
    }

    private fun renderSuspendModifier(functionDescriptor: FirMemberDeclaration, builder: StringBuilder) {
        renderModifier(builder, functionDescriptor.isSuspend, "suspend")
    }

    fun render(declarationDescriptor: FirDeclaration, containingDeclaration: FirDeclaration?): String {
        return buildString {
            declarationDescriptor.accept(RenderDeclarationDescriptorVisitor(containingDeclaration), this)
        }
    }

    /* TYPE PARAMETERS */
    private fun renderTypeParameter(typeParameter: FirTypeParameter, builder: StringBuilder, topLevel: Boolean) {
        if (topLevel) {
            builder.append(lt())
        }

        renderModifier(builder, typeParameter.isReified, "reified")
        val variance = typeParameter.variance.label
        renderModifier(builder, variance.isNotEmpty(), variance)

        builder.renderAnnotations(typeParameter)

        renderName(typeParameter, builder, topLevel)
        val upperBoundsCount = typeParameter.bounds.size
        if ((upperBoundsCount > 1 && !topLevel) || upperBoundsCount == 1) {
            val upperBound = typeParameter.bounds.iterator().next()
            if (!upperBound.isNullableAny) {
                builder.append(" : ").append(renderType(upperBound))
            }
        } else if (topLevel) {
            var first = true
            for (upperBound in typeParameter.bounds) {
                if (upperBound.isNullableAny) {
                    continue
                }
                if (first) {
                    builder.append(" : ")
                } else {
                    builder.append(" & ")
                }
                builder.append(renderType(upperBound))
                first = false
            }
        } else {
            // rendered with "where"
        }

        if (topLevel) {
            builder.append(gt())
        }
    }

    private fun renderTypeParameterRefs(typeParameters: List<FirTypeParameterRef>, builder: StringBuilder, withSpace: Boolean) =
        renderTypeParameters(typeParameters.map { it.symbol.fir }, builder, withSpace)

    private fun renderTypeParameters(typeParameters: List<FirTypeParameter>, builder: StringBuilder, withSpace: Boolean) {
        if (typeParameters.isNotEmpty()) {
            builder.append(lt())
            renderTypeParameterList(builder, typeParameters)
            builder.append(gt())
            if (withSpace) {
                builder.append(" ")
            }
        }
    }

    private fun renderTypeParameterList(builder: StringBuilder, typeParameters: List<FirTypeParameter>) {
        val iterator = typeParameters.iterator()
        while (iterator.hasNext()) {
            val typeParameterDescriptor = iterator.next()
            renderTypeParameter(typeParameterDescriptor, builder, false)
            if (iterator.hasNext()) {
                builder.append(", ")
            }
        }
    }

    private fun renderAccessor(accessor: FirPropertyAccessor, containingDeclaration: FirDeclaration?, builder: StringBuilder) {
        builder.renderAnnotations(accessor)
        renderVisibility(accessor.visibility, builder)

        renderModalityForCallable(accessor, containingDeclaration, builder)

        renderMemberModifiers(accessor, builder)

        renderAdditionalModifiers(accessor, builder)

        val keyword = if (accessor.isGetter) "get" else "set"

        builder.append(renderKeyword(keyword)).append(" ")

//        renderValueParameters(function.valueParameters, function.hasSynthesizedParameterNames(), builder)
    }

    /* FUNCTIONS */
    private fun renderFunction(function: FirSimpleFunction, containingDeclaration: FirDeclaration?, builder: StringBuilder) {
        builder.renderAnnotations(function)
        renderVisibility(function.visibility, builder)

        renderModalityForCallable(function, containingDeclaration, builder)

        renderMemberModifiers(function, builder)

        renderOverride(function, builder)

        renderAdditionalModifiers(function, builder)

        builder.append(renderKeyword("fun")).append(" ")
        renderTypeParameters(function.typeParameters, builder, true)
        renderReceiver(function, builder)

        renderName(function, builder, true)

        renderValueParameters(function.valueParameters, false, builder)

        val returnType = function.returnTypeRef
        if (unitReturnType || (!returnType.isUnit)) {
            builder.append(": ").append(renderType(returnType))
        }

        renderWhereSuffix(function.typeParameters, builder)
    }

    private fun renderReceiver(callableDescriptor: FirCallableDeclaration<*>, builder: StringBuilder) {
        val receiverType = callableDescriptor.receiverTypeRef
        if (receiverType != null) {
            builder.renderAnnotations(callableDescriptor, AnnotationUseSiteTarget.RECEIVER)

            var result = renderType(receiverType)
            if (shouldRenderAsPrettyFunctionType(receiverType) && receiverType.isMarkedNullable == true) {
                result = "($result)"
            }
            builder.append(result).append(".")
        }
    }

    private fun renderConstructor(constructor: FirConstructor, containingClass: FirRegularClass, builder: StringBuilder) {
        builder.renderAnnotations(constructor)
//        val visibilityRendered = (options.renderDefaultVisibility || constructor.constructedClass.modality != Modality.SEALED)
//                && renderVisibility(constructor.visibility, builder)

        builder.append(renderKeyword("constructor"))
        builder.append(" ")
        renderName(containingClass, builder, true)
        renderTypeParameterRefs(constructor.typeParameters, builder, false)

        renderValueParameters(constructor.valueParameters, true, builder)

        renderWhereSuffix(constructor.typeParameters, builder)
    }

    private fun renderWhereSuffix(typeParameters: List<FirTypeParameterRef>, builder: StringBuilder) {

        val upperBoundStrings = ArrayList<String>(0)

        for (typeParameter in typeParameters) {
            val typeParameterFir = typeParameter.symbol.fir
            typeParameterFir.bounds
                .drop(1) // first parameter is rendered by renderTypeParameter
                .mapTo(upperBoundStrings) { renderName(typeParameterFir.name, false) + " : " + renderType(it) }
        }

        if (upperBoundStrings.isNotEmpty()) {
            builder.append(" ").append(renderKeyword("where")).append(" ")
            upperBoundStrings.joinTo(builder, ", ")
        }
    }

    private fun renderValueParameters(
        parameters: List<FirValueParameter>,
        isInPrimaryConstructor: Boolean,
        builder: StringBuilder
    ) {
        val parameterCount = parameters.size

        builder.append("(")
        for ((index, parameter) in parameters.withIndex()) {
            renderValueParameter(parameter, isInPrimaryConstructor, false, builder)
            if (index != parameterCount - 1) {
                builder.append(", ")
            }
        }
        builder.append(")")
    }

    /* VARIABLES */
    private fun renderValueParameter(
        valueParameter: FirValueParameter,
        isInPrimaryConstructor: Boolean,
        topLevel: Boolean,
        builder: StringBuilder
    ) {
        if (topLevel) {
            builder.append(renderKeyword("value-parameter")).append(" ")
        }

        builder.renderAnnotations(valueParameter)
        renderModifier(builder, valueParameter.isCrossinline, "crossinline")
        renderModifier(builder, valueParameter.isNoinline, "noinline")

        renderVariable(valueParameter, builder, topLevel, isInPrimaryConstructor)

        val withDefaultValue = valueParameter.defaultValue != null //TODO check if default value is inherited
        if (withDefaultValue) {
            builder.append(" = ...")
        }
    }

    private fun renderValVarPrefix(variable: FirVariable<*>, builder: StringBuilder, isInPrimaryConstructor: Boolean = false) {
        if (isInPrimaryConstructor || variable !is ValueParameterDescriptor) {
            builder.append(renderKeyword(if (variable.isVar) "var" else "val")).append(" ")
        }
    }

    private fun renderVariable(
        variable: FirVariable<*>,
        builder: StringBuilder,
        topLevel: Boolean,
        isInPrimaryConstructor: Boolean
    ) {
        val typeToRender = variable.returnTypeRef

        val isVarArg = (variable as? FirValueParameter)?.isVararg ?: false
        renderModifier(builder, isVarArg, "vararg")

        if (isInPrimaryConstructor || topLevel) {
            renderValVarPrefix(variable, builder, isInPrimaryConstructor)
        }

        renderName(variable, builder, topLevel)
        builder.append(": ")

        builder.append(renderType(typeToRender))
    }

    private fun renderProperty(property: FirProperty, containingDeclaration: FirDeclaration?, builder: StringBuilder) {
        renderPropertyAnnotations(property, builder)
        property.visibility
        renderVisibility(property.visibility, builder)
        renderModifier(builder, RendererModifier.CONST in modifiers && property.isConst, "const")
        renderMemberModifiers(property, builder)
        renderModalityForCallable(property, containingDeclaration, builder)
        renderOverride(property, builder)
        renderModifier(builder, RendererModifier.LATEINIT in modifiers && property.isLateInit, "lateinit")
        renderValVarPrefix(property, builder)
        renderTypeParameters(property.typeParameters, builder, true)
        renderReceiver(property, builder)

        renderName(property, builder, true)
        builder.append(": ").append(renderType(property.returnTypeRef))

        renderWhereSuffix(property.typeParameters, builder)
    }

    private fun renderPropertyAnnotations(property: FirProperty, builder: StringBuilder) {
        if (RendererModifier.ANNOTATIONS !in modifiers) return

        builder.renderAnnotations(property)


//        property.backingField?.let { builder.renderAnnotations(it, AnnotationUseSiteTarget.FIELD) }
//        property.delegateField?.let { builder.renderAnnotations(it, AnnotationUseSiteTarget.PROPERTY_DELEGATE_FIELD) }

//        if (PropertyAccessorRenderingPolicy.DEBUG == PropertyAccessorRenderingPolicy.NONE) {
//            property.getter?.let {
//                builder.renderAnnotations(it, AnnotationUseSiteTarget.PROPERTY_GETTER)
//            }
//            property.setter?.let { setter ->
//                setter.let {
//                    builder.renderAnnotations(it, AnnotationUseSiteTarget.PROPERTY_SETTER)
//                }
//                setter.valueParameters.single().let {
//                    builder.renderAnnotations(it, AnnotationUseSiteTarget.SETTER_PARAMETER)
//                }
//            }
//        }
    }

    private fun renderTypeAlias(typeAlias: FirTypeAlias, builder: StringBuilder) {
        builder.renderAnnotations(typeAlias)
        renderVisibility(typeAlias.visibility, builder)
        renderMemberModifiers(typeAlias, builder)
        builder.append(renderKeyword("typealias")).append(" ")
        renderName(typeAlias, builder, true)

        renderTypeParameters(typeAlias.typeParameters, builder, false)

        builder.append(" = ").append(renderType(typeAlias.expandedTypeRef))
    }

    /* CLASSES */
    private fun renderClass(klass: FirRegularClass, containingDeclaration: FirDeclaration?, builder: StringBuilder) {
        val isEnumEntry = klass.classKind == ClassKind.ENUM_ENTRY

        builder.renderAnnotations(klass)
        if (!isEnumEntry) {
            renderVisibility(klass.visibility, builder)
        }
        if (!(klass.classKind == ClassKind.INTERFACE && klass.modality == Modality.ABSTRACT ||
                    klass.classKind.isSingleton && klass.modality == Modality.FINAL)
        ) {
            val modality = klass.modality
            if (modality != null) {
                renderModality(modality, builder, klass.implicitModalityWithoutExtensions(containingDeclaration))
            }

        }
        renderMemberModifiers(klass, builder)
        renderModifier(builder, RendererModifier.INNER in modifiers && klass.isInner, "inner")
        renderModifier(builder, RendererModifier.DATA in modifiers && klass.isData, "data")
        renderModifier(builder, RendererModifier.INLINE in modifiers && klass.isInline, "inline")
        //TODO renderModifier(builder, RendererModifier.VALUE in modifiers && klass.isValue, "value")
        renderModifier(builder, RendererModifier.FUN in modifiers && klass.isFun, "fun")
        renderClassKindPrefix(klass, builder)

        if (!klass.isCompanion) {
            renderSpaceIfNeeded(builder)
            renderName(klass, builder, true)
        } else {
            renderCompanionObjectName(klass, builder)
        }

        if (isEnumEntry) return

        val typeParameters = klass.typeParameters
        renderTypeParameterRefs(typeParameters, builder, false)

        renderSuperTypes(klass, builder)
        renderWhereSuffix(typeParameters, builder)
    }

    private fun renderSuperTypes(klass: FirRegularClass, builder: StringBuilder) {

        if (klass.defaultType().isNothing) return

        val supertypes = klass.superTypeRefs
        if (supertypes.isEmpty() || klass.superTypeRefs.singleOrNull()?.let { it.isAny || it.isNullableAny} == true ) return

        renderSpaceIfNeeded(builder)
        builder.append(": ")
        supertypes.joinTo(builder, ", ") { renderType(it) }
    }


    private fun getClassifierKindPrefix(classifier: FirDeclaration): String = when (classifier) {
        is TypeAliasDescriptor -> "typealias"
        is FirRegularClass ->
            if (classifier.isCompanion) {
                "companion object"
            } else {
                when (classifier.classKind) {
                    ClassKind.CLASS -> "class"
                    ClassKind.INTERFACE -> "interface"
                    ClassKind.ENUM_CLASS -> "enum class"
                    ClassKind.OBJECT -> "object"
                    ClassKind.ANNOTATION_CLASS -> "annotation class"
                    ClassKind.ENUM_ENTRY -> "enum entry"
                }
            }
        else ->
            throw AssertionError("Unexpected classifier: $classifier")
    }

    private fun renderClassKindPrefix(klass: FirRegularClass, builder: StringBuilder) {
        builder.append(renderKeyword(getClassifierKindPrefix(klass)))
    }


    /* OTHER */
    private fun renderPackageView(packageView: PackageViewDescriptor, builder: StringBuilder) {
        renderPackageHeader(packageView.fqName, "package", builder)
    }

    private fun renderPackageFragment(fragment: PackageFragmentDescriptor, builder: StringBuilder) {
        renderPackageHeader(fragment.fqName, "package-fragment", builder)
    }

    private fun renderPackageHeader(fqName: FqName, fragmentOrView: String, builder: StringBuilder) {
        builder.append(renderKeyword(fragmentOrView))
        val fqNameString = renderFqName(fqName.toUnsafe())
        if (fqNameString.isNotEmpty()) {
            builder.append(" ")
            builder.append(fqNameString)
        }
    }

    /* STUPID DISPATCH-ONLY VISITOR */
    private inner class RenderDeclarationDescriptorVisitor(private val containingDeclaration: FirDeclaration?) : FirVisitor<Unit, StringBuilder>() {

        override fun visitValueParameter(valueParameter: FirValueParameter, data: StringBuilder) {
            renderValueParameter(valueParameter, containingDeclaration is FirConstructor, true, data)
        }

        override fun visitProperty(property: FirProperty, data: StringBuilder) {
            renderProperty(property, containingDeclaration, data)
        }

        override fun visitPropertyAccessor(propertyAccessor: FirPropertyAccessor, data: StringBuilder) {
            require(containingDeclaration is FirProperty?) { "Invalid containing declaration" }
            renderAccessor(propertyAccessor, containingDeclaration, data) //TODO
        }

        override fun visitSimpleFunction(simpleFunction: FirSimpleFunction, data: StringBuilder) {
            renderFunction(simpleFunction, containingDeclaration, data)
        }

        override fun visitThisReceiverExpression(thisReceiverExpression: FirThisReceiverExpression, data: StringBuilder) {
            data.append("this") // renders <this>
            //TODO was builder.append(descriptor.name) // renders <this>
        }

        override fun visitConstructor(constructor: FirConstructor, data: StringBuilder) {
            require(containingDeclaration is FirRegularClass) { "Invalid renderer containing declaration for constructor" }
            renderConstructor(constructor, containingDeclaration, data)
        }

        override fun visitTypeParameter(typeParameter: FirTypeParameter, data: StringBuilder) {
            renderTypeParameter(typeParameter, data, true)
        }

        override fun visitRegularClass(regularClass: FirRegularClass, data: StringBuilder) {
            renderClass(regularClass, containingDeclaration, data)
        }

        override fun visitTypeAlias(typeAlias: FirTypeAlias, data: StringBuilder) {
            renderTypeAlias(typeAlias, data)
        }

        override fun visitElement(element: FirElement, data: StringBuilder) {
            TODO("Not yet implemented")
        }
    }

    private fun renderSpaceIfNeeded(builder: StringBuilder) {
        val length = builder.length
        if (length == 0 || builder[length - 1] != ' ') {
            builder.append(' ')
        }
    }

    private fun replacePrefixes(
        lowerRendered: String,
        lowerPrefix: String,
        upperRendered: String,
        upperPrefix: String,
        foldedPrefix: String
    ): String? {
        if (lowerRendered.startsWith(lowerPrefix) && upperRendered.startsWith(upperPrefix)) {
            val lowerWithoutPrefix = lowerRendered.substring(lowerPrefix.length)
            val upperWithoutPrefix = upperRendered.substring(upperPrefix.length)
            val flexibleCollectionName = foldedPrefix + lowerWithoutPrefix

            if (lowerWithoutPrefix == upperWithoutPrefix) return flexibleCollectionName

            if (differsOnlyInNullability(lowerWithoutPrefix, upperWithoutPrefix)) {
                return "$flexibleCollectionName!"
            }
        }
        return null
    }

    private fun differsOnlyInNullability(lower: String, upper: String) =
        lower == upper.replace("?", "") || upper.endsWith("?") && ("$lower?") == upper || "($lower)?" == upper

    private fun overridesSomething(callable: FirCallableMemberDeclaration<*>) = false //TODO callable.overriddenDescriptors.isNotEmpty()

}
