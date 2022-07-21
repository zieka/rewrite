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
package org.openrewrite.groovy;

import groovy.lang.GroovyClassLoader;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.control.io.InputStreamReaderSource;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;
import org.intellij.lang.annotations.Language;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.tree.ParsingEventListener;
import org.openrewrite.tree.ParsingExecutionContextView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class GroovyParser implements Parser<G.CompilationUnit> {
    @Nullable
    private final Collection<Path> classpath;

    private final /*~~>*/List<NamedStyles> styles;
    private final boolean logCompilationWarningsAndErrors;
    private final JavaTypeCache typeCache;

    @Override
    public /*~~>*/List<G.CompilationUnit> parse(@Language("groovy") String... sources) {
        Pattern packagePattern = Pattern.compile("^package\\s+([^;]+);");
        Pattern classPattern = Pattern.compile("(class|interface|enum)\\s*(<[^>]*>)?\\s+(\\w+)");

        Function<String, String> simpleName = sourceStr -> {
            Matcher classMatcher = classPattern.matcher(sourceStr);
            return classMatcher.find() ? classMatcher.group(3) : null;
        };

        return parseInputs(
                Arrays.stream(sources)
                        .map(sourceFile -> {
                            Matcher packageMatcher = packagePattern.matcher(sourceFile);
                            String pkg = packageMatcher.find() ? packageMatcher.group(1).replace('.', '/') + "/" : "";

                            String className = Optional.ofNullable(simpleName.apply(sourceFile))
                                    .orElse(Long.toString(System.nanoTime())) + ".java";

                            Path path = Paths.get(pkg + className);
                            return new Input(
                                    path, null,
                                    () -> new ByteArrayInputStream(sourceFile.getBytes(StandardCharsets.UTF_8)),
                                    true
                            );
                        })
                        .collect(toList()),
                null,
                new InMemoryExecutionContext()
        );
    }

    @Override
    public /*~~>*/List<G.CompilationUnit> parseInputs(Iterable<Input> sources, @Nullable Path relativeTo, ExecutionContext ctx) {
        ParsingEventListener parsingListener = ParsingExecutionContextView.view(ctx).getParsingListener();
        /*~~>*/List<CompiledGroovySource> compilerCus = parseInputsToCompilerAst(sources, relativeTo, ctx);
        /*~~>*/List<G.CompilationUnit> cus = new ArrayList<>(compilerCus.size());

        for (CompiledGroovySource compiled : compilerCus) {
            try {
                GroovyParserVisitor mappingVisitor = new GroovyParserVisitor(
                        compiled.getInput().getRelativePath(relativeTo),
                        compiled.getInput().getFileAttributes(),
                        compiled.getInput().getSource(),
                        typeCache,
                        ctx
                );
                G.CompilationUnit gcu = mappingVisitor.visit(compiled.getSourceUnit(), compiled.getModule());
                cus.add(gcu);
                parsingListener.parsed(compiled.getInput(), gcu);
            } catch (Throwable t) {
                ctx.getOnError().accept(t);
            }
        }

        return cus;
    }

    /*~~>*/List<CompiledGroovySource> parseInputsToCompilerAst(Iterable<Input> sources, @Nullable Path relativeTo, ExecutionContext ctx) {
        /*~~>*/List<CompiledGroovySource> cus = new ArrayList<>();

        for (Input input : sources) {
            CompilerConfiguration configuration = new CompilerConfiguration();
            configuration.setTolerance(Integer.MAX_VALUE);
            configuration.setClasspathList(classpath == null ? emptyList() : classpath.stream()
                    .map(cp -> cp.toFile().toString())
                    .collect(toList()));

            ErrorCollector errorCollector = new ErrorCollector(configuration);
            SourceUnit unit = new SourceUnit(
                    "doesntmatter",
                    new InputStreamReaderSource(input.getSource(), configuration),
                    configuration,
                    null,
                    errorCollector
            );

            GroovyClassLoader transformLoader = new GroovyClassLoader(getClass().getClassLoader());

            CompilationUnit compUnit = new CompilationUnit(configuration, null, null, transformLoader);
            compUnit.addSource(unit);

            try {
                compUnit.compile(Phases.CANONICALIZATION);
                ModuleNode ast = unit.getAST();

                for (ClassNode aClass : ast.getClasses()) {
                    try {
                        new StaticTypeCheckingVisitor(unit, aClass).visitClass(aClass);
                    } catch (NoClassDefFoundError ignored) {
                    }
                }

                cus.add(new CompiledGroovySource(input, unit, ast));
            } catch (Throwable t) {
                ctx.getOnError().accept(t);
            } finally {
                if (logCompilationWarningsAndErrors && (errorCollector.hasErrors() || errorCollector.hasWarnings())) {
                    try (StringWriter sw = new StringWriter();
                         PrintWriter pw = new PrintWriter(sw)) {
                        errorCollector.write(pw, new Janitor());
                        org.slf4j.LoggerFactory.getLogger(GroovyParser.class).warn(sw.toString());
                    } catch (IOException ignored) {
                        // unreachable
                    }
                }
            }
        }

        return cus;
    }

    @Override
    public boolean accept(Path path) {
        return path.toString().endsWith(".groovy");
    }

    @Override
    public GroovyParser reset() {
        typeCache.clear();
        return this;
    }

    @Override
    public Path sourcePathFromSourceText(Path prefix, String sourceCode) {
        return prefix.resolve("file.groovy");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        @Nullable
        private Collection<Path> classpath = JavaParser.runtimeClasspath();

        private JavaTypeCache typeCache = new JavaTypeCache();
        private boolean logCompilationWarningsAndErrors = false;
        private final /*~~>*/List<NamedStyles> styles = new ArrayList<>();

        public Builder logCompilationWarningsAndErrors(boolean logCompilationWarningsAndErrors) {
            this.logCompilationWarningsAndErrors = logCompilationWarningsAndErrors;
            return this;
        }

        public Builder classpath(Collection<Path> classpath) {
            this.classpath = classpath;
            return this;
        }

        public Builder classpath(String... classpath) {
            this.classpath = JavaParser.dependenciesFromClasspath(classpath);
            return this;
        }

        public Builder typeCache(JavaTypeCache typeCache) {
            this.typeCache = typeCache;
            return this;
        }

        public Builder styles(Iterable<? extends NamedStyles> styles) {
            for (NamedStyles style : styles) {
                /*~~>*/this.styles.add(style);
            }
            return this;
        }

        public GroovyParser build() {
            return new GroovyParser(classpath, styles, logCompilationWarningsAndErrors, typeCache);
        }
    }
}
