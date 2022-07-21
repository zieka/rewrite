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
package org.openrewrite.maven;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.semver.Semver;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

@Incubating(since = "7.20.0")
@Getter
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AddManagedDependency extends Recipe {

    @Option(displayName = "Group",
            description = "The first part of a dependency coordinate 'org.apache.logging.log4j:ARTIFACT_ID:VERSION'.",
            example = "org.apache.logging.log4j")
    private final String groupId;

    @Option(displayName = "Artifact",
            description = "The second part of a dependency coordinate 'org.apache.logging.log4j:log4j-bom:VERSION'.",
            example = "log4j-bom")
    private final String artifactId;

    @Option(displayName = "Version",
            description = "An exact version number or node-style semver selector used to select the version number.",
            example = "latest.release")
    private final String version;

    @Option(displayName = "Scope",
            description = "An optional scope to use for the dependency management tag.",
            example = "import",
            valid = {"import", "runtime", "provided", "test"},
            required = false)
    @Nullable
    private final String scope;


    @Option(displayName = "Type",
            description = "An optional type to use for the dependency management tag.",
            valid = {"jar", "pom", "war"},
            example = "pom",
            required = false)
    @Nullable
    private String type;

    @Option(displayName = "Classifier",
            description = "An optional classifier to use for the dependency management tag",
            example = "test",
            required = false)
    @Nullable
    private String classifier;

    @Option(displayName = "Version pattern",
            description = "Allows version selection to be extended beyond the original Node Semver semantics. So for example," +
                    "Setting 'version' to \"25-29\" can be paired with a metadata pattern of \"-jre\" to select 29.0-jre",
            example = "-jre",
            required = false)
    @Nullable
    private String versionPattern;

    @Option(displayName = "Releases only",
            description = "Whether to exclude snapshots from consideration when using a semver selector",
            example = "true",
            required = false)
    @Nullable
    private Boolean releasesOnly;

    @Option(displayName = "Only if using glob expression for group:artifact",
            description = "Only add managed dependencies to projects having a dependency matching the expression.",
            example = "org.apache.logging.log4j:log4j*",
            required = false)
    @Nullable
    private String onlyIfUsing;

    @Option(displayName = "Add to the root pom",
            description = "Add to the root pom where root is the eldest parent of the pom within the source set.",
            example = "true",
            required = false)
    @Nullable
    private Boolean addToRootPom;

    @Override
    public Validated validate() {
        Validated validated = super.validate();
        //noinspection ConstantConditions
        if (version != null) {
            validated = validated.or(Semver.validate(version, versionPattern));
        }
        if (!StringUtils.isNullOrEmpty(onlyIfUsing)) {
            validated = validated.and(Validated.test("onlyIfUsing", "invalid group:artifact glob pattern", onlyIfUsing, s -> {
                try {
                    return onlyIfUsing.matches("[\\w\\d.]+\\*?:([\\w\\d]+\\*?|\\*)");
                } catch (Throwable t) {
                    return false;
                }
            }));
        }
        return validated;
    }

    @Override
    public String getDisplayName() {
        return "Add Managed Maven Dependency";
    }

    @Override
    public String getDescription() {
        return "Add a managed maven dependency to a pom.xml file.";
    }

    @Override
    protected MavenIsoVisitor<ExecutionContext> getApplicableTest() {
        if (!StringUtils.isNullOrEmpty(onlyIfUsing) && onlyIfUsing.contains(":")) {
            String[] ga = onlyIfUsing.split(":");
            return new MavenIsoVisitor<ExecutionContext>() {
                @Override
                public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext context) {
                    Xml.Tag t = super.visitTag(tag, context);

                    if (isDependencyTag()) {
                        ResolvedDependency dependency = findDependency(t, null);
                        if (dependency != null) {
                            ResolvedDependency match = dependency.findDependency(ga[0], ga[1]);
                            if (match != null) {
                                t = t.withMarkers(t.getMarkers().searchResult());
                            }
                        }
                    }

                    return t;
                }
            };
        }
        return null;
    }

    @Override
    protected /*~~>*/List<SourceFile> visit(/*~~>*/List<SourceFile> before, ExecutionContext ctx) {
        /*~~>*/List<SourceFile> rootPoms = new ArrayList<>();
        for (SourceFile source : before) {
            source.getMarkers().findFirst(MavenResolutionResult.class).ifPresent(mavenResolutionResult -> {
                if (mavenResolutionResult.getParent() == null) {
                    rootPoms.add(source);
                }
            });
        }

        return ListUtils.map(before, s -> s.getMarkers().findFirst(MavenResolutionResult.class)
                .map(javaProject -> (Tree) new MavenVisitor<ExecutionContext>() {
                    @Override
                    public Xml visitDocument(Xml.Document document, ExecutionContext executionContext) {
                        Xml maven = super.visitDocument(document, executionContext);

                        if (!Boolean.TRUE.equals(addToRootPom) || rootPoms.contains(document)) {
                            doAfterVisit(new AddManagedDependencyVisitor(groupId,artifactId,version,versionPattern,scope,releasesOnly,type,classifier));
                        }

                        return maven;
                    }
                }.visit(s, ctx))
                .map(SourceFile.class::cast)
                .orElse(s)
        );
    }
}
