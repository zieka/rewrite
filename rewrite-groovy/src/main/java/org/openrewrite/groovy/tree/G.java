/*
 * Copyright 2021 the original author or authors.
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
package org.openrewrite.groovy.tree;

import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.openrewrite.*;
import org.openrewrite.groovy.GroovyPrinter;
import org.openrewrite.groovy.GroovyVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.internal.TypesInUse;
import org.openrewrite.java.search.FindTypes;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public interface G extends J {
    @SuppressWarnings("unchecked")
    @Override
    default <R extends Tree, P> R accept(TreeVisitor<R, P> v, P p) {
        if (v instanceof GroovyVisitor) {
            return (R) acceptGroovy((GroovyVisitor<P>) v, p);
        }
        return (R) acceptJava((JavaVisitor<P>) v, p);
    }

    @Nullable
    default <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
        return v.defaultValue(this, p);
    }

    Space getPrefix();

    default /*~~>*/List<Comment> getComments() {
        return getPrefix().getComments();
    }

    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class CompilationUnit implements G, JavaSourceFile, SourceFile {
        @Nullable
        @NonFinal
        transient SoftReference<TypesInUse> typesInUse;

        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @EqualsAndHashCode.Include
        @With
        @Getter
        UUID id;

        @With
        @Getter
        @Nullable
        String shebang;

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

        public G.CompilationUnit withPackageDeclaration(Package packageDeclaration) {
            return getPadding().withPackageDeclaration(JRightPadded.withElement(this.packageDeclaration, packageDeclaration));
        }

        /*~~>*/List<JRightPadded<Statement>> statements;

        public /*~~>*/List<Statement> getStatements() {
            return JRightPadded.getElements(statements);
        }

        public G.CompilationUnit withStatements(/*~~>*/List<Statement> statements) {
            return getPadding().withStatements(JRightPadded.withElements(/*~~>*/this.statements, statements));
        }

        @With
        @Getter
        Space eof;

        public /*~~>*/List<Import> getImports() {
            return statements.stream()
                    .map(JRightPadded::getElement)
                    .filter(J.Import.class::isInstance)
                    .map(J.Import.class::cast)
                    .collect(Collectors.toList());
        }

        /**
         * This will move all imports to the front of every other statement in the file.
         * If the result is no change, then the original instance is returned.
         *
         * @param imports The imports to use.
         * @return This compilation unit with new imports.
         */
        public G.CompilationUnit withImports(/*~~>*/List<Import> imports) {
//            List<Statement> after = ListUtils.concatAll(
//                    imports.stream()
//                            .map(s -> (Statement) s)
//                            .collect(Collectors.toList()),
//                    statements.stream()
//                            .map(JRightPadded::getElement)
//                            .filter(s -> !(s instanceof Import))
//                            .collect(Collectors.toList()));
//
//            if (after.size() != statements.size()) {
//                return padding.withStatements(after);
//            }
//
//            for (int i = 0; i < statements.size(); i++) {
//                Statement statement = statements.get(i);
//                if (after.get(i) != statement) {
//                    return withStatements(after);
//                }
//            }

            // TODO implement me!
            return this;
        }

        public /*~~>*/List<ClassDeclaration> getClasses() {
            return statements.stream()
                    .map(JRightPadded::getElement)
                    .filter(J.ClassDeclaration.class::isInstance)
                    .map(J.ClassDeclaration.class::cast)
                    .collect(Collectors.toList());
        }

        /**
         * This will move all classes to after last import. Every other statement which is neither
         * an import or class declaration will appear last.
         * <p>
         * If the result is no change, then the original instance is returned.
         *
         * @param classes The classes to use.
         * @return This compilation unit with new classes.
         */
        public G.CompilationUnit withClasses(/*~~>*/List<ClassDeclaration> classes) {
            // TODO implement me!
            return this;
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            return new GroovyVisitor<P>() {
                @Override
                public J visit(@Nullable Tree tree, P p) {
                    return tree instanceof G.CompilationUnit ?
                            visitJavaSourceFile((JavaSourceFile) v.visitJavaSourceFile((G.CompilationUnit) tree, p), p) :
                            v.visit(tree, p);
                }
            }.visit(this, p);
        }

        @Override
        public <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
            return v.visitJavaSourceFile(this, p);
        }

        public Set<NameTree> findType(String clazz) {
            return FindTypes.find(this, clazz);
        }

        @Override
        public <P> TreeVisitor<?, PrintOutputCapture<P>> printer(Cursor cursor) {
            return new GroovyPrinter<>();
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
            private final G.CompilationUnit t;

            @Nullable
            public JRightPadded<Package> getPackageDeclaration() {
                return t.packageDeclaration;
            }

            public G.CompilationUnit withPackageDeclaration(@Nullable JRightPadded<Package> packageDeclaration) {
                return t.packageDeclaration == packageDeclaration ? t :
                        new G.CompilationUnit(t.id, t.shebang, t.prefix, t.markers, t.sourcePath, t.fileAttributes,
                                t.charsetName, t.charsetBomMarked, t.checksum, packageDeclaration, /*~~>*/t.statements, t.eof);

            }

            @Override
            public /*~~>*/List<JRightPadded<Import>> getImports() {
                //noinspection unchecked
                return /*~~>*/t.statements.stream()
                        .filter(s -> s.getElement() instanceof J.Import)
                        .map(s -> (JRightPadded<J.Import>) (Object) s)
                        .collect(Collectors.toList());
            }

            @Override
            public G.CompilationUnit withImports(/*~~>*/List<JRightPadded<Import>> imports) {
                // TODO implement me!
                return t;
//                return t.imports == imports ? t : new G.CompilationUnit(t.id, t.shebang, t.prefix, t.markers, t.sourcePath, t.packageDeclaration, imports, t.classes, t.eof);
            }

            public /*~~>*/List<JRightPadded<Statement>> getStatements() {
                return t.statements;
            }

            public G.CompilationUnit withStatements(/*~~>*/List<JRightPadded<Statement>> statements) {
                return /*~~>*/t.statements == statements ? t : new G.CompilationUnit(t.id, t.shebang, t.prefix, t.markers, t.sourcePath,
                        t.fileAttributes, t.charsetName, t.charsetBomMarked, t.checksum, t.packageDeclaration, statements, t.eof);
            }
        }
    }

    /**
     * Unlike Java, Groovy allows expressions to appear anywhere Statements do.
     * Rather than re-define versions of the many J types that implement Expression to also implement Statement,
     * just wrap such expressions.
     *
     * Has no state or behavior of its own aside from the Expression it wraps.
     */
    @SuppressWarnings("unchecked")
    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @AllArgsConstructor
    final class ExpressionStatement implements G, Expression, Statement {
        @With
        @Getter
        Expression expression;

        @Override
        public <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
            return acceptJava(v, p);
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            J j = v.visit(getExpression(), p);
            if(j instanceof ExpressionStatement) {
                return j;
            } else if (j instanceof Expression) {
                return withExpression((Expression) j);
            }
            return j;
        }

        @Override
        public UUID getId() {
            return expression.getId();
        }

        @Override
        public <T extends Tree> T withId(UUID id) {
            return (T) withExpression(expression.withId(id));
        }

        @Override
        public <J2 extends J> J2 withPrefix(Space space) {
            return (J2) withExpression(expression.withPrefix(space));
        }

        @Override
        public Space getPrefix() {
            return expression.getPrefix();
        }

        @Override
        public <J2 extends J> J2 withMarkers(Markers markers) {
            return (J2) withExpression(expression.withMarkers(markers));
        }

        @Override
        public Markers getMarkers() {
            return expression.getMarkers();
        }

        @Override
        public @Nullable JavaType getType() {
            return expression.getType();
        }

        @Override
        public <T extends J> T withType(@Nullable JavaType type) {
            return (T) withExpression(expression.withType(type));
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
    final class MapEntry implements G, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @Getter
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @Getter
        @With
        Space prefix;

        @Getter
        @With
        Markers markers;

        JRightPadded<Expression> key;

        public Expression getKey() {
            return key.getElement();
        }

        public MapEntry withKey(@Nullable Expression key) {
            return getPadding().withKey(JRightPadded.withElement(this.key, key));
        }

        @Getter
        @With
        Expression value;

        @Getter
        @With
        @Nullable
        JavaType type;

        @Override
        public <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
            return v.visitMapEntry(this, p);
        }

        @Override
        public @Nullable <P> J acceptJava(JavaVisitor<P> v, P p) {
            G.MapEntry m = this;
            m = m.withType(v.visitType(type, p));
            return m;
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
            private final MapEntry t;

            @Nullable
            public JRightPadded<Expression> getKey() {
                return t.key;
            }

            public MapEntry withKey(@Nullable JRightPadded<Expression> key) {
                return t.key == key ? t : new MapEntry(t.id, t.prefix, t.markers, key, t.value, t.type);
            }
        }
    }


    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class MapLiteral implements G, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @Getter
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @Getter
        @With
        Space prefix;

        @Getter
        @With
        Markers markers;

        JContainer<G.MapEntry> elements;

        public /*~~>*/List<G.MapEntry> getElements() {
            return elements.getElements();
        }

        public MapLiteral withElements(/*~~>*/List<G.MapEntry> elements) {
            return getPadding().withElements(JContainer.withElements(this.elements, elements));
        }

        @Getter
        @With
        @Nullable
        JavaType type;

        @Override
        public <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
            return v.visitMapLiteral(this, p);
        }

        @Override
        public @Nullable <P> J acceptJava(JavaVisitor<P> v, P p) {
            G.MapLiteral m = this;
            m = m.withType(v.visitType(type, p));
            return m;
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
            private final MapLiteral t;

            public JContainer<G.MapEntry> getElements() {
                return t.elements;
            }

            public MapLiteral withElements(JContainer<G.MapEntry> elements) {
                return t.elements == elements ? t : new MapLiteral(t.id, t.prefix, t.markers, elements, t.type);
            }
        }
    }


    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    final class ListLiteral implements G, Expression, TypedTree {
        @Nullable
        @NonFinal
        transient WeakReference<Padding> padding;

        @Getter
        @With
        @EqualsAndHashCode.Include
        UUID id;

        @Getter
        @With
        Space prefix;

        @Getter
        @With
        Markers markers;

        JContainer<Expression> elements;

        public /*~~>*/List<Expression> getElements() {
            return elements.getElements();
        }

        public ListLiteral withElements(/*~~>*/List<Expression> elements) {
            return getPadding().withElements(JContainer.withElements(this.elements, elements));
        }

        @Getter
        @With
        @Nullable
        JavaType type;

        @Override
        public <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
            return v.visitListLiteral(this, p);
        }

        @Override
        public @Nullable <P> J acceptJava(JavaVisitor<P> v, P p) {
            ListLiteral l = this;
            l = l.withType(v.visitType(type, p));
            return l;
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
            private final ListLiteral t;

            public JContainer<Expression> getElements() {
                return t.elements;
            }

            public ListLiteral withElements(JContainer<Expression> elements) {
                return t.elements == elements ? t : new ListLiteral(t.id, t.prefix, t.markers, elements, t.type);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @Data
    @With
    final class GString implements G, Statement, Expression {
        UUID id;
        Space prefix;
        Markers markers;
        /*~~>*/List<J> strings;

        @Nullable
        JavaType type;

        @Override
        public <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
            return v.visitGString(this, p);
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            GString g = this;
            g = g.withStrings(ListUtils.map(strings, s -> v.visit(s, p)));
            g = g.withType(v.visitType(type, p));
            return g;
        }

        @Override
        public CoordinateBuilder.Statement getCoordinates() {
            return new CoordinateBuilder.Statement(this);
        }

        @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @Data
        @With
        public static final class Value implements G {
            UUID id;
            Markers markers;
            J tree;
            boolean enclosedInBraces;

            @Override
            public <J2 extends J> J2 withPrefix(Space space) {
                //noinspection unchecked
                return (J2) this;
            }

            @Override
            public Space getPrefix() {
                return Space.EMPTY;
            }

            @Override
            public <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
                return v.visitGStringValue(this, p);
            }
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Data
    final class Binary implements G, Expression, TypedTree {

        @Nullable
        @NonFinal
        transient WeakReference<G.Binary.Padding> padding;

        @With
        @EqualsAndHashCode.Include
        UUID id;

        @With
        Space prefix;

        @With
        Markers markers;

        @With
        Expression left;

        JLeftPadded<G.Binary.Type> operator;

        public G.Binary.Type getOperator() {
            return operator.getElement();
        }

        public G.Binary withOperator(G.Binary.Type operator) {
            return getPadding().withOperator(this.operator.withElement(operator));
        }

        @With
        Expression right;

        @With
        Space after;

        @With
        @Nullable
        JavaType type;

        @Override
        public <P> J acceptGroovy(GroovyVisitor<P> v, P p) {
            return v.visitBinary(this, p);
        }

        @Override
        public <P> J acceptJava(JavaVisitor<P> v, P p) {
            G.Binary b = this;
            b = b.withType(v.visitType(type, p));
            return b;
        }

        @Override
        public CoordinateBuilder.Expression getCoordinates() {
            return new CoordinateBuilder.Expression(this);
        }

        public enum Type {
            Find,
            Match,

            Access
        }

        public G.Binary.Padding getPadding() {
            G.Binary.Padding p;
            if (this.padding == null) {
                p = new G.Binary.Padding(this);
                this.padding = new WeakReference<>(p);
            } else {
                p = this.padding.get();
                if (p == null || p.t != this) {
                    p = new G.Binary.Padding(this);
                    this.padding = new WeakReference<>(p);
                }
            }
            return p;
        }

        @RequiredArgsConstructor
        public static class Padding {
            private final G.Binary t;

            public JLeftPadded<G.Binary.Type> getOperator() {
                return t.operator;
            }

            public G.Binary withOperator(JLeftPadded<G.Binary.Type> operator) {
                return t.operator == operator ? t : new G.Binary(t.id, t.prefix, t.markers, t.left, operator, t.right, t.after, t.type);
            }
        }
    }
}
