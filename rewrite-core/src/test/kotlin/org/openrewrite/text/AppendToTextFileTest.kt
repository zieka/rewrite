/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.text

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.openrewrite.internal.StringUtils.trimIndentPreserveCRLF
import org.openrewrite.test.RewriteTest
import org.openrewrite.test.SourceSpecs

class AppendToTextFileTest : RewriteTest {
    @Test
    fun `creates file if needed`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file.txt", "content", "preamble", true, "leave"))
        .expectedCyclesThatMakeChanges(1)
        .afterRecipe { resultList -> resultList.let {
            assertEquals(1, resultList.size)
            val actualSourceFile = resultList[0].after!!
            val actualPlaintext = actualSourceFile as PlainText
            assertEquals("file.txt", actualSourceFile.sourcePath.toString())
            assertEquals(trimIndentPreserveCRLF("""
                    preamble
                    content
                    
                """), actualPlaintext.text)
        }}}, *arrayOf<SourceSpecs>())

    @Test
    fun `creates file if needed with multiple instances`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file.txt", "content", "preamble", true, "leave")
            .doNext(AppendToTextFile("file.txt", "content", "preamble", true, "leave")))
        .expectedCyclesThatMakeChanges(1)
        .afterRecipe { resultList -> resultList.let {
            assertEquals(1, resultList.size)
            val actualSourceFile = resultList[0].after!!
            val actualPlaintext = actualSourceFile as PlainText
            assertEquals("file.txt", actualSourceFile.sourcePath.toString())
            assertEquals(trimIndentPreserveCRLF("""
                    preamble
                    content
                    content
                    
                """), actualPlaintext.text)
        }}}, *arrayOf<SourceSpecs>())

    @Test
    fun `replaces file if requested`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file.txt", "content", "preamble", true, "replace")) },
        plainText("""
            existing
            """, """
            preamble
            content
            
        """) { spec -> spec.path("file.txt") })

    @Test
    fun `continues file if requested`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file.txt", "content", "preamble", true, "continue")) },
        plainText("""
            existing
            """, """
            existingcontent
            
        """) { spec -> spec.path("file.txt") })

    @Test
    fun `leaves file if requested`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file.txt", "content", "preamble", true, "leave")) },
        plainText("""
            existing
            """) { spec -> spec.path("file.txt") })

    @Test
    fun `mutltiple instances can append`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file.txt", "content", "preamble", true, "replace")
            .doNext(AppendToTextFile("file.txt", "content", "preamble", true, "replace"))) },
        plainText("""
            existing
            """, """
            preamble
            content
            content
            
        """) { spec -> spec.path("file.txt") })

    @Test
    fun `no leading newline if no preamble`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file.txt", "content", null, true, "replace")) },
        plainText("""
            existing
            """, """
            content
            
        """) { spec -> spec.path("file.txt") })

    @Test
    fun `leaves existing file if not plaintext`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file.json", "content", "preamble", true, "continue")) },
        json("""{"a":"b"}""") { spec -> spec.path("file.json") })

    @Test
    fun `mutltiple files`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file1.txt", "content1", "preamble1", true, "replace")
            .doNext(AppendToTextFile("file2.txt", "content2", "preamble2", true, "replace"))) },
        plainText("""
            existing1
            """, """
            preamble1
            content1
            
        """) { spec -> spec.path("file1.txt") },
        plainText("""
            existing2
            """, """
            preamble2
            content2
            
        """) { spec -> spec.path("file2.txt") })

    @Test
    fun `mutltiple instances on multiple files`() = rewriteRun( { spec -> spec
        .recipe(AppendToTextFile("file1.txt", "content1", "preamble1", true, "replace")
            .doNext(AppendToTextFile("file2.txt", "content2", "preamble2", true, "replace"))
            .doNext(AppendToTextFile("file1.txt", "content1", "preamble1", true, "replace"))
            .doNext(AppendToTextFile("file2.txt", "content2", "preamble2", true, "replace"))) },
        plainText("""
            existing1
            """, """
            preamble1
            content1
            content1
            
        """) { spec -> spec.path("file1.txt") },
        plainText("""
            existing2
            """, """
            preamble2
            content2
            content2
            
        """) { spec -> spec.path("file2.txt") })
}