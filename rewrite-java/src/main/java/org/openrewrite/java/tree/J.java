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
package org.openrewrite.java.tree;

import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.openrewrite.*;
import org.openrewrite.internal.LoathingOfOthers;
import org.openrewrite.internal.SelfLoathing;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.NonNull;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaPrinter;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.internal.TypesInUse;
import org.openrewrite.java.internal.template.JavaTemplateParser;
import org.openrewrite.java.search.FindTypes;
import org.openrewrite.marker.Markers;
import org.openrewrite.template.SourceTemplate;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

@SuppressWarnings("unused")
public interface J extends Tree {
    static void clearCaches() {
        JavaTemplateParser.clearCache();
    }

    @SuppressWarnings("unchecked")
    @Override
    default <R extends Tree, P> R accept(TreeVisitor<R, P> v, P p) {
        return (R) acceptJava((JavaVisitor<P>) v, p);
    }

    @Override
    default <P> boolean isAcceptable(TreeVisitor<?, P> v, P p) {
        return v instanceof JavaVisitor;
    }

    @Nullable
    default <P> J acceptJava(JavaVisitor<P> v, P p) {
        return v.defaultValue(this, p);
    }

    <J2 extends J> J2 withPrefix(Space space);

    Space getPrefix();

    default /*~~>*/List<Comment> getComments() {
        return getPrefix().getComments();
    }

    default <J2 extends J> J2 withComments(/*~~>*/List<Comment> comments) {
        return withPrefix(getPrefix().withComments(comments));
    }

    @Incubating(since = "7.0.0")
    default <J2 extends J> J2 withTemplate(SourceTemplate<J, JavaCoordinates> template, JavaCoordinates coordinates, Object... parameters) {
        return template.withTemplate(this, coordinates, parameters);
    }

    <J2 extends J> J2 withMarkers(Markers markers);

    Markers getMarkers();

    /**
     * @return This tree, printed.
     * @deprecated This method doesn't print in a way that is
     * specialized for each language extension of the base Java model. Use {@link #print(Cursor)} instead.
     */
    @Deprecated
    default String print() {
        PrintOutputCapture<Integer> outputCapture = new PrintOutputCapture<>(0);
        new JavaPrinter<Integer>().visit(this, outputCapture);
        return outputCapture.getOut();
    }

    /**
     * @return This tree, printed.
     * @deprecated This method doesn't print in a way that is
     * specialized for each language extension of the base Java model. Use {@link #print(Cursor)} instead.
     */
    @Deprecated
    default String printTrimmed() {
        return StringUtils.trimIndent(print());
    }

    @SuppressWarnings("unchecked")
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class AnnotatedType implements J, Expression, TypeTree {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        /*~~>*/List<J.Annotation> annotations;

        @With
        TypeTree typeExpression;

        @Override
        public JavaType getType() {
            return typeExpression.getType();
        }

        @Override
        public AnnotatedType withType(@Nullable JavaType type) {
            return withTypeExpression(typeExpression.withType(type));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitAnnotatedType(this, p);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Annotation implements J, Expression {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        NameTree annotationType;

        public String getSimpleName() {
            return annotationType instanceof Identifier ?
                    ((Identifier) annotationType).getSimpleName() :
                    ((J.FieldAccess) annotationType).getSimpleName();
        }

        @Nullable
        JContainer<Expression> arguments;

        @Nullable
        public /*~~>*/List<Expression> getArguments() {
            return arguments == null ? null : arguments.getElements();
        }

        public Annotation withArguments(@Nullable /*~~>*/List<Expression> arguments) {
            return getPadding().withArguments(JContainer.withElementsNullable(this.arguments, arguments));
        }

        @Override
        public JavaType getType() {
            return annotationType.getType();
        }

        @SuppressWarnings("unchecked")
        @Override
        public Annotation withType(@Nullable JavaType type) {
            return withAnnotationType(annotationType.withType(type));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitAnnotation(this, p);
        }

        public CoordinateBuilder.Annotation getCoordinates() {
            return new CoordinateBuilder.Annotation(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Annotation t;

            @Nullable
            public JContainer<Expression> getArguments() {
                return t.arguments;
            }

            public Annotation withArguments(@Nullable JContainer<Expression> arguments) {
                return t.arguments == arguments ? t : new Annotation(t.id, t.prefix, t.markers, t.annotationType, arguments);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class ArrayAccess implements J, Expression, TypedTree {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        Expression indexed;

        @With
        ArrayDimension dimension;

        @With
        @Nullable
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitArrayAccess(this, p);
        }

        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class ArrayType implements J, TypeTree, Expression {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        TypeTree elementType;

        @With
        /*~~>*/List<JRightPadded<Space>> dimensions;

        @Override
        public JavaType getType() {
            return elementType.getType();
        }

        @SuppressWarnings("unchecked")
        @Override
        public ArrayType withType(@Nullable JavaType type) {
            return type == getType() ? this : withElementType(elementType.withType(type));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitArrayType(this, p);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Assert implements J, Statement {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        Expression condition;

        @Nullable
        @With
        JLeftPadded<Expression> detail;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitAssert(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Assignment implements J, Statement, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        Expression variable;

        JLeftPadded<Expression> assignment;

        public Expression getAssignment() {
            return assignment.getElement();
        }

        public Assignment withAssignment(Expression assignment) {
            return getPadding().withAssignment(this.assignment.withElement(assignment));
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @With
        @Nullable
        @Getter
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitAssignment(this, p);
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            return singletonList(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Assignment t;

            public JLeftPadded<Expression> getAssignment() {
                return t.assignment;
            }

            public Assignment withAssignment(JLeftPadded<Expression> assignment) {
                return t.assignment == assignment ? t : new Assignment(t.id, t.prefix, t.markers, t.variable, assignment, t.type);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class AssignmentOperation implements J, Statement, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        Expression variable;

        JLeftPadded<Type> operator;

        public Type getOperator() {
            return operator.getElement();
        }

        public AssignmentOperation withOperator(Type operator) {
            return getPadding().withOperator(this.operator.withElement(operator));
        }

        @With
        @Getter
        Expression assignment;

        @With
        @Nullable
        @Getter
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitAssignmentOperation(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            return singletonList(this);
        }

        public enum Type {
            Addition,
            BitAnd,
            BitOr,
            BitXor,
            Division,
            LeftShift,
            Modulo,
            Multiplication,
            RightShift,
            Subtraction,
            UnsignedRightShift
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final AssignmentOperation t;

            public JLeftPadded<Type> getOperator() {
                return t.operator;
            }

            public AssignmentOperation withOperator(JLeftPadded<Type> operator) {
                return t.operator == operator ? t : new AssignmentOperation(t.id, t.prefix, t.markers, t.variable, operator, t.assignment, t.type);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Data
    final class Binary implements J, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        Expression left;

        JLeftPadded<Type> operator;

        public Type getOperator() {
            return operator.getElement();
        }

        public Binary withOperator(Type operator) {
            return getPadding().withOperator(this.operator.withElement(operator));
        }

        @With
        Expression right;

        @With
        @Nullable
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitBinary(this, p);
        }

        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            List<J> sideEffects = new ArrayList<>(2);
            sideEffects.addAll(left.getSideEffects());
            sideEffects.addAll(right.getSideEffects());
            return sideEffects;
        }

        public enum Type {
            Addition,
            Subtraction,
            Multiplication,
            Division,
            Modulo,
            LessThan,
            GreaterThan,
            LessThanOrEqual,
            GreaterThanOrEqual,
            Equal,
            NotEqual,
            BitAnd,
            BitOr,
            BitXor,
            LeftShift,
            RightShift,
            UnsignedRightShift,
            Or,
            And
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Binary t;

            public JLeftPadded<Type> getOperator() {
                return t.operator;
            }

            public Binary withOperator(JLeftPadded<Type> operator) {
                return t.operator == operator ? t : new Binary(t.id, t.prefix, t.markers, t.left, operator, t.right, t.type);
            }
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Block implements J, Statement {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @Getter
        @EqualsAndHashCode.Include
        UUID id;

        @Getter
        @With
        Space prefix;

        @Getter
        @With
        Markers markers;

        JRightPadded<Boolean> statik;

        public boolean isStatic() {
            return statik.getElement();
        }

        public Block withStatic(boolean statik) {
            return getPadding().withStatic(this.statik.withElement(statik));
        }

        /*~~>*/List<JRightPadded<Statement>> statements;

        public /*~~>*/List<Statement> getStatements() {
            return JRightPadded.getElements(statements);
        }

        public Block withStatements(/*~~>*/List<Statement> statements) {
            return getPadding().withStatements(JRightPadded.withElements(this.statements, statements));
        }

        @Getter
        @With
        Space end;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitBlock(this, p);
        }

        public CoordinateBuilder.Block getCoordinates() {
            return new CoordinateBuilder.Block(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Block t;

            public JRightPadded<Boolean> getStatic() {
                return t.statik;
            }

            public Block withStatic(JRightPadded<Boolean> statik) {
                return t.statik == statik ? t : new Block(t.id, t.prefix, t.markers, statik, t.statements, t.end);
            }

            public /*~~>*/List<JRightPadded<Statement>> getStatements() {
                return t.statements;
            }

            public Block withStatements(/*~~>*/List<JRightPadded<Statement>> statements) {
                return t.statements == statements ? t : new Block(t.id, t.prefix, t.markers, t.statik, statements, t.end);
            }
        }

        @SelfLoathing(name = "Jonathan Leitschuh")
        @LoathingOfOthers("Who didn't encode this in the model?!")
        @Incubating(since = "7.25.0")
        public static boolean isInitBlock(Cursor cursor) {
            if (!(cursor.getValue() instanceof J.Block)) {
                throw new IllegalArgumentException("Cursor must point to a J.Block!");
            }
            J.Block block = cursor.getValue();
            if (block.isStatic()) {
                return false;
            }
            J.Block parentBlock = null;
            Iterator<Object> path = cursor.getPath();
            if (path.hasNext()) {
                path.next(); // skip the first element, which is the block itself
            }
            while (path.hasNext()) {
                Object next = path.next();
                if (parentBlock != null && next instanceof J.Block) {
                    // If we find an outer block before a ClassDeclaration or NewClass, we're not in an initializer block.
                    return false;
                } else if (next instanceof J.Block) {
                    parentBlock = (J.Block) next;
                    if (!parentBlock.getStatements().contains(block)) {
                        return false;
                    }
                } else if (next instanceof J.ClassDeclaration) {
                    J.ClassDeclaration classDeclaration = (J.ClassDeclaration) next;
                    return classDeclaration.getBody() == parentBlock;
                } else if (next instanceof J.NewClass) {
                    J.NewClass newClass = (J.NewClass) next;
                    return newClass.getBody() == parentBlock;
                }
            }
            return false;
        }

        /**
         * Determines if the passed cursor is an {@link J.Block} that is static or initializer block.
         *
         * @param cursor Must point to a {@link J.Block}
         * @return True if the cursor represents a static or initializer block, false otherwise.
         */
        @Incubating(since = "7.25.0")
        public static boolean isStaticOrInitBlock(Cursor cursor) {
            if (!(cursor.getValue() instanceof J.Block)) {
                throw new IllegalArgumentException("Cursor must point to a J.Block!");
            }
            J.Block block = cursor.getValue();
            return block.isStatic() || isInitBlock(cursor);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Break implements J, Statement {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        @Nullable
        J.Identifier label;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitBreak(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Case implements J, Statement {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        Expression pattern;

        JContainer<Statement> statements;

        public /*~~>*/List<Statement> getStatements() {
            return statements.getElements();
        }

        public Case withStatements(/*~~>*/List<Statement> statements) {
            return getPadding().withStatements(this.statements.getPadding().withElements(JRightPadded.withElements(
                    this.statements.getPadding().getElements(), statements)));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitCase(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Case t;

            public JContainer<Statement> getStatements() {
                return t.statements;
            }

            public Case withStatements(JContainer<Statement> statements) {
                return t.statements == statements ? t : new Case(t.id, t.prefix, t.markers, t.pattern, statements);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class ClassDeclaration implements J, Statement, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @Nullable
        @NonFinal
        transient WeakReference<Annotations> annotations;

        @With
        @Getter
        @EqualsAndHashCode.Include
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        /*~~>*/List<Annotation> leadingAnnotations;

        @With
        @Getter
        /*~~>*/List<Modifier> modifiers;

        Kind kind;

        public Kind.Type getKind() {
            return kind.getType();
        }

        public ClassDeclaration withKind(Kind.Type type) {
            Kind k = getAnnotations().getKind();
            if (k.type == type) {
                return this;
            } else {
                return getAnnotations().withKind(k.withType(type));
            }
        }

        @With
        @Getter
        Identifier name;

        @Nullable
        JContainer<TypeParameter> typeParameters;

        @Nullable
        public /*~~>*/List<TypeParameter> getTypeParameters() {
            return typeParameters == null ? null : typeParameters.getElements();
        }

        public ClassDeclaration withTypeParameters(@Nullable /*~~>*/List<TypeParameter> typeParameters) {
            return getPadding().withTypeParameters(JContainer.withElementsNullable(this.typeParameters, typeParameters));
        }

        @Nullable
        JLeftPadded<TypeTree> extendings;

        @Nullable
        public TypeTree getExtends() {
            return extendings == null ? null : extendings.getElement();
        }

        public ClassDeclaration withExtends(@Nullable TypeTree extendings) {
            return getPadding().withExtends(JLeftPadded.withElement(this.extendings, extendings));
        }

        @Nullable
        JContainer<TypeTree> implementings;

        @Nullable
        public /*~~>*/List<TypeTree> getImplements() {
            return implementings == null ? null : implementings.getElements();
        }

        public ClassDeclaration withImplements(@Nullable /*~~>*/List<TypeTree> implementings) {
            return getPadding().withImplements(JContainer.withElementsNullable(this.implementings, implementings));
        }

        @With
        @Getter
        Block body;

        @Getter
        @Nullable
        JavaType.FullyQualified type;

        @SuppressWarnings("unchecked")
        @Override
        public ClassDeclaration withType(@Nullable JavaType type) {
            if (type == this.type) {
                return this;
            }

            if (type != null && !(type instanceof JavaType.FullyQualified)) {
                throw new IllegalArgumentException("A class can only be type attributed with a fully qualified type name");
            }

            return new ClassDeclaration(id, prefix, markers, leadingAnnotations, modifiers, kind, name, typeParameters, extendings, implementings, body, (JavaType.FullyQualified) type);
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitClassDeclaration(this, p);
        }

        // gather annotations from everywhere they may occur
        public /*~~>*/List<J.Annotation> getAllAnnotations() {
            List<Annotation> allAnnotations = new ArrayList<>(leadingAnnotations);
            for (J.Modifier modifier : modifiers) {
                allAnnotations.addAll(modifier.getAnnotations());
            }
            allAnnotations.addAll(kind.getAnnotations());
            return allAnnotations;
        }

        @Override
        public CoordinateBuilder.ClassDeclaration getCoordinates() {
            return new CoordinateBuilder.ClassDeclaration(this);
        }

        public String getSimpleName() {
            return name.getSimpleName();
        }

        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @Data
        public static final class Kind implements J {

            @With
            @Getter
            @EqualsAndHashCode.Include
            UUID id;

            @With
            @Getter
            Space prefix;

            @With
            @Getter
            Markers markers;

            @With
            @Getter
            /*~~>*/List<Annotation> annotations;

            @With
            @Getter
            Type type;

            public enum Type {
                Class,
                Enum,
                Interface,
                Annotation
            }
        }

        public boolean hasModifier(Modifier.Type modifier) {
            return Modifier.hasModifier(getModifiers(), modifier);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final ClassDeclaration t;

            @Nullable
            public JLeftPadded<TypeTree> getExtends() {
                return t.extendings;
            }

            public ClassDeclaration withExtends(@Nullable JLeftPadded<TypeTree> extendings) {
                return t.extendings == extendings ? t : new ClassDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, t.kind, t.name, t.typeParameters, extendings, t.implementings, t.body, t.type);
            }

            @Nullable
            public JContainer<TypeTree> getImplements() {
                return t.implementings;
            }

            public ClassDeclaration withImplements(@Nullable JContainer<TypeTree> implementings) {
                return t.implementings == implementings ? t : new ClassDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, t.kind, t.name, t.typeParameters, t.extendings, implementings, t.body, t.type);
            }

            public Kind getKind() {
                return t.kind;
            }

            public ClassDeclaration withKind(Kind kind) {
                return t.kind == kind ? t : new ClassDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, kind, t.name, t.typeParameters, t.extendings, t.implementings, t.body, t.type);
            }

            @Nullable
            public JContainer<TypeParameter> getTypeParameters() {
                return t.typeParameters;
            }

            public ClassDeclaration withTypeParameters(@Nullable JContainer<TypeParameter> typeParameters) {
                return t.typeParameters == typeParameters ? t : new ClassDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, t.kind, t.name, typeParameters, t.extendings, t.implementings, t.body, t.type);
            }
        }

        public Annotations getAnnotations() {
            Annotations a;
            if (this.annotations == null) {
                a = new Annotations(this);
                this.annotations = new WeakReference<>(a);
            } else {
                a = this.annotations.get();
                if (a == null || a.t != this) {
                    a = new Annotations(this);
                    this.annotations = new WeakReference<>(a);
                }
            }
            return a;
        }

        @RequiredArgsConstructor
        public static class Annotations {
            private final ClassDeclaration t;

            public Kind getKind() {
                return t.kind;
            }

            public ClassDeclaration withKind(Kind kind) {
                return t.kind == kind ? t : new ClassDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, kind, t.name, t.typeParameters, t.extendings, t.implementings, t.body, t.type);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class CompilationUnit implements J, JavaSourceFile, SourceFile {
        @Nullable
        @NonFinal
        transient SoftReference<TypesInUse> typesInUse;

        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        Path sourcePath;

        @With
        @Getter
        @Nullable
        FileAttributes fileAttributes;

        @Nullable // for backwards compatibility
        @With(AccessLevel.PRIVATE)
        String charsetName;

        @With
        @Getter
        boolean charsetBomMarked;

        @With
        @Getter
        @Nullable
        Checksum checksum;

        @Override
        public Charset getCharset() {
            return charsetName == null ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
        }

        @SuppressWarnings("unchecked")
        @Override
        public SourceFile withCharset(Charset charset) {
            return withCharsetName(charset.name());
        }

        @Nullable
        JRightPadded<Package> packageDeclaration;

        @Nullable
        public Package getPackageDeclaration() {
            return packageDeclaration == null ? null : packageDeclaration.getElement();
        }

        public CompilationUnit withPackageDeclaration(Package packageDeclaration) {
            return getPadding().withPackageDeclaration(JRightPadded.withElement(this.packageDeclaration, packageDeclaration));
        }

        /*~~>*/List<JRightPadded<Import>> imports;

        public /*~~>*/List<Import> getImports() {
            return JRightPadded.getElements(imports);
        }

        public CompilationUnit withImports(/*~~>*/List<Import> imports) {
            return getPadding().withImports(JRightPadded.withElements(this.imports, imports));
        }

        @With
        @Getter
        /*~~>*/List<ClassDeclaration> classes;

        @With
        @Getter
        Space eof;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitJavaSourceFile(this, p);
        }

        public Set<NameTree> findType(String clazz) {
            return FindTypes.find(this, clazz);
        }

        @Override
        public <P> TreeVisitor<?, PrintOutputCapture<P>> printer(Cursor cursor) {
            return new JavaPrinter<>();
        }

        public TypesInUse getTypesInUse() {
            TypesInUse cache;
            if (this.typesInUse == null) {
                cache = TypesInUse.build(this);
                this.typesInUse = new SoftReference<>(cache);
            } else {
                cache = this.typesInUse.get();
                if (cache == null || cache.getCu() != this) {
                    cache = TypesInUse.build(this);
                    this.typesInUse = new SoftReference<>(cache);
                }
            }
            return cache;
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding implements JavaSourceFile.Padding {
            private final CompilationUnit t;

            @Nullable
            public JRightPadded<Package> getPackageDeclaration() {
                return t.packageDeclaration;
            }

            public CompilationUnit withPackageDeclaration(@Nullable JRightPadded<Package> packageDeclaration) {
                return t.packageDeclaration == packageDeclaration ? t : new CompilationUnit(t.id, t.prefix, t.markers, t.sourcePath, t.fileAttributes, t.charsetName, t.charsetBomMarked, null,
                        packageDeclaration, t.imports, t.classes, t.eof);
            }

            @Override
            public /*~~>*/List<JRightPadded<Import>> getImports() {
                return t.imports;
            }

            @Override
            public CompilationUnit withImports(/*~~>*/List<JRightPadded<Import>> imports) {
                return t.imports == imports ? t : new CompilationUnit(t.id, t.prefix, t.markers, t.sourcePath, t.fileAttributes, t.charsetName, t.charsetBomMarked, null,
                        t.packageDeclaration, imports, t.classes, t.eof);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Continue implements J, Statement {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        @Nullable
        J.Identifier label;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitContinue(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class DoWhileLoop implements J, Loop {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        JRightPadded<Statement> body;

        public Statement getBody() {
            return body.getElement();
        }

        @SuppressWarnings("unchecked")
        public DoWhileLoop withBody(Statement body) {
            return getPadding().withBody(this.body.withElement(body));
        }

        JLeftPadded<ControlParentheses<Expression>> whileCondition;

        public ControlParentheses<Expression> getWhileCondition() {
            return whileCondition.getElement();
        }

        public DoWhileLoop withWhileCondition(ControlParentheses<Expression> whileCondition) {
            return getPadding().withWhileCondition(this.whileCondition.withElement(whileCondition));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitDoWhileLoop(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final DoWhileLoop t;

            public JRightPadded<Statement> getBody() {
                return t.body;
            }

            public DoWhileLoop withBody(JRightPadded<Statement> body) {
                return t.body == body ? t : new DoWhileLoop(t.id, t.prefix, t.markers, body, t.whileCondition);
            }

            public JLeftPadded<ControlParentheses<Expression>> getWhileCondition() {
                return t.whileCondition;
            }

            public DoWhileLoop withWhileCondition(JLeftPadded<ControlParentheses<Expression>> whileCondition) {
                return t.whileCondition == whileCondition ? t : new DoWhileLoop(t.id, t.prefix, t.markers, t.body, whileCondition);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Empty implements J, Statement, Expression, TypeTree {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @Override
        public JavaType getType() {
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Empty withType(@Nullable JavaType type) {
            return this;
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitEmpty(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class EnumValue implements J {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        /*~~>*/List<J.Annotation> annotations;

        @With
        Identifier name;

        @With
        @Nullable
        NewClass initializer;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitEnumValue(this, p);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class EnumValueSet implements J, Statement {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        /*~~>*/List<JRightPadded<EnumValue>> enums;

        public /*~~>*/List<EnumValue> getEnums() {
            return JRightPadded.getElements(enums);
        }

        public EnumValueSet withEnums(/*~~>*/List<EnumValue> enums) {
            return getPadding().withEnums(JRightPadded.withElements(this.enums, enums));
        }

        @With
        @Getter
        boolean terminatedWithSemicolon;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitEnumValueSet(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final EnumValueSet t;

            public /*~~>*/List<JRightPadded<EnumValue>> getEnums() {
                return t.enums;
            }

            public EnumValueSet withEnums(/*~~>*/List<JRightPadded<EnumValue>> enums) {
                return t.enums == enums ? t : new EnumValueSet(t.id, t.prefix, t.markers, enums, t.terminatedWithSemicolon);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class FieldAccess implements J, TypeTree, Expression, Statement {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        Expression target;

        JLeftPadded<Identifier> name;

        public Identifier getName() {
            return name.getElement();
        }

        public FieldAccess withName(Identifier name) {
            return getPadding().withName(this.name.withElement(name));
        }

        @With
        @Getter
        @Nullable
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitFieldAccess(this, p);
        }

        public String getSimpleName() {
            return name.getElement().getSimpleName();
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            return target.getSideEffects();
        }

        /**
         * @return For expressions like {@code String.class}, this casts target expression to a {@link NameTree}.
         * If the field access is not a reference to a class type, returns null.
         */
        @Nullable
        public NameTree asClassReference() {
            if (target instanceof NameTree) {
                String fqn = null;
                if (type instanceof JavaType.FullyQualified) {
                    fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
                }
                return "java.lang.Class".equals(fqn) ? (NameTree) target : null;
            }
            return null;
        }

        public boolean isFullyQualifiedClassReference(String className) {
            return isFullyQualifiedClassReference(this, className);
        }

        private boolean isFullyQualifiedClassReference(J.FieldAccess fieldAccess, String className) {
            if (!className.contains(".")) {
                return false;
            }
            if (!fieldAccess.getName().getSimpleName().equals(className.substring(className.lastIndexOf('.') + 1))) {
                return false;
            }
            if (fieldAccess.getTarget() instanceof J.FieldAccess) {
                return isFullyQualifiedClassReference((J.FieldAccess) fieldAccess.getTarget(), className.substring(0, className.lastIndexOf('.')));
            }
            if (fieldAccess.getTarget() instanceof Identifier) {
                return ((Identifier) fieldAccess.getTarget()).getSimpleName().equals(className.substring(0, className.lastIndexOf('.')));
            }
            return false;
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final FieldAccess t;

            public JLeftPadded<Identifier> getName() {
                return t.name;
            }

            public FieldAccess withName(JLeftPadded<Identifier> name) {
                return t.name == name ? t : new FieldAccess(t.id, t.prefix, t.markers, t.target, name, t.type);
            }
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class ForEachLoop implements J, Loop {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        Control control;

        JRightPadded<Statement> body;

        public Statement getBody() {
            return body.getElement();
        }

        @SuppressWarnings("unchecked")
        public ForEachLoop withBody(Statement body) {
            return getPadding().withBody(this.body.withElement(body));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitForEachLoop(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @RequiredArgsConstructor
        @AllArgsConstructor(access = AccessLevel.PRIVATE)
        public static final class Control implements J {
            @Nullable
            @NonFinal
            transient WeakReference<Padding> padding;

            @With
            @EqualsAndHashCode.Include
            @Getter
            UUID id;

            @With
            @Getter
            Space prefix;

            @With
            @Getter
            Markers markers;

            JRightPadded<VariableDeclarations> variable;

            public VariableDeclarations getVariable() {
                return variable.getElement();
            }

            public Control withVariable(VariableDeclarations variable) {
                return getPadding().withVariable(this.variable.withElement(variable));
            }

            JRightPadded<Expression> iterable;

            public Expression getIterable() {
                return iterable.getElement();
            }

            public Control withIterable(Expression iterable) {
                return getPadding().withIterable(this.iterable.withElement(iterable));
            }

            @Override
            public <P> J acceptJava(JavaVisitor<P> v, P p) {
                return v.visitForEachControl(this, p);
            }

            public Padding getPadding() {
                Padding p;
                if (this.padding == null) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                } else {
                    p = this.padding.get();
                    if (p == null || p.t != this) {
                        p = new Padding(this);
                        this.padding = new WeakReference<>(p);
                    }
                }
                return p;
            }

            @Override
            public String toString() {
                return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
            }

            @RequiredArgsConstructor
            public static class Padding {
                private final Control t;

                public JRightPadded<VariableDeclarations> getVariable() {
                    return t.variable;
                }

                public Control withVariable(JRightPadded<VariableDeclarations> variable) {
                    return t.variable == variable ? t : new Control(t.id, t.prefix, t.markers, variable, t.iterable);
                }

                public JRightPadded<Expression> getIterable() {
                    return t.iterable;
                }

                public Control withIterable(JRightPadded<Expression> iterable) {
                    return t.iterable == iterable ? t : new Control(t.id, t.prefix, t.markers, t.variable, iterable);
                }
            }
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final ForEachLoop t;

            public JRightPadded<Statement> getBody() {
                return t.body;
            }

            public ForEachLoop withBody(JRightPadded<Statement> body) {
                return t.body == body ? t : new ForEachLoop(t.id, t.prefix, t.markers, t.control, body);
            }
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class ForLoop implements J, Loop {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        Control control;

        JRightPadded<Statement> body;

        public Statement getBody() {
            return body.getElement();
        }

        @SuppressWarnings("unchecked")
        public ForLoop withBody(Statement body) {
            return getPadding().withBody(this.body.withElement(body));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitForLoop(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @RequiredArgsConstructor
        @AllArgsConstructor(access = AccessLevel.PRIVATE)
        public static final class Control implements J {
            @Nullable
            @NonFinal
            transient WeakReference<Padding> padding;

            @With
            @EqualsAndHashCode.Include
            @Getter
            UUID id;

            @With
            @Getter
            Space prefix;

            @With
            @Getter
            Markers markers;

            /*~~>*/List<JRightPadded<Statement>> init;

            public /*~~>*/List<Statement> getInit() {
                return JRightPadded.getElements(init);
            }

            public Control withInit(/*~~>*/List<Statement> init) {
                return getPadding().withInit(JRightPadded.withElements(this.init, init));
            }

            JRightPadded<Expression> condition;

            public Expression getCondition() {
                return condition.getElement();
            }

            public Control withCondition(Expression condition) {
                return getPadding().withCondition(this.condition.withElement(condition));
            }

            /*~~>*/List<JRightPadded<Statement>> update;

            public /*~~>*/List<Statement> getUpdate() {
                return JRightPadded.getElements(update);
            }

            public Control withUpdate(/*~~>*/List<Statement> update) {
                return getPadding().withUpdate(JRightPadded.withElements(this.update, update));
            }

            @Override
            public <P> J acceptJava(JavaVisitor<P> v, P p) {
                return v.visitForControl(this, p);
            }

            public Padding getPadding() {
                Padding p;
                if (this.padding == null) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                } else {
                    p = this.padding.get();
                    if (p == null || p.t != this) {
                        p = new Padding(this);
                        this.padding = new WeakReference<>(p);
                    }
                }
                return p;
            }

            @Override
            public String toString() {
                return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
            }

            @RequiredArgsConstructor
            public static class Padding {
                private final Control t;

                public /*~~>*/List<JRightPadded<Statement>> getInit() {
                    return t.init;
                }

                public ForLoop.Control withInit(/*~~>*/List<JRightPadded<Statement>> init) {
                    return t.init == init ? t : new ForLoop.Control(t.id, t.prefix, t.markers, init, t.condition, t.update);
                }

                public JRightPadded<Expression> getCondition() {
                    return t.condition;
                }

                public ForLoop.Control withCondition(JRightPadded<Expression> condition) {
                    return t.condition == condition ? t : new ForLoop.Control(t.id, t.prefix, t.markers, t.init, condition, t.update);
                }

                public /*~~>*/List<JRightPadded<Statement>> getUpdate() {
                    return t.update;
                }

                public ForLoop.Control withUpdate(/*~~>*/List<JRightPadded<Statement>> update) {
                    return t.update == update ? t : new ForLoop.Control(t.id, t.prefix, t.markers, t.init, t.condition, update);
                }
            }
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final ForLoop t;

            public JRightPadded<Statement> getBody() {
                return t.body;
            }

            public ForLoop withBody(JRightPadded<Statement> body) {
                return t.body == body ? t : new ForLoop(t.id, t.prefix, t.markers, t.control, body);
            }
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Identifier implements J, TypeTree, Expression {
        @Getter
        @EqualsAndHashCode.Include
        UUID id;

        @Getter
        Space prefix;

        @Getter
        Markers markers;

        String simpleName;

        @Nullable
        JavaType type;

        @Nullable
        JavaType.Variable fieldType;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitIdentifier(this, p);
        }

        public CoordinateBuilder.Identifier getCoordinates() {
            return new CoordinateBuilder.Identifier(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class If implements J, Statement {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        ControlParentheses<Expression> ifCondition;

        JRightPadded<Statement> thenPart;

        public Statement getThenPart() {
            return thenPart.getElement();
        }

        public If withThenPart(Statement thenPart) {
            return getPadding().withThenPart(this.thenPart.withElement(thenPart));
        }

        @With
        @Nullable
        @Getter
        Else elsePart;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitIf(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @ToString
        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @RequiredArgsConstructor
        @AllArgsConstructor(access = AccessLevel.PRIVATE)
        public static final class Else implements J {
            @Nullable
            @NonFinal
            transient WeakReference<Padding> padding;

            @With
            @EqualsAndHashCode.Include
            @Getter
            UUID id;

            @With
            @Getter
            Space prefix;

            @With
            @Getter
            Markers markers;

            JRightPadded<Statement> body;

            public Statement getBody() {
                return body.getElement();
            }

            public Else withBody(Statement body) {
                return getPadding().withBody(this.body.withElement(body));
            }

            @Override
            public <P> J acceptJava(JavaVisitor<P> v, P p) {
                return v.visitElse(this, p);
            }

            public Padding getPadding() {
                Padding p;
                if (this.padding == null) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                } else {
                    p = this.padding.get();
                    if (p == null || p.t != this) {
                        p = new Padding(this);
                        this.padding = new WeakReference<>(p);
                    }
                }
                return p;
            }

            @RequiredArgsConstructor
            public static class Padding {
                private final Else t;

                public JRightPadded<Statement> getBody() {
                    return t.body;
                }

                public Else withBody(JRightPadded<Statement> body) {
                    return t.body == body ? t : new Else(t.id, t.prefix, t.markers, body);
                }
            }
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final If t;

            public JRightPadded<Statement> getThenPart() {
                return t.thenPart;
            }

            public If withThenPart(JRightPadded<Statement> thenPart) {
                return t.thenPart == thenPart ? t : new If(t.id, t.prefix, t.markers, t.ifCondition, thenPart, t.elsePart);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Import implements Statement, Comparable<Import> {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @Getter
        @EqualsAndHashCode.Include
        UUID id;

        @Getter
        @With
        Space prefix;

        @Getter
        @With
        Markers markers;

        JLeftPadded<Boolean> statik;

        @With
        @Getter
        FieldAccess qualid;

        public boolean isStatic() {
            return statik.getElement();
        }

        public Import withStatic(boolean statik) {
            return getPadding().withStatic(this.statik.withElement(statik));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitImport(this, p);
        }

        /**
         * The type name of a statically imported inner class is the outermost class.
         */
        public String getTypeName() {
            if (isStatic()) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(qualid.getType());

                // the compiler doesn't type attribute static imports of classes
                if (fq == null) {
                    String possibleInnerClassFqn = getTypeName(qualid);
                    String possibleInnerClassName = possibleInnerClassFqn.substring(possibleInnerClassFqn.indexOf('$') + 1);
                    if ("*".equals(possibleInnerClassName)) {
                        return possibleInnerClassFqn.substring(0, possibleInnerClassFqn.indexOf('$'));
                    }
                    while (possibleInnerClassName.indexOf('$') >= 0) {
                        possibleInnerClassName = possibleInnerClassName.substring(possibleInnerClassName.indexOf('$') + 1);
                    }

                    JavaType.Class owner = TypeUtils.asClass(qualid.getTarget().getType());
                    if (owner != null && !(qualid.getTarget().getType() instanceof JavaType.ShallowClass)) {
                        Iterator<JavaType.Method> visibleMethods = owner.getVisibleMethods();
                        while (visibleMethods.hasNext()) {
                            JavaType.Method method = visibleMethods.next();
                            if (method.getName().equals(possibleInnerClassName)) {
                                return possibleInnerClassFqn.substring(0, possibleInnerClassFqn.lastIndexOf('$'));
                            }
                        }

                        Iterator<JavaType.Variable> visibleMembers = owner.getVisibleMembers();
                        while (visibleMembers.hasNext()) {
                            JavaType.Variable member = visibleMembers.next();
                            if (member.getName().equals(possibleInnerClassName)) {
                                return possibleInnerClassFqn.substring(0, possibleInnerClassFqn.lastIndexOf('$'));
                            }
                        }

                        return possibleInnerClassFqn;
                    }
                }

                return getTypeName((FieldAccess) qualid.getTarget());
            }

            return getTypeName(qualid);
        }

        private String getTypeName(J.FieldAccess type) {
            StringBuilder typeName = new StringBuilder();

            J.FieldAccess part = type;
            while (true) {
                String name = part.getSimpleName();
                if (part.getTarget() instanceof J.Identifier) {
                    typeName.insert(0, ((Identifier) part.getTarget()).getSimpleName() +
                            "." + name);
                    break;
                } else {
                    part = (FieldAccess) part.getTarget();
                    String delim = Character.isUpperCase(part.getSimpleName().charAt(0)) ? "$" : ".";
                    typeName.insert(0, delim + name);
                }
            }

            return typeName.toString();
        }

        /**
         * Retrieve just the package from the import.
         * e.g.:
         * <code>
         * import org.foo.A;            == "org.foo"
         * import static org.foo.A.bar; == "org.foo"
         * import org.foo.*;            == "org.foo"
         * </code>
         */
        public String getPackageName() {
            String typeName = getTypeName();
            int lastDot = typeName.lastIndexOf('.');
            return lastDot < 0 ? "" : typeName.substring(0, lastDot);
        }

        public String getClassName() {
            String typeName = getTypeName();
            int lastDot = typeName.lastIndexOf('.');
            return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
        }

        @Override
        public int compareTo(Import o) {
            String p1 = this.getPackageName();
            String p2 = o.getPackageName();

            String[] p1s = p1.split("\\.");
            String[] p2s = p2.split("\\.");

            for (int i = 0; i < p1s.length; i++) {
                String s = p1s[i];
                if (p2s.length < i + 1) {
                    return 1;
                }
                if (!s.equals(p2s[i])) {
                    return s.compareTo(p2s[i]);
                }
            }

            return p1s.length < p2s.length ? -1 :
                    this.getQualid().getSimpleName().compareTo(o.getQualid().getSimpleName());
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Import t;

            public JLeftPadded<Boolean> getStatic() {
                return t.statik;
            }

            public Import withStatic(JLeftPadded<Boolean> statik) {
                return t.statik == statik ? t : new Import(t.id, t.prefix, t.markers, statik, t.qualid);
            }
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class InstanceOf implements J, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        JRightPadded<Expression> expression;

        public Expression getExpression() {
            return expression.getElement();
        }

        public InstanceOf withExpression(Expression expression) {
            return getPadding().withExpr(this.expression.withElement(expression));
        }

        @With
        @Getter
        J clazz;

        @With
        @Nullable
        @Getter
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitInstanceOf(this, p);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final InstanceOf t;

            public JRightPadded<Expression> getExpr() {
                return t.expression;
            }

            public InstanceOf withExpr(JRightPadded<Expression> expression) {
                return t.expression == expression ? t : new InstanceOf(t.id, t.prefix, t.markers, expression, t.clazz, t.type);
            }
        }

    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Label implements J, Statement {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        /**
         * Right padded before the ':'
         */
        JRightPadded<Identifier> label;

        public Identifier getLabel() {
            return label.getElement();
        }

        public Label withLabel(Identifier label) {
            return getPadding().withLabel(this.label.withElement(label));
        }

        @With
        @Getter
        Statement statement;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitLabel(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Label t;

            public JRightPadded<Identifier> getLabel() {
                return t.label;
            }

            public Label withLabel(JRightPadded<Identifier> label) {
                return t.label == label ? t : new Label(t.id, t.prefix, t.markers, label, t.statement);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Lambda implements J, Statement, Expression, TypedTree {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        Parameters parameters;

        @With
        Space arrow;

        @With
        J body;

        @With
        @Nullable
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitLambda(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @ToString
        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @RequiredArgsConstructor
        @AllArgsConstructor(access = AccessLevel.PRIVATE)
        public static final class Parameters implements J {
            @Nullable
            @NonFinal
            transient WeakReference<Padding> padding;

            @With
            @EqualsAndHashCode.Include
            @Getter
            UUID id;

            @With
            @Getter
            Space prefix;

            @With
            @Getter
            Markers markers;

            @With
            @Getter
            boolean parenthesized;

            /*~~>*/List<JRightPadded<J>> parameters;

            public /*~~>*/List<J> getParameters() {
                return JRightPadded.getElements(parameters);
            }

            public Parameters withParameters(/*~~>*/List<J> parameters) {
                return getPadding().withParams(JRightPadded.withElements(this.parameters, parameters));
            }

            public CoordinateBuilder.Lambda.Parameters getCoordinates() {
                return new CoordinateBuilder.Lambda.Parameters(this);
            }

            public Padding getPadding() {
                Padding p;
                if (this.padding == null) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                } else {
                    p = this.padding.get();
                    if (p == null || p.t != this) {
                        p = new Padding(this);
                        this.padding = new WeakReference<>(p);
                    }
                }
                return p;
            }

            @RequiredArgsConstructor
            public static class Padding {
                private final Parameters t;

                public /*~~>*/List<JRightPadded<J>> getParams() {
                    return t.parameters;
                }

                public Parameters withParams(/*~~>*/List<JRightPadded<J>> parameters) {
                    return t.parameters == parameters ? t : new Parameters(t.id, t.prefix, t.markers, t.parenthesized, parameters);
                }
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Literal implements J, Expression, TypedTree {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        @Nullable
        Object value;

        @With
        @Nullable
        String valueSource;

        @With
        @Nullable
        /*~~>*/List<UnicodeEscape> unicodeEscapes;

        /**
         * Including String literals
         */
        JavaType.Primitive type;

        @SuppressWarnings("unchecked")
        @Override
        public Literal withType(@Nullable JavaType type) {
            if (type == this.type) {
                return this;
            }
            if (type instanceof JavaType.Primitive) {
                return new Literal(id, prefix, markers, value, valueSource, unicodeEscapes, (JavaType.Primitive) type);
            }
            return this;
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitLiteral(this, p);
        }

        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        /**
         * See <a href="https://docs.oracle.com/javase/specs/jls/se7/html/jls-3.html#jls-3.3">jls-3.3</a>.
         * <p>
         * Unmatched UTF-16 surrogate pairs (composed of two escape and code point pairs) are unserializable
         * by technologies like Jackson. So we separate and store the code point off and reconstruct
         * the escape sequence when printing later.
         * <p>
         * We only escape unicode characters that are part of UTF-16 surrogate pairs. Others are generally
         * treated well by tools like Jackson.
         */
        @Value
        public static class UnicodeEscape {
            @With
            int valueSourceIndex;

            @With
            String codePoint;
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class MemberReference implements J, Expression, TypedTree, MethodCall {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        JRightPadded<Expression> containing;

        public Expression getContaining() {
            return containing.getElement();
        }

        public MemberReference withContaining(Expression containing) {
            //noinspection ConstantConditions
            return getPadding().withContaining(JRightPadded.withElement(this.containing, containing));
        }

        @Nullable
        JContainer<Expression> typeParameters;

        @Nullable
        public /*~~>*/List<Expression> getTypeParameters() {
            return typeParameters == null ? null : typeParameters.getElements();
        }

        public MemberReference withTypeParameters(@Nullable /*~~>*/List<Expression> typeParameters) {
            return getPadding().withTypeParameters(JContainer.withElementsNullable(this.typeParameters, typeParameters));
        }

        @Override
        public /*~~>*/List<Expression> getArguments() {
            return emptyList();
        }

        @Override
        public MemberReference withArguments(/*~~>*/List<Expression> arguments) {
            return this;
        }

        JLeftPadded<Identifier> reference;

        public Identifier getReference() {
            return reference.getElement();
        }

        public MemberReference withReference(Identifier reference) {
            return getPadding().withReference(this.reference.withElement(reference));
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        /**
         * In the case of a method reference, this will be the type of the functional interface that
         * this method reference is supplying.
         */
        @With
        @Nullable
        @Getter
        JavaType type;

        /**
         * In the case of a method reference, this is the method type pointed to by {@link #reference}.
         */
        @With
        @Nullable
        @Getter
        JavaType.Method methodType;

        /**
         * In the case of a field reference, this is the field pointed to by {@link #reference}.
         */
        @With
        @Nullable
        @Getter
        JavaType.Variable variableType;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitMemberReference(this, p);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final MemberReference t;

            public JRightPadded<Expression> getContaining() {
                return t.containing;
            }

            public MemberReference withContaining(JRightPadded<Expression> containing) {
                return t.containing == containing ? t : new MemberReference(t.id, t.prefix, t.markers, containing, t.typeParameters, t.reference, t.type, t.methodType, t.variableType);
            }

            @Nullable
            public JContainer<Expression> getTypeParameters() {
                return t.typeParameters;
            }

            public MemberReference withTypeParameters(@Nullable JContainer<Expression> typeParameters) {
                return t.typeParameters == typeParameters ? t : new MemberReference(t.id, t.prefix, t.markers, t.containing, typeParameters, t.reference, t.type, t.methodType, t.variableType);
            }

            public JLeftPadded<Identifier> getReference() {
                return t.reference;
            }

            public MemberReference withReference(JLeftPadded<Identifier> reference) {
                return t.reference == reference ? t : new MemberReference(t.id, t.prefix, t.markers, t.containing, t.typeParameters, reference, t.type, t.methodType, t.variableType);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class MethodDeclaration implements J, Statement, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @Nullable
        @NonFinal
        transient WeakReference<Annotations> annotations;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        /*~~>*/List<Annotation> leadingAnnotations;

        @With
        @Getter
        /*~~>*/List<Modifier> modifiers;

        @Nullable
        TypeParameters typeParameters;

        @Nullable
        public /*~~>*/List<TypeParameter> getTypeParameters() {
            return typeParameters == null ? null : typeParameters.getTypeParameters();
        }

        public MethodDeclaration withTypeParameters(@Nullable /*~~>*/List<TypeParameter> typeParameters) {
            if (typeParameters == null) {
                if (this.getAnnotations().getTypeParameters() == null) {
                    return this;
                } else {
                    return this.getAnnotations().withTypeParameters(null);
                }
            } else {
                TypeParameters currentTypeParameters = this.getAnnotations().getTypeParameters();
                if (currentTypeParameters == null) {
                    return getAnnotations().withTypeParameters(new TypeParameters(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                            null, typeParameters.stream().map(JRightPadded::build).collect(toList())));
                } else {
                    return getAnnotations().withTypeParameters(currentTypeParameters.withTypeParameters(typeParameters));
                }
            }
        }

        /**
         * Null for constructor declarations.
         */
        @With
        @Getter
        @Nullable
        TypeTree returnTypeExpression;

        IdentifierWithAnnotations name;

        public Identifier getName() {
            return name.getIdentifier();
        }

        public MethodDeclaration withName(Identifier name) {
            return getAnnotations().withName(this.name.withIdentifier(name));
        }

        JContainer<Statement> parameters;

        public /*~~>*/List<Statement> getParameters() {
            return parameters.getElements();
        }

        public MethodDeclaration withParameters(/*~~>*/List<Statement> parameters) {
            return getPadding().withParameters(JContainer.withElements(this.parameters, parameters));
        }

        @Nullable
        JContainer<NameTree> throwz;

        @Nullable
        public /*~~>*/List<NameTree> getThrows() {
            return throwz == null ? null : throwz.getElements();
        }

        public MethodDeclaration withThrows(@Nullable /*~~>*/List<NameTree> throwz) {
            return getPadding().withThrows(JContainer.withElementsNullable(this.throwz, throwz));
        }

        /**
         * Null for abstract method declarations and interface method declarations.
         */
        @With
        @Getter
        @Nullable
        Block body;

        /**
         * For default values on definitions of annotation parameters.
         */
        @Nullable
        JLeftPadded<Expression> defaultValue;

        @Nullable
        public Expression getDefaultValue() {
            return defaultValue == null ? null : defaultValue.getElement();
        }

        public MethodDeclaration withDefaultValue(@Nullable Expression defaultValue) {
            return getPadding().withDefaultValue(JLeftPadded.withElement(this.defaultValue, defaultValue));
        }

        @Getter
        @Nullable
        JavaType.Method methodType;

        public MethodDeclaration withMethodType(@Nullable JavaType.Method type) {
            if (type == this.methodType) {
                return this;
            }
            return new MethodDeclaration(id, prefix, markers, leadingAnnotations, modifiers, typeParameters, returnTypeExpression, name, parameters, throwz, body, defaultValue, type);
        }

        public JavaType getType() {
            return methodType == null ? null : methodType.getReturnType();
        }

        @SuppressWarnings("unchecked")
        @Override
        public MethodDeclaration withType(@Nullable JavaType type) {
            throw new UnsupportedOperationException("To change the return type of this method declaration, use withMethodType(..)");
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitMethodDeclaration(this, p);
        }

        public boolean isAbstract() {
            return body == null;
        }

        public boolean isConstructor() {
            return getReturnTypeExpression() == null;
        }

        public String getSimpleName() {
            return name.getIdentifier().getSimpleName();
        }

        public boolean hasModifier(Modifier.Type modifier) {
            return Modifier.hasModifier(getModifiers(), modifier);
        }

        @Override
        public CoordinateBuilder.MethodDeclaration getCoordinates() {
            return new CoordinateBuilder.MethodDeclaration(this);
        }

        // gather annotations from everywhere they may occur
        public /*~~>*/List<J.Annotation> getAllAnnotations() {
            List<Annotation> allAnnotations = new ArrayList<>(leadingAnnotations);
            for (J.Modifier modifier : modifiers) {
                allAnnotations.addAll(modifier.getAnnotations());
            }
            if (typeParameters != null) {
                allAnnotations.addAll(typeParameters.getAnnotations());
            }
            if (returnTypeExpression instanceof AnnotatedType) {
                allAnnotations.addAll(((AnnotatedType) returnTypeExpression).getAnnotations());
            }
            allAnnotations.addAll(name.getAnnotations());
            return allAnnotations;
        }

        @Override
        public String toString() {
            return "MethodDeclaration{" +
                    (getMethodType() == null ? "unknown" : getMethodType()) +
                    "}";
        }

        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @Data
        public static final class IdentifierWithAnnotations {
            @Getter
            @With
            Identifier identifier;

            @Getter
            @With
            /*~~>*/List<Annotation> annotations;
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final MethodDeclaration t;

            public JContainer<Statement> getParameters() {
                return t.parameters;
            }

            public MethodDeclaration withParameters(JContainer<Statement> parameters) {
                return t.parameters == parameters ? t : new MethodDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, t.typeParameters, t.returnTypeExpression, t.name, parameters, t.throwz, t.body, t.defaultValue, t.methodType);
            }

            @Nullable
            public JContainer<NameTree> getThrows() {
                return t.throwz;
            }

            public MethodDeclaration withThrows(@Nullable JContainer<NameTree> throwz) {
                return t.throwz == throwz ? t : new MethodDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, t.typeParameters, t.returnTypeExpression, t.name, t.parameters, throwz, t.body, t.defaultValue, t.methodType);
            }

            @Nullable
            public JLeftPadded<Expression> getDefaultValue() {
                return t.defaultValue;
            }

            public MethodDeclaration withDefaultValue(@Nullable JLeftPadded<Expression> defaultValue) {
                return t.defaultValue == defaultValue ? t : new MethodDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, t.typeParameters, t.returnTypeExpression, t.name, t.parameters, t.throwz, t.body, defaultValue, t.methodType);
            }

            @Nullable
            public TypeParameters getTypeParameters() {
                return t.typeParameters;
            }

            public MethodDeclaration withTypeParameters(@Nullable TypeParameters typeParameters) {
                return t.typeParameters == typeParameters ? t : new MethodDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, typeParameters, t.returnTypeExpression, t.name, t.parameters, t.throwz, t.body, t.defaultValue, t.methodType);
            }
        }

        public Annotations getAnnotations() {
            Annotations a;
            if (this.annotations == null) {
                a = new Annotations(this);
                this.annotations = new WeakReference<>(a);
            } else {
                a = this.annotations.get();
                if (a == null || a.t != this) {
                    a = new Annotations(this);
                    this.annotations = new WeakReference<>(a);
                }
            }
            return a;
        }

        @RequiredArgsConstructor
        public static class Annotations {
            private final MethodDeclaration t;

            @Nullable
            public TypeParameters getTypeParameters() {
                return t.typeParameters;
            }

            public MethodDeclaration withTypeParameters(@Nullable TypeParameters typeParameters) {
                return t.typeParameters == typeParameters ? t : new MethodDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, typeParameters, t.returnTypeExpression, t.name, t.parameters, t.throwz, t.body, t.defaultValue, t.methodType);
            }

            public IdentifierWithAnnotations getName() {
                return t.name;
            }

            public MethodDeclaration withName(IdentifierWithAnnotations name) {
                return t.name == name ? t : new MethodDeclaration(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, t.typeParameters, t.returnTypeExpression, name, t.parameters, t.throwz, t.body, t.defaultValue, t.methodType);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class MethodInvocation implements J, Statement, Expression, TypedTree, MethodCall {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        /**
         * Right padded before the '.'
         */
        @Nullable
        JRightPadded<Expression> select;

        @Nullable
        public Expression getSelect() {
            return select == null ? null : select.getElement();
        }

        public MethodInvocation withSelect(@Nullable Expression select) {
            return getPadding().withSelect(JRightPadded.withElement(this.select, select));
        }

        @Nullable
        @With
        JContainer<Expression> typeParameters;

        @Nullable
        public /*~~>*/List<Expression> getTypeParameters() {
            return typeParameters == null ? null : typeParameters.getElements();
        }

        @Getter
        Identifier name;

        public MethodInvocation withName(J.Identifier name) {
            if (this.name == name) {
                return this;
            }
            JavaType.Method newType = null;
            if (this.methodType != null) {
                newType = this.methodType.withName(name.getSimpleName());
            }
            return new MethodInvocation(id, prefix, markers, select, typeParameters, name, arguments, newType);
        }

        JContainer<Expression> arguments;

        public /*~~>*/List<Expression> getArguments() {
            return arguments.getElements();
        }

        public MethodInvocation withArguments(/*~~>*/List<Expression> arguments) {
            if (this.arguments.getElements() == arguments) {
                return this;
            }
            return getPadding().withArguments(JContainer.withElements(this.arguments, arguments));
        }

        @Nullable
        @Getter
        JavaType.Method methodType;

        public MethodInvocation withMethodType(@Nullable JavaType.Method type) {
            if (type == this.methodType) {
                return this;
            }
            return new MethodInvocation(id, prefix, markers, select, typeParameters, name, arguments, type);
        }

        @SuppressWarnings("unchecked")
        @Override
        public MethodInvocation withType(@Nullable JavaType type) {
            throw new UnsupportedOperationException("To change the return type of this method invocation, use withMethodType(..)");
        }

        public MethodInvocation withDeclaringType(JavaType.FullyQualified type) {
            if (this.methodType == null) {
                return this;
            } else {
                return withMethodType(this.methodType.withDeclaringType(type));
            }
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitMethodInvocation(this, p);
        }

        @Override
        public CoordinateBuilder.MethodInvocation getCoordinates() {
            return new CoordinateBuilder.MethodInvocation(this);
        }

        @Override
        @Nullable
        public JavaType getType() {
            return methodType == null ? null : methodType.getReturnType();
        }

        public String getSimpleName() {
            return name.getSimpleName();
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            return singletonList(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final MethodInvocation t;

            @Nullable
            public JRightPadded<Expression> getSelect() {
                return t.select;
            }

            public MethodInvocation withSelect(@Nullable JRightPadded<Expression> select) {
                return t.select == select ? t : new MethodInvocation(t.id, t.prefix, t.markers, select, t.typeParameters, t.name, t.arguments, t.methodType);
            }

            @Nullable
            public JContainer<Expression> getTypeParameters() {
                return t.typeParameters;
            }

            public MethodInvocation withTypeParameters(@Nullable JContainer<Expression> typeParameters) {
                return t.typeParameters == typeParameters ? t : new MethodInvocation(t.id, t.prefix, t.markers, t.select, typeParameters, t.name, t.arguments, t.methodType);
            }

            public JContainer<Expression> getArguments() {
                return t.arguments;
            }

            public MethodInvocation withArguments(JContainer<Expression> arguments) {
                return t.arguments == arguments ? t : new MethodInvocation(t.id, t.prefix, t.markers, t.select, t.typeParameters, t.name, arguments, t.methodType);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Modifier implements J {
        public static boolean hasModifier(Collection<Modifier> modifiers, Modifier.Type modifier) {
            return modifiers.stream().anyMatch(m -> m.getType() == modifier);
        }

        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        Type type;

        @With
        @Getter
        /*~~>*/List<Annotation> annotations;

        @Override
        public String toString() {
            return type.toString().toLowerCase();
        }

        /**
         * These types are sorted in order of their recommended appearance in a list of modifiers, as defined in the
         * <a href="https://rules.sonarsource.com/java/tag/convention/RSPEC-1124">JLS</a>.
         */
        public enum Type {
            Default,
            Public,
            Protected,
            Private,
            Abstract,
            Static,
            Final,
            Transient,
            Volatile,
            Synchronized,
            Native,
            Strictfp,
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class MultiCatch implements J, TypeTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        /*~~>*/List<JRightPadded<NameTree>> alternatives;

        public /*~~>*/List<NameTree> getAlternatives() {
            return JRightPadded.getElements(alternatives);
        }

        public MultiCatch withAlternatives(/*~~>*/List<NameTree> alternatives) {
            return getPadding().withAlternatives(JRightPadded.withElements(this.alternatives, alternatives));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitMultiCatch(this, p);
        }

        @SuppressWarnings("unchecked")
        @Override
        public MultiCatch withType(@Nullable JavaType type) {
            // cannot overwrite type directly, perform this operation on each alternative separately
            return this;
        }

        @Override
        public JavaType getType() {
            return new JavaType.MultiCatch(alternatives.stream()
                    .filter(Objects::nonNull)
                    .map(alt -> alt.getElement().getType())
                    .collect(toList()));
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final MultiCatch t;

            public /*~~>*/List<JRightPadded<NameTree>> getAlternatives() {
                return t.alternatives;
            }

            public MultiCatch withAlternatives(/*~~>*/List<JRightPadded<NameTree>> alternatives) {
                return t.alternatives == alternatives ? t : new MultiCatch(t.id, t.prefix, t.markers, alternatives);
            }
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class NewArray implements J, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Nullable
        @Getter
        TypeTree typeExpression;

        @With
        @Getter
        /*~~>*/List<ArrayDimension> dimensions;

        @Nullable
        JContainer<Expression> initializer;

        @Nullable
        public /*~~>*/List<Expression> getInitializer() {
            return initializer == null ? null : initializer.getElements();
        }

        public NewArray withInitializer(/*~~>*/List<Expression> initializer) {
            return getPadding().withInitializer(JContainer.withElementsNullable(this.initializer, initializer));
        }

        @With
        @Nullable
        @Getter
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitNewArray(this, p);
        }

        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final NewArray t;

            @Nullable
            public JContainer<Expression> getInitializer() {
                return t.initializer;
            }

            public NewArray withInitializer(@Nullable JContainer<Expression> initializer) {
                return t.initializer == initializer ? t : new NewArray(t.id, t.prefix, t.markers, t.typeExpression, t.dimensions, initializer, t.type);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class ArrayDimension implements J {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        JRightPadded<Expression> index;

        public Expression getIndex() {
            return index.getElement();
        }

        public ArrayDimension withIndex(Expression index) {
            return getPadding().withIndex(this.index.withElement(index));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitArrayDimension(this, p);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final ArrayDimension t;

            public JRightPadded<Expression> getIndex() {
                return t.index;
            }

            public ArrayDimension withIndex(JRightPadded<Expression> index) {
                return t.index == index ? t : new ArrayDimension(t.id, t.prefix, t.markers, index);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class NewClass implements J, Statement, Expression, TypedTree, MethodCall {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @Getter
        @EqualsAndHashCode.Include
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        /**
         * For situations like <code>this.new A()</code>.
         * Right padded before the '.'
         */
        @Nullable
        JRightPadded<Expression> enclosing;

        @Nullable
        public Expression getEnclosing() {
            return enclosing == null ? null : enclosing.getElement();
        }

        public NewClass withEnclosing(Expression enclosing) {
            return getPadding().withEnclosing(JRightPadded.withElement(this.enclosing, enclosing));
        }

        Space nooh;

        public Space getNew() {
            return nooh;
        }

        public NewClass withNew(Space nooh) {
            if (nooh == this.nooh) {
                return this;
            }
            return new NewClass(id, prefix, markers, enclosing, nooh, clazz, arguments, body, constructorType);
        }

        @Nullable
        @With
        @Getter
        TypeTree clazz;

        JContainer<Expression> arguments;

        public /*~~>*/List<Expression> getArguments() {
            return arguments.getElements();
        }

        public NewClass withArguments(/*~~>*/List<Expression> arguments) {
            return getPadding().withArguments(JContainer.withElements(this.arguments, arguments));
        }

        @With
        @Nullable
        @Getter
        Block body;

        @With
        @Nullable
        @Getter
        JavaType.Method constructorType;

        @Nullable
        @Override
        public JavaType getType() {
            return constructorType == null ? null : constructorType.getDeclaringType();
        }

        /**
         * This is an alias for {@link #getConstructorType()}.
         * @return The constructor type.
         */
        @Nullable
        public JavaType.Method getMethodType() {
            return getConstructorType();
        }

        /**
         * This is an alias for {@link #withConstructorType(JavaType.Method)}.
         * @param methodType The constructor type.
         * @return An instance with the new constructor type.
         */
        public NewClass withMethodType(@Nullable JavaType.Method methodType) {
            return withConstructorType(methodType);
        }

        @SuppressWarnings("unchecked")
        @Override
        public NewClass withType(@Nullable JavaType type) {
            throw new UnsupportedOperationException("To change the return type of this new class, use withConstructorType(..)");
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitNewClass(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            return singletonList(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final NewClass t;

            @Nullable
            public JRightPadded<Expression> getEnclosing() {
                return t.enclosing;
            }

            public NewClass withEnclosing(@Nullable JRightPadded<Expression> enclosing) {
                return t.enclosing == enclosing ? t : new NewClass(t.id, t.prefix, t.markers, enclosing, t.nooh, t.clazz, t.arguments, t.body, t.constructorType);
            }

            public JContainer<Expression> getArguments() {
                return t.arguments;
            }

            public NewClass withArguments(JContainer<Expression> arguments) {
                return t.arguments == arguments ? t : new NewClass(t.id, t.prefix, t.markers, t.enclosing, t.nooh, t.clazz, arguments, t.body, t.constructorType);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Package implements Statement, J {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        Expression expression;

        @With
        /*~~>*/List<Annotation> annotations;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitPackage(this, p);
        }

        public CoordinateBuilder.Package getCoordinates() {
            return new CoordinateBuilder.Package(this);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class ParameterizedType implements J, TypeTree, Expression {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        NameTree clazz;

        @Nullable
        JContainer<Expression> typeParameters;

        @Nullable
        public /*~~>*/List<Expression> getTypeParameters() {
            return typeParameters == null ? null : typeParameters.getElements();
        }

        public ParameterizedType withTypeParameters(@Nullable /*~~>*/List<Expression> typeParameters) {
            return getPadding().withTypeParameters(JContainer.withElementsNullable(this.typeParameters, typeParameters));
        }

        @Override
        public JavaType getType() {
            return clazz.getType();
        }

        @SuppressWarnings("unchecked")
        @Override
        public ParameterizedType withType(@Nullable JavaType type) {
            if (type == clazz.getType()) {
                return this;
            }
            return withClazz(clazz.withType(type));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitParameterizedType(this, p);
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final ParameterizedType t;

            @Nullable
            public JContainer<Expression> getTypeParameters() {
                return t.typeParameters;
            }

            public ParameterizedType withTypeParameters(@Nullable JContainer<Expression> typeParameters) {
                return t.typeParameters == typeParameters ? t : new ParameterizedType(t.id, t.prefix, t.markers, t.clazz, typeParameters);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    class Parentheses<J2 extends J> implements J, Expression {
        @Nullable
        @NonFinal
        transient WeakReference<Padding<J2>> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        JRightPadded<J2> tree;

        public J2 getTree() {
            return tree.getElement();
        }

        public Parentheses<J2> withTree(J2 tree) {
            return getPadding().withTree(this.tree.withElement(tree));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitParentheses(this, p);
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            return tree.getElement() instanceof Expression ? ((Expression) tree.getElement()).getSideEffects() : emptyList();
        }

        @Override
        public JavaType getType() {
            return tree.getElement() instanceof Expression ? ((Expression) tree.getElement()).getType() :
                    tree.getElement() instanceof NameTree ? ((NameTree) tree.getElement()).getType() :
                            null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Parentheses<J2> withType(@Nullable JavaType type) {
            return tree.getElement() instanceof Expression ? withTree(((Expression) tree.getElement()).withType(type)) :
                    tree.getElement() instanceof NameTree ? withTree(((NameTree) tree.getElement()).withType(type)) :
                            this;
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        public Padding<J2> getPadding() {
            Padding<J2> p;
            if (this.padding == null) {
                p = new Padding<>(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding<>(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding<J2 extends J> {
            private final Parentheses<J2> t;

            public JRightPadded<J2> getTree() {
                return t.tree;
            }

            public Parentheses<J2> withTree(JRightPadded<J2> tree) {
                return t.tree == tree ? t : new Parentheses<>(t.id, t.prefix, t.markers, tree);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class ControlParentheses<J2 extends J> implements J, Expression {
        @Nullable
        @NonFinal
        transient WeakReference<Padding<J2>> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        JRightPadded<J2> tree;

        public J2 getTree() {
            return tree.getElement();
        }

        public ControlParentheses<J2> withTree(J2 tree) {
            return getPadding().withTree(this.tree.withElement(tree));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitControlParentheses(this, p);
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            return tree.getElement() instanceof Expression ? ((Expression) tree.getElement()).getSideEffects() : emptyList();
        }

        @Override
        public JavaType getType() {
            J2 element = tree.getElement();
            if (element instanceof Expression) {
                return ((Expression) element).getType();
            }
            if (element instanceof NameTree) {
                return ((NameTree) element).getType();
            }
            if (element instanceof J.VariableDeclarations) {
                return ((VariableDeclarations) element).getType();
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public ControlParentheses<J2> withType(@Nullable JavaType type) {
            return tree.getElement() instanceof Expression ? withTree(((Expression) tree.getElement()).withType(type)) :
                    tree.getElement() instanceof NameTree ? withTree(((NameTree) tree.getElement()).withType(type)) :
                            this;
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        public Padding<J2> getPadding() {
            Padding<J2> p;
            if (this.padding == null) {
                p = new Padding<>(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding<>(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding<J2 extends J> {
            private final ControlParentheses<J2> t;

            public JRightPadded<J2> getTree() {
                return t.tree;
            }

            public ControlParentheses<J2> withTree(JRightPadded<J2> tree) {
                return t.tree == tree ? t : new ControlParentheses<>(t.id, t.prefix, t.markers, tree);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @AllArgsConstructor
    final class Primitive implements J, TypeTree, Expression {
        @With
        @Getter
        @EqualsAndHashCode.Include
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        JavaType.Primitive type;

        @SuppressWarnings("unchecked")
        @Override
        public Primitive withType(@Nullable JavaType type) {
            if (type == this.type) {
                return this;
            }
            if (!(type instanceof JavaType.Primitive)) {
                throw new IllegalArgumentException("Cannot apply a non-primitive type to Primitive");
            }
            return new Primitive(id, prefix, markers, (JavaType.Primitive) type);
        }

        @Override
        @NonNull
        public JavaType.Primitive getType() {
            return type;
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitPrimitive(this, p);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Return implements J, Statement {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        @Nullable
        Expression expression;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitReturn(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Switch implements J, Statement {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        ControlParentheses<Expression> selector;

        @With
        Block cases;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitSwitch(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Synchronized implements J, Statement {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        ControlParentheses<Expression> lock;

        @With
        Block body;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitSynchronized(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Ternary implements J, Expression, Statement, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        Expression condition;

        JLeftPadded<Expression> truePart;

        public Expression getTruePart() {
            return truePart.getElement();
        }

        public Ternary withTruePart(Expression truePart) {
            return getPadding().withTruePart(this.truePart.withElement(truePart));
        }

        JLeftPadded<Expression> falsePart;

        public Expression getFalsePart() {
            return falsePart.getElement();
        }

        public Ternary withFalsePart(Expression falsePart) {
            return getPadding().withFalsePart(this.falsePart.withElement(falsePart));
        }

        @With
        @Nullable
        @Getter
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitTernary(this, p);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Ternary t;

            public JLeftPadded<Expression> getTruePart() {
                return t.truePart;
            }

            public Ternary withTruePart(JLeftPadded<Expression> truePart) {
                return t.truePart == truePart ? t : new Ternary(t.id, t.prefix, t.markers, t.condition, truePart, t.falsePart, t.type);
            }

            public JLeftPadded<Expression> getFalsePart() {
                return t.falsePart;
            }

            public Ternary withFalsePart(JLeftPadded<Expression> falsePart) {
                return t.falsePart == falsePart ? t : new Ternary(t.id, t.prefix, t.markers, t.condition, t.truePart, falsePart, t.type);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class Throw implements J, Statement {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        Expression exception;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitThrow(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Try implements J, Statement {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @Getter
        @EqualsAndHashCode.Include
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @Nullable
        JContainer<Resource> resources;

        @Nullable
        public /*~~>*/List<Resource> getResources() {
            return resources == null ? null : resources.getElements();
        }

        public Try withResources(@Nullable /*~~>*/List<Resource> resources) {
            return getPadding().withResources(JContainer.withElementsNullable(this.resources, resources));
        }

        @With
        @Getter
        Block body;

        @With
        @Getter
        /*~~>*/List<Catch> catches;

        @Nullable
        JLeftPadded<Block> finallie;

        @Nullable
        public Block getFinally() {
            return finallie == null ? null : finallie.getElement();
        }

        public Try withFinally(@Nullable Block finallie) {
            return getPadding().withFinally(JLeftPadded.withElement(this.finallie, finallie));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitTry(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @Data
        public static final class Resource implements J {
            @With
            @EqualsAndHashCode.Include
            UUID id;

            @With
            Space prefix;

            @With
            Markers markers;

            @With
            TypedTree variableDeclarations;

            /**
             * Only honored on the last resource in a collection of resources.
             */
            @With
            boolean terminatedWithSemicolon;

            @Override
            public <P> J acceptJava(JavaVisitor<P> v, P p) {
                return v.visitTryResource(this, p);
            }
        }

        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @Data
        public static final class Catch implements J {
            @With
            @EqualsAndHashCode.Include
            UUID id;

            @With
            Space prefix;

            @With
            Markers markers;

            @With
            ControlParentheses<VariableDeclarations> parameter;

            @With
            Block body;

            @Override
            public <P> J acceptJava(JavaVisitor<P> v, P p) {
                return v.visitCatch(this, p);
            }

            @Override
            public String toString() {
                return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
            }
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Try t;

            @Nullable
            public JContainer<Resource> getResources() {
                return t.resources;
            }

            public Try withResources(@Nullable JContainer<Resource> resources) {
                return t.resources == resources ? t : new Try(t.id, t.prefix, t.markers, resources, t.body, t.catches, t.finallie);
            }

            @Nullable
            public JLeftPadded<Block> getFinally() {
                return t.finallie;
            }

            public Try withFinally(@Nullable JLeftPadded<Block> finallie) {
                return t.finallie == finallie ? t : new Try(t.id, t.prefix, t.markers, t.resources, t.body, t.catches, finallie);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    final class TypeCast implements J, Expression {
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        ControlParentheses<TypeTree> clazz;

        @With
        Expression expression;

        @Override
        public JavaType getType() {
            return clazz.getType();
        }

        @SuppressWarnings("unchecked")
        @Override
        public TypeCast withType(@Nullable JavaType type) {
            return withClazz(clazz.withType(type));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitTypeCast(this, p);
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class TypeParameter implements J {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        /*~~>*/List<Annotation> annotations;

        /**
         * Will be either a {@link TypeTree} or {@link Wildcard}. Wildcards aren't possible in
         * every context where type parameters may be defined (e.g. not possible on new statements).
         */
        @With
        @Getter
        Expression name;

        @Nullable
        JContainer<TypeTree> bounds;

        @Nullable
        public /*~~>*/List<TypeTree> getBounds() {
            return bounds == null ? null : bounds.getElements();
        }

        public TypeParameter withBounds(@Nullable /*~~>*/List<TypeTree> bounds) {
            return getPadding().withBounds(JContainer.withElementsNullable(this.bounds, bounds));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitTypeParameter(this, p);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final TypeParameter t;

            @Nullable
            public JContainer<TypeTree> getBounds() {
                return t.bounds;
            }

            public TypeParameter withBounds(@Nullable JContainer<TypeTree> bounds) {
                return t.bounds == bounds ? t : new TypeParameter(t.id, t.prefix, t.markers, t.annotations, t.name, bounds);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class TypeParameters implements J {
        @Nullable
        @NonFinal
        transient WeakReference<TypeParameters.Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        /*~~>*/List<Annotation> annotations;

        /*~~>*/List<JRightPadded<TypeParameter>> typeParameters;

        public /*~~>*/List<TypeParameter> getTypeParameters() {
            return JRightPadded.getElements(typeParameters);
        }

        public TypeParameters withTypeParameters(/*~~>*/List<TypeParameter> typeParameters) {
            return getPadding().withTypeParameters(JRightPadded.withElements(this.typeParameters, typeParameters));
        }

        public TypeParameters.Padding getPadding() {
            TypeParameters.Padding p;
            if (this.padding == null) {
                p = new TypeParameters.Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new TypeParameters.Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final TypeParameters t;

            public /*~~>*/List<JRightPadded<TypeParameter>> getTypeParameters() {
                return t.typeParameters;
            }

            public TypeParameters withTypeParameters(/*~~>*/List<JRightPadded<TypeParameter>> typeParameters) {
                return t.typeParameters == typeParameters ? t : new TypeParameters(t.id, t.prefix, t.markers, t.annotations, typeParameters);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Unary implements J, Statement, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        JLeftPadded<Type> operator;

        public Type getOperator() {
            return operator.getElement();
        }

        public Unary withOperator(Type operator) {
            return getPadding().withOperator(this.operator.withElement(operator));
        }

        @With
        @Getter
        Expression expression;

        @With
        @Nullable
        @Getter
        JavaType type;

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitUnary(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @Override
        public /*~~>*/List<J> getSideEffects() {
            return expression.getSideEffects();
        }

        public enum Type {
            PreIncrement,
            PreDecrement,
            PostIncrement,
            PostDecrement,
            Positive,
            Negative,
            Complement,
            Not
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Unary t;

            public JLeftPadded<Type> getOperator() {
                return t.operator;
            }

            public Unary withOperator(JLeftPadded<Type> operator) {
                return t.operator == operator ? t : new Unary(t.id, t.prefix, t.markers, operator, t.expression, t.type);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class VariableDeclarations implements J, Statement, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        /*~~>*/List<Annotation> leadingAnnotations;

        @With
        @Getter
        /*~~>*/List<Modifier> modifiers;

        @With
        @Nullable
        @Getter
        TypeTree typeExpression;

        @With
        @Nullable
        @Getter
        Space varargs;

        @With
        @Getter
        /*~~>*/List<JLeftPadded<Space>> dimensionsBeforeName;

        /*~~>*/List<JRightPadded<NamedVariable>> variables;

        public /*~~>*/List<NamedVariable> getVariables() {
            return JRightPadded.getElements(variables);
        }

        public VariableDeclarations withVariables(/*~~>*/List<NamedVariable> vars) {
            return getPadding().withVariables(JRightPadded.withElements(this.variables, vars));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitVariableDeclarations(this, p);
        }

        @Override
        public CoordinateBuilder.VariableDeclarations getCoordinates() {
            return new CoordinateBuilder.VariableDeclarations(this);
        }

        // gather annotations from everywhere they may occur
        public /*~~>*/List<J.Annotation> getAllAnnotations() {
            List<Annotation> allAnnotations = new ArrayList<>(leadingAnnotations);
            for (J.Modifier modifier : modifiers) {
                allAnnotations.addAll(modifier.getAnnotations());
            }
            if (typeExpression != null && typeExpression instanceof J.AnnotatedType) {
                allAnnotations.addAll(((J.AnnotatedType) typeExpression).getAnnotations());
            }
            return allAnnotations;
        }

        @Nullable
        public JavaType.FullyQualified getTypeAsFullyQualified() {
            return typeExpression == null ? null : TypeUtils.asFullyQualified(typeExpression.getType());
        }

        @Nullable
        @Override
        public JavaType getType() {
            return typeExpression == null ? null : typeExpression.getType();
        }

        @SuppressWarnings("unchecked")
        @Override
        public VariableDeclarations withType(@Nullable JavaType type) {
            return typeExpression == null ? this :
                    withTypeExpression(typeExpression.withType(type));
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @RequiredArgsConstructor
        @AllArgsConstructor(access = AccessLevel.PRIVATE)
        public static final class NamedVariable implements J, NameTree {
            @Nullable
            @NonFinal
            transient WeakReference<Padding> padding;

            @With
            @EqualsAndHashCode.Include
            @Getter
            UUID id;

            @With
            @Getter
            Space prefix;

            @With
            @Getter
            Markers markers;

            @With
            @Getter
            Identifier name;

            @With
            @Getter
            /*~~>*/List<JLeftPadded<Space>> dimensionsAfterName;

            @Nullable
            JLeftPadded<Expression> initializer;

            @Nullable
            public Expression getInitializer() {
                return initializer == null ? null : initializer.getElement();
            }

            public NamedVariable withInitializer(@Nullable Expression initializer) {
                if (initializer == null) {
                    return this.initializer == null ? this : new NamedVariable(id, prefix, markers, name, dimensionsAfterName, null, variableType);
                }
                return getPadding().withInitializer(JLeftPadded.withElement(this.initializer, initializer));
            }

            @With
            @Nullable
            @Getter
            JavaType.Variable variableType;

            public JavaType getType() {
                return variableType != null ? variableType.getType() : null;
            }

            @SuppressWarnings("unchecked")
            @Override
            public NamedVariable withType(@Nullable JavaType type) {
                return variableType != null ? withVariableType(variableType.withType(type)) : this;
            }

            public String getSimpleName() {
                return name.getSimpleName();
            }

            @Override
            public <P> J acceptJava(JavaVisitor<P> v, P p) {
                return v.visitVariable(this, p);
            }

            public boolean isField(Cursor cursor) {
                return cursor
                        .getParentOrThrow() // JRightPadded
                        .getParentOrThrow() // J.VariableDeclarations
                        .getParentOrThrow() // JRightPadded
                        .getParentOrThrow() // J.Block
                        .getParentOrThrow() // maybe J.ClassDeclaration
                        .getValue() instanceof ClassDeclaration;
            }

            public Padding getPadding() {
                Padding p;
                if (this.padding == null) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                } else {
                    p = this.padding.get();
                    if (p == null || p.t != this) {
                        p = new Padding(this);
                        this.padding = new WeakReference<>(p);
                    }
                }
                return p;
            }

            @Override
            public String toString() {
                return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
            }

            @RequiredArgsConstructor
            public static class Padding {
                private final NamedVariable t;

                @Nullable
                public JLeftPadded<Expression> getInitializer() {
                    return t.initializer;
                }

                public NamedVariable withInitializer(@Nullable JLeftPadded<Expression> initializer) {
                    return t.initializer == initializer ? t : new NamedVariable(t.id, t.prefix, t.markers, t.name, t.dimensionsAfterName, initializer, t.variableType);
                }
            }
        }

        public boolean hasModifier(Modifier.Type modifier) {
            return Modifier.hasModifier(getModifiers(), modifier);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final VariableDeclarations t;

            public /*~~>*/List<JRightPadded<NamedVariable>> getVariables() {
                return t.variables;
            }

            public VariableDeclarations withVariables(/*~~>*/List<JRightPadded<NamedVariable>> variables) {
                return t.variables == variables ? t : new VariableDeclarations(t.id, t.prefix, t.markers, t.leadingAnnotations, t.modifiers, t.typeExpression, t.varargs, t.dimensionsBeforeName, variables);
            }
        }
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class WhileLoop implements J, Loop {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @With
        @Getter
        ControlParentheses<Expression> condition;

        JRightPadded<Statement> body;

        public Statement getBody() {
            return body.getElement();
        }

        @SuppressWarnings("unchecked")
        public WhileLoop withBody(Statement body) {
            return getPadding().withBody(this.body.withElement(body));
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitWhileLoop(this, p);
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final WhileLoop t;

            public JRightPadded<Statement> getBody() {
                return t.body;
            }

            public WhileLoop withBody(JRightPadded<Statement> body) {
                return t.body == body ? t : new WhileLoop(t.id, t.prefix, t.markers, t.condition, body);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class Wildcard implements J, Expression, TypeTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @With
        @EqualsAndHashCode.Include
        @Getter
        UUID id;

        @With
        @Getter
        Space prefix;

        @With
        @Getter
        Markers markers;

        @Nullable
        JLeftPadded<Bound> bound;

        @Nullable
        public Bound getBound() {
            return bound == null ? null : bound.getElement();
        }

        public Wildcard withBound(@Nullable Bound bound) {
            return getPadding().withBound(JLeftPadded.withElement(this.bound, bound));
        }

        @With
        @Nullable
        @Getter
        NameTree boundedType;

        @Override
        public JavaType getType() {
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Wildcard withType(@Nullable JavaType type) {
            return this;
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return v.visitWildcard(this, p);
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        public enum Bound {
            Extends,
            Super
        }

        public Padding getPadding() {
            Padding p;
            if (this.padding == null) {
                p = new Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @Override
        public String toString() {
            return withPrefix(Space.EMPTY).printTrimmed(new JavaPrinter<>());
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final Wildcard t;

            @Nullable
            public JLeftPadded<Bound> getBound() {
                return t.bound;
            }

            public Wildcard withBound(@Nullable JLeftPadded<Bound> bound) {
                return t.bound == bound ? t : new Wildcard(t.id, t.prefix, t.markers, bound, t.boundedType);
            }
        }
    }
}
