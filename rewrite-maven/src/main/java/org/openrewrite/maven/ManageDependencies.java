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
package org.openrewrite.maven;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.tree.*;
import org.openrewrite.xml.RemoveContentVisitor;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

/**
 * Make existing dependencies "dependency managed", moving the version to the dependencyManagement
 * section of the POM.
 * <p>
 * All dependencies that match {@link #groupPattern} and {@link #artifactPattern} should be
 * align-able to the same version (either the version provided to this visitor or the maximum matching
 * version if none is provided).
 */
@Incubating(since = "7.19.0")
@Value
@EqualsAndHashCode(callSuper = true)
public class ManageDependencies extends Recipe {
    private static final XPathMatcher MANAGED_DEPENDENCIES_MATCHER = new XPathMatcher("/project/dependencyManagement/dependencies");

    @Option(displayName = "Group",
            description = "Group glob expression pattern used to match dependencies that should be managed." +
                    "Group is the the first part of a dependency coordinate 'com.google.guava:guava:VERSION'.",
            example = "com.google.*")
    String groupPattern;

    @Option(displayName = "Artifact",
            description = "Artifact glob expression pattern used to match dependencies that should be managed." +
                    "Artifact is the second part of a dependency coordinate 'com.google.guava:guava:VERSION'.",
            example = "guava*",
            required = false)
    @Nullable
    String artifactPattern;

    @Option(displayName = "Version",
            description = "Version to use for the dependency in dependency management. " +
                    "Defaults to the existing version found on the matching dependency, or the max version if multiple dependencies match the glob expression patterns.",
            example = "1.0.0",
            required = false)
    @Nullable
    String version;

    @Option(displayName = "Add to the root pom",
            description = "Add to the root pom where root is the eldest parent of the pom within the source set.",
            example = "true",
            required = false)
    @Nullable
    Boolean addToRootPom;

    @Override
    public String getDisplayName() {
        return "Manage dependencies";
    }

    @Override
    public String getDescription() {
        return "Make existing dependencies managed by moving their version to be specified in the dependencyManagement section of the POM.";
    }

    @Override
    protected /*~~>*/List<SourceFile> visit(/*~~>*/List<SourceFile> before, ExecutionContext ctx) {
        Map<GroupArtifactVersion, Collection<ResolvedDependency>> rootGavToDependencies = new HashMap<>();
        if (Boolean.TRUE.equals(addToRootPom)) {
            for (SourceFile source : before) {
                new MavenIsoVisitor<ExecutionContext>() {
                    @Override
                    public Xml.Document visitDocument(Xml.Document document, ExecutionContext executionContext) {
                        Xml.Document doc = super.visitDocument(document, executionContext);
                        Collection<ResolvedDependency> manageableDependencies = findDependencies(groupPattern, artifactPattern != null ? artifactPattern : "*");
                        ResolvedGroupArtifactVersion root = findRootPom(getResolutionResult()).getPom().getGav();
                        rootGavToDependencies.computeIfAbsent(new GroupArtifactVersion(root.getGroupId(), root.getArtifactId(), root.getVersion()), v -> new ArrayList<>()).addAll(manageableDependencies);
                        return doc;
                    }
                }.visit(source, ctx);
            }
        }

        return ListUtils.map(before, s -> s.getMarkers().findFirst(MavenResolutionResult.class)
                .map(javaProject -> (Tree) new MavenVisitor<ExecutionContext>() {
                    @Override
                    public Xml visitDocument(Xml.Document document, ExecutionContext executionContext) {
                        Xml maven = super.visitDocument(document, executionContext);

                        Collection<ResolvedDependency> manageableDependencies;
                        if (Boolean.TRUE.equals(addToRootPom)) {
                            ResolvedPom pom = getResolutionResult().getPom();
                            GroupArtifactVersion gav = new GroupArtifactVersion(pom.getGav().getGroupId(), pom.getGav().getArtifactId(), pom.getGav().getVersion());
                            manageableDependencies = rootGavToDependencies.get(gav);
                        } else {
                            manageableDependencies = findDependencies(groupPattern, artifactPattern != null ? artifactPattern : "*");
                        }

                        if (manageableDependencies != null) {
                            Map<GroupArtifact, GroupArtifactVersion> dependenciesToManage = new HashMap<>();
                            String selectedVersion = version;

                            for (ResolvedDependency rmd : manageableDependencies) {
                                if (version != null) {
                                    dependenciesToManage.putIfAbsent(new GroupArtifact(rmd.getGroupId(), rmd.getArtifactId()), new GroupArtifactVersion(rmd.getGroupId(), rmd.getArtifactId(), version));
                                } else {
                                    if (selectedVersion == null) {
                                        selectedVersion = rmd.getVersion();
                                    } else {
                                        if (new Version(rmd.getVersion()).compareTo(new Version(selectedVersion)) > 0) {
                                            selectedVersion = rmd.getVersion();
                                        }
                                    }
                                    dependenciesToManage.put(new GroupArtifact(rmd.getGroupId(), rmd.getArtifactId()), new GroupArtifactVersion(rmd.getGroupId(), rmd.getArtifactId(), selectedVersion));
                                }
                            }

                            for (GroupArtifactVersion gav : dependenciesToManage.values()) {
                                doAfterVisit(new AddManagedDependencyVisitor(gav.getGroupId(), gav.getArtifactId(), gav.getVersion(), null, null, null, null, null));
                            }
                        }

                        doAfterVisit(new RemoveVersionTagVisitor(groupPattern, artifactPattern != null ? artifactPattern : "*"));
                        return maven;
                    }
                }.visit(s, ctx))
                .map(SourceFile.class::cast)
                .orElse(s)
        );
    }

    private MavenResolutionResult findRootPom(MavenResolutionResult pom) {
        if (pom.getParent() == null) {
            return pom;
        }
        return findRootPom(pom.getParent());
    }

    private static class RemoveVersionTagVisitor extends MavenIsoVisitor<ExecutionContext> {
        private final String groupPattern;
        private final String artifactPattern;

        public RemoveVersionTagVisitor(String groupPattern, String artifactPattern) {
            this.groupPattern = groupPattern;
            this.artifactPattern = artifactPattern;
        }

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            if (isDependencyTag() && isDependencyTag(groupPattern, artifactPattern)) {
                tag.getChild("version").ifPresent(versionTag -> doAfterVisit(new RemoveContentVisitor<>(versionTag, false)));
                return tag;
            }

            return super.visitTag(tag, ctx);
        }
    }

}
