/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.backend.handlers

import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.TREAT_AS_ONE_FILE
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.model.BinaryArtifacts
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.isKtFile
import java.util.LinkedHashMap

class BytecodeTextHandler(testServices: TestServices) : JvmBinaryArtifactHandler(testServices) {
    companion object {
        private const val IGNORED_PREFIX = "helpers/"
    }

    override val directivesContainers: List<DirectivesContainer>
        get() = listOf(CodegenTestDirectives)

    override fun processModule(module: TestModule, info: BinaryArtifacts.Jvm) {
        val targetBackend = module.targetBackend!!
        val isIgnored = targetBackend in module.directives[CodegenTestDirectives.IGNORE_BACKEND]
        val files = module.files.filter { it.isKtFile }
        if (files.size > 1 && TREAT_AS_ONE_FILE !in module.directives) {
            processMultiFileTest(files, info, targetBackend, !isIgnored)
        } else {
            val file = files.first { !it.isAdditional }
            val expected = readExpectedOccurrences(file.originalContent.split("\n"))
            val actual = info.classFileFactory.createText(IGNORED_PREFIX)
            checkGeneratedTextAgainstExpectedOccurrences(actual, expected, targetBackend, !isIgnored, assertions)
        }
    }

    private fun processMultiFileTest(
        files: List<TestFile>,
        info: BinaryArtifacts.Jvm,
        targetBackend: TargetBackend,
        reportProblems: Boolean
    ) {
        val expectedOccurrencesByOutputFile = LinkedHashMap<String, List<OccurrenceInfo>>()
        for (file in files) {
            readExpectedOccurrencesForMultiFileTest(file.name, file.originalContent, expectedOccurrencesByOutputFile)
        }

        val generated = info.classFileFactory.createTextForEachFile()
        for (expectedOutputFile in expectedOccurrencesByOutputFile.keys) {
            assertTextWasGenerated(expectedOutputFile, generated, assertions)
            val generatedText = generated[expectedOutputFile]!!
            val expectedOccurrences = expectedOccurrencesByOutputFile[expectedOutputFile]!!
            checkGeneratedTextAgainstExpectedOccurrences(generatedText, expectedOccurrences, targetBackend, reportProblems, assertions)
        }
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {}
}
