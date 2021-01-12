/*
 * Copyright 2020 the original author or authors.
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
package org.openrewrite.java

import org.junit.jupiter.api.Test
import org.openrewrite.Cursor
import org.openrewrite.ExecutionContext
import org.openrewrite.RecipeTest
import org.openrewrite.internal.ListUtils
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.java.tree.Space
import org.openrewrite.java.tree.Space.format
import org.openrewrite.java.tree.Statement

interface JavaTemplateTest : RecipeTest {
    @Test
    fun beforeMethodBodyStatement(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaProcessor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J {
                val parent = cursor.parentOrThrow.getTree<J>()
                if(parent is J.MethodDecl) {
                    return block.withStatements(
                        ListUtils.concat(
                            JRightPadded(
                                JavaTemplate.builder("others.add(#{});").build()
                                    .generateBefore<Statement>(
                                        Cursor(cursor, block.statements[0].elem),
                                        (parent.params.elem[0].elem as J.VariableDecls).vars[0]
                                    )[0],
                                Space.EMPTY
                            ),
                            block.statements
                        )
                    )
                }
                return super.visitBlock(block, p)
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    n++;
                }
            }
        """,
        after = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    others.add(m);
                    n++;
                }
            }
        """
    )

    @Test
    fun afterMethodBodyStatement(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaProcessor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J {
                val parent = cursor.parentOrThrow.getTree<J>()
                if(parent is J.MethodDecl) {
                    return block.withStatements(
                        ListUtils.concat(
                            block.statements,
                            JRightPadded(
                                JavaTemplate.builder("others.add(#{});").build()
                                    .generateAfter<Statement>(
                                        Cursor(cursor, block.statements[0].elem),
                                        (parent.params.elem[0].elem as J.VariableDecls).vars[0]
                                    )[0],
                                Space.EMPTY
                            )
                        )
                    )
                }
                return super.visitBlock(block, p)
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    n++;
                }
            }
        """,
        after = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    n++;
                    others.add(m);
                }
            }
        """
    )

    @Test
    fun addAnnotationToMethod(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoProcessor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitMethod(method: J.MethodDecl, p: ExecutionContext): J.MethodDecl {
                var m = super.visitMethod(method, p)
                m = m.withAnnotations(ListUtils.concat(
                    m.annotations,
                    JavaTemplate.builder("@Deprecated").build()
                        .generateBefore<J.Annotation>(Cursor(cursor, method))[0]
                ))
                m = m.withReturnTypeExpr(m.returnTypeExpr!!.withPrefix(format(" ")));
                return m
            }
        }.toRecipe(),
        before = """
            public class A {
                void foo() {
                }
            }
        """,
        after = """
            public class A {
                @Deprecated void foo() {
                }
            }
        """
    )

//    @Test
//    fun buildSnippetLocalMethodReference(jp: JavaParser) {
//        val a = jp.parse(
//            """
//            import java.util.List;
//            import static java.util.Collections.emptyList;
//
//            public class A {
//                int n = 0;
//                void foo(String m, List<String> others) {
//                    incrementCounterByListSize(others);
//                    others.add(m);
//                }
//                char incrementCounterByListSize(List<String> list) {
//                    n =+ list.size();
//                    return 'f';
//                }
//            }
//        """.trimIndent()
//        )[0]
//
//        val method = a.classes[0].methods[0]
//        val methodBodyCursor = CursorExtractor(method.body!!).visit(a)
//        val param1 = (method.params.elem[0].elem as J.VariableDecls).vars[0].elem.name
//        val param2 = method.body!!.statements[0].elem
//
//        val template = JavaTemplate.builder(
//            """{
//                        others.add(#{});
//                        #{};
//                    }
//                 """
//        ).build()
//        val snippets = template.generate<J>(methodBodyCursor, param1, param2)
//        //Snippet should be the method block with the two statements in it.
//        assertThat(snippets).hasSize(1);
//        val block: J.Block = snippets[0] as J.Block
//
//        val methodInvocation1 = block.statements[0].elem as J.MethodInvocation // others.add(m)
//        val methodInvocation2 = block.statements[0].elem as J.MethodInvocation // incrementCounterByListSize(others);
//
//        assertThat(methodInvocation1.type).`as`("The type information should be populated").isNotNull
//        assertThat(methodInvocation2.type).`as`("The type information should be populated").isNotNull
//    }
//
//    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
//    @Test
//    fun buildSnippetMethodReferenceSiblingClass(jp: JavaParser) {
//        val a = jp.parse(
//            """
//            import java.util.List;
//            import java.util.ArrayList;
//            import static java.util.Collections.emptyList;
//
//            public class A {
//                int n = 0;
//                void foo(String m, List<String> others) {
//                    boolean flag = true;
//                    if (flag) {
//                        List<String> clone = B.cloneList(others);
//                        clone.add(m);
//                    }
//                    int fred = 8;
//                }
//
//                public static class B {
//                    public static List<String> cloneList(List<String> list) {
//                        return new ArrayList<>(list);
//                    }
//                }
//
//                public static class C {
//
//                    private int hello = 0;
//                    private String nope = "nothing here";
//                }
//
//            }
//        """.trimIndent()
//        )[0]
//
//        val method = a.classes[0].methods[0]
//        val then = (method.body!!.statements[1].elem as J.If).thenPart.elem as J.Block
//        val methodBodyCursor = CursorExtractor(then.statements[0].elem).visit(a)
//        val param = (method.params.elem[0].elem as J.VariableDecls).vars[0].elem.name
//
//        val template = JavaTemplate.builder(
//            """others.add(#{});
//                    #{};"""
//        ).build()
//        val snippets = template.generate<J>(methodBodyCursor, param, then.statements[0].elem)
//        assertThat(snippets).hasSize(2)
//
//        val methodInv1: Expression = snippets[0] as Expression
//        assertThat(methodInv1.type).`as`("The type information should be populated").isNotNull
//        val variableDeclarations = snippets[1] as J.VariableDecls
//        val methodInvocation = variableDeclarations.vars[0].elem.initializer.elem as J.MethodInvocation
//        assertThat("Class{A.B}").isEqualTo((methodInvocation.select.elem as J.Ident).ident.type.toString())
//        assertThat("List<String>").isEqualTo(variableDeclarations.typeExpr.printTrimmed())
//    }
//
//    class CursorExtractor(private val scope: J) : JavaIsoProcessor<CursorHolder>() {
//
//        init {
//            setCursoringOn()
//        }
//
//        fun visit(tree: J?): Cursor {
//            val cursorHolder = CursorHolder()
//            super.visit(tree, cursorHolder)
//            return cursorHolder.c!!
//        }
//
//        override fun visitEach(tree: J?, cursorHolder: CursorHolder): J? {
//            if (scope.isScope(tree)) {
//                cursorHolder.c = this.cursor
//            }
//            return tree
//        }
//    }
//
//    class CursorHolder {
//        var c: Cursor? = null
//    }
}
