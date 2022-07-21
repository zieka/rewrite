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
package org.openrewrite.java.search;

import lombok.RequiredArgsConstructor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Marks a {@link JavaSourceFile} as matching if all the passed methods are found.
 */
@RequiredArgsConstructor
public class UsesAllMethods<P> extends JavaIsoVisitor<P> {
    private final /*~~>*/List<MethodMatcher> methodMatchers;

    public UsesAllMethods(MethodMatcher... methodMatchers) {
        this(Arrays.asList(methodMatchers));
    }

    @Override
    public JavaSourceFile visitJavaSourceFile(JavaSourceFile cu, P p) {
        List<MethodMatcher> unmatched = new ArrayList<>(methodMatchers);
        for (JavaType.Method type : cu.getTypesInUse().getUsedMethods()) {
            if (unmatched.removeIf(matcher -> matcher.matches(type)) && unmatched.isEmpty()) {
                return cu.withMarkers(cu.getMarkers().searchResult());
            }
        }
        return cu;
    }
}
