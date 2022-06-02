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
package org.openrewrite.kotlin;

import lombok.*;
import org.jetbrains.kotlin.analyzer.AnalysisResult;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.com.intellij.mock.MockProject;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.idea.KotlinLanguage;
import org.jetbrains.kotlin.psi.KtFile;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.tree.ParsingEventListener;
import org.openrewrite.tree.ParsingExecutionContextView;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jetbrains.kotlin.cli.common.CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY;
import static org.jetbrains.kotlin.cli.common.messages.MessageRenderer.PLAIN_FULL_PATHS;

@RequiredArgsConstructor
@AllArgsConstructor
@Getter
@Builder
public class KotlinParser implements Parser<K.CompilationUnit> {

    final boolean logCompilationWarningsAndErrors;

    @Setter
    @Nullable
    @Builder.Default
    private String moduleName = null;

    @Override
    public List<K.CompilationUnit> parseInputs(Iterable<Input> sources, @Nullable Path relativeTo, ExecutionContext ctx) {
        ParsingEventListener parsingListener = ParsingExecutionContextView.view(ctx).getParsingListener();
        LinkedHashMap<Input, KtFile> cus = parseInputsToCompilerAst(sources, ctx);

        return null;
    }

    private LinkedHashMap<Input, KtFile> parseInputsToCompilerAst(Iterable<Input> sourceFiles, ExecutionContext ctx) {
        Disposable disposable = null;
        try {
            LinkedHashMap<Input, KtFile> cus = new LinkedHashMap<>();

            disposable = Disposer.newDisposable();
            KotlinCoreEnvironment kenv = KotlinCoreEnvironment.createForProduction(
                    disposable, compilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);

            Project project = kenv.getProject();

            PsiFileFactory psiFileFactory = PsiFileFactory.getInstance(project);
            for (Input input : sourceFiles) {
                KtFile ktFile = (KtFile) psiFileFactory.createFileFromText(KotlinLanguage.INSTANCE, input.getSource().readFully());
                kenv.getSourceFiles().add(ktFile);
                cus.put(input, ktFile);
            }
//            kenv.registerJavac(Collections.emptyList(), kenv.getSourceFiles(), null, null, null);

            KotlinToJVMBytecodeCompiler kotlinCompiler = KotlinToJVMBytecodeCompiler.INSTANCE;
            AnalysisResult result = kotlinCompiler.analyze(kenv);

            return cus;
        } finally {
            if(disposable != null) {
                disposable.dispose();
            }
        }
    }

    private CompilerConfiguration compilerConfiguration() {
        CompilerConfiguration compilerConfiguration = new CompilerConfiguration();
        if(logCompilationWarningsAndErrors) {
            compilerConfiguration.put(MESSAGE_COLLECTOR_KEY, new PrintingMessageCollector(System.err, PLAIN_FULL_PATHS, true));
        } else {
            compilerConfiguration.put(MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
        }
        if(moduleName == null) {
            compilerConfiguration.put(CommonConfigurationKeys.MODULE_NAME, "main");
        } else {
            compilerConfiguration.put(CommonConfigurationKeys.MODULE_NAME, moduleName);
        }

        return compilerConfiguration;
    }

    @Override
    public boolean accept(Path path) {
        return path.toString().endsWith(".kt") || path.toString().endsWith(".kts");
    }

    @Override
    public Path sourcePathFromSourceText(Path prefix, String sourceCode) {
        Pattern packagePattern = Pattern.compile("^package\\s+([^;]+);?");
        Pattern classPattern = Pattern.compile("(class|interface|enum)\\s*(<[^>]*>)?\\s+(\\w+)");

        Function<String, String> simpleName = sourceStr -> {
            Matcher classMatcher = classPattern.matcher(sourceStr);
            return classMatcher.find() ? classMatcher.group(3) : null;
        };

        Matcher packageMatcher = packagePattern.matcher(sourceCode);
        String pkg = packageMatcher.find() ? packageMatcher.group(1).replace('.', '/') + "/" : "";

        String className = Optional.ofNullable(simpleName.apply(sourceCode))
                .orElse(Long.toString(System.nanoTime())) + ".java";

        return prefix.resolve(Paths.get(pkg + className));
    }
}
