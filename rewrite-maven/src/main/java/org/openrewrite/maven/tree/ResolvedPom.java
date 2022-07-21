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
package org.openrewrite.maven.tree;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import lombok.Getter;
import lombok.Value;
import lombok.With;
import lombok.experimental.NonFinal;
import org.openrewrite.ExecutionContext;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.PropertyPlaceholderHelper;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.cache.MavenPomCache;
import org.openrewrite.maven.internal.MavenDownloadingException;
import org.openrewrite.maven.internal.MavenParsingException;
import org.openrewrite.maven.internal.MavenPomDownloader;
import org.openrewrite.maven.internal.VersionRequirement;
import org.openrewrite.maven.tree.ManagedDependency.Defined;
import org.openrewrite.maven.tree.ManagedDependency.Imported;

import java.util.*;
import java.util.function.UnaryOperator;

import static java.util.Collections.*;
import static org.openrewrite.internal.StringUtils.matchesGlob;

@JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class, property = "@ref")
@Getter
public class ResolvedPom {

    private static final PropertyPlaceholderHelper placeholderHelper = new PropertyPlaceholderHelper("${", "}", null);

    // https://maven.apache.org/ref/3.6.3/maven-model-builder/super-pom.html
    private static final ResolvedPom SUPER_POM = new ResolvedPom(
            new Pom(null, null, null, null, null, null, emptyMap(), emptyList(), emptyList(), singletonList(MavenRepository.MAVEN_CENTRAL), emptyList(), emptyList(), null, null),
            emptyList()
    );

    @With
    Pom requested;

    @With
    Iterable<String> activeProfiles;

    public ResolvedPom(Pom requested, Iterable<String> activeProfiles) {
        this(requested, activeProfiles, emptyMap(), emptyList(), null, emptyList(), emptyList());
    }

    @JsonCreator
    ResolvedPom(Pom requested, Iterable<String> activeProfiles, Map<String, String> properties, /*~~>*/List<ResolvedManagedDependency> dependencyManagement, @Nullable /*~~>*/List<MavenRepository> initialRepositories, /*~~>*/List<MavenRepository> repositories, /*~~>*/List<Dependency> requestedDependencies) {
        this.requested = requested;
        this.activeProfiles = activeProfiles;
        this.properties = properties;
        /*~~>*/this.dependencyManagement = dependencyManagement;
        /*~~>*/this.initialRepositories = initialRepositories;
        /*~~>*/this.repositories = repositories;
        /*~~>*/this.requestedDependencies = requestedDependencies;
    }

    @NonFinal
    Map<String, String> properties;

    @NonFinal
    /*~~>*/List<ResolvedManagedDependency> dependencyManagement;

    @NonFinal
    /*~~>*/List<MavenRepository> initialRepositories;

    @NonFinal
    /*~~>*/List<MavenRepository> repositories;

    @NonFinal
    /*~~>*/List<Dependency> requestedDependencies;

    /**
     * Deduplicate dependencies and dependency management dependencies
     * @return This POM after deduplication.
     */
    @SuppressWarnings("UnnecessaryLocalVariable")
    public ResolvedPom deduplicate() {
        Set<UniqueDependencyKey> uniqueManagedDependencies = new HashSet<>(dependencyManagement.size());

        /*~~>*/List<ResolvedManagedDependency> dedupMd = ListUtils.map(dependencyManagement, dm -> uniqueManagedDependencies.add(new UniqueDependencyKey(dm.getGav(), dm.getType(), dm.getClassifier(), dm.getScope())) ?
                dm : null);
        dependencyManagement = dedupMd;

        uniqueManagedDependencies.clear();
        /*~~>*/List<Dependency> dedupD = ListUtils.map(requestedDependencies, d -> uniqueManagedDependencies.add(new UniqueDependencyKey(d.getGav(), d.getType(), d.getClassifier(), d.getScope())) ?
                d : null);
        requestedDependencies = dedupD;
        return this;
    }

    @Value
    private static class UniqueDependencyKey {
        GroupArtifactVersion gav;

        @Nullable
        String type;

        @Nullable
        String classifier;

        Object scope;
    }

    /**
     * Whenever a change is made that may affect the effective properties, dependency management,
     * dependencies, etc. of a POM, this can be called to re-resolve the POM.
     *
     * @param ctx        An execution context containing any maven-specific requirements.
     * @param downloader A POM downloader to download dependencies and parents.
     * @return A new instance with dependencies re-resolved or the same instance if no resolved dependencies have changed.
     * @throws MavenDownloadingException When problems are encountered downloading dependencies or parents.
     */
    public ResolvedPom resolve(ExecutionContext ctx, MavenPomDownloader downloader) throws MavenDownloadingException {
        ResolvedPom resolved = new ResolvedPom(
                requested,
                activeProfiles,
                emptyMap(),
                emptyList(),
                initialRepositories,
                emptyList(),
                emptyList()
        ).resolver(ctx, downloader).resolve();

        for (Map.Entry<String, String> property : resolved.getProperties().entrySet()) {
            if (property.getValue() != null && !property.getValue().equals(properties.get(property.getKey()))) {
                return resolved;
            }
        }

        /*~~>*/List<Dependency> resolvedRequestedDependencies = resolved.getRequestedDependencies();
        if (requestedDependencies.size() != resolvedRequestedDependencies.size()) {
            return resolved;
        }
        for (int i = 0; i < resolvedRequestedDependencies.size(); i++) {
            if (!requestedDependencies.get(i).equals(resolvedRequestedDependencies.get(i))) {
                return resolved;
            }
        }

        /*~~>*/List<ResolvedManagedDependency> resolvedDependencyManagement = resolved.getDependencyManagement();
        if (dependencyManagement.size() != resolvedDependencyManagement.size()) {
            return resolved;
        }
        for (int i = 0; i < resolvedDependencyManagement.size(); i++) {
            // TODO does ResolvedPom's equals work well enough to match on BOM imports?
            if (!dependencyManagement.get(i).equals(resolvedDependencyManagement.get(i))) {
                return resolved;
            }
        }

        /*~~>*/List<MavenRepository> resolvedRepositories = resolved.getRepositories();
        if (repositories.size() != resolvedRepositories.size()) {
            return resolved;
        }
        for (int i = 0; i < resolvedRepositories.size(); i++) {
            if (!repositories.get(i).equals(resolvedRepositories.get(i))) {
                return resolved;
            }
        }

        return this;
    }

    Resolver resolver(ExecutionContext ctx, MavenPomDownloader downloader) {
        return new Resolver(ctx, downloader);
    }

    public ResolvedGroupArtifactVersion getGav() {
        return requested.getGav();
    }

    public String getGroupId() {
        return requested.getGroupId();
    }

    public String getArtifactId() {
        return requested.getArtifactId();
    }

    public String getVersion() {
        return requested.getVersion();
    }

    @Nullable
    public String getDatedSnapshotVersion() {
        return requested.getDatedSnapshotVersion();
    }

    public String getPackaging() {
        return requested.getPackaging() == null ? "jar" : requested.getPackaging();
    }

    @Nullable
    public String getValue(@Nullable String value) {
        if (value == null) {
            return null;
        }
        return placeholderHelper.replacePlaceholders(value, this::getProperty);
    }

    @Nullable
    private String getProperty(@Nullable String property) {
        if (property == null) {
            return null;
        }
        switch (property) {
            case "groupId":
            case "project.groupId":
            case "pom.groupId":
                return requested.getGroupId();
            case "project.parent.groupId":
                return requested.getParent() != null ? requested.getParent().getGroupId() : null;
            case "artifactId":
            case "project.artifactId":
            case "pom.artifactId":
                return requested.getArtifactId(); // cannot be inherited from parent
            case "project.parent.artifactId":
                return requested.getParent() == null ? null : requested.getParent().getArtifactId();
            case "version":
            case "project.version":
            case "pom.version":
                return requested.getVersion();
            case "project.parent.version":
                return requested.getParent() != null ? requested.getParent().getVersion() : null;
        }

        return System.getProperty(property, properties.get(property));
    }

    @Nullable
    public String getManagedVersion(String groupId, String artifactId, @Nullable String type, @Nullable String classifier) {
        for (ResolvedManagedDependency dm : dependencyManagement) {
            if (dm.matches(groupId, artifactId, type, classifier)) {
                return getValue(dm.getVersion());
            }
        }

        return null;
    }

    public /*~~>*/List<GroupArtifact> getManagedExclusions(String groupId, String artifactId, @Nullable String type, @Nullable String classifier) {
        for (ResolvedManagedDependency dm : dependencyManagement) {
            if (dm.matches(groupId, artifactId, type, classifier)) {
                return dm.getExclusions() == null ? emptyList() : dm.getExclusions();
            }
        }
        return emptyList();
    }

    @Nullable
    public Scope getManagedScope(String groupId, String artifactId, @Nullable String type, @Nullable String classifier) {
        for (ResolvedManagedDependency dm : dependencyManagement) {
            if (dm.matches(groupId, artifactId, type, classifier)) {
                return dm.getScope();
            }
        }
        return null;
    }

    public GroupArtifactVersion getValues(GroupArtifactVersion gav) {
        return gav.withGroupId(getValue(gav.getGroupId()))
                .withArtifactId(getValue(gav.getArtifactId()))
                .withVersion(getValue(gav.getVersion()));
    }

    public GroupArtifact getValues(GroupArtifact ga) {
        return ga.withGroupId(getValue(ga.getGroupId()))
                .withArtifactId(getValue(ga.getArtifactId()));
    }

    @Value
    class Resolver {
        ExecutionContext ctx;
        MavenPomDownloader downloader;

        public ResolvedPom resolve() throws MavenDownloadingException {
            resolveParentsRecursively(requested);
            return ResolvedPom.this;
        }

        void resolveParentsRecursively(Pom requested) {
            /*~~>*/List<Pom> pomAncestry = new ArrayList<>();
            pomAncestry.add(requested);

            if (initialRepositories != null) {
                mergeRepositories(initialRepositories);
            }
            resolveParentPropertiesAndRepositoriesRecursively(pomAncestry);
            if (initialRepositories == null) {
                initialRepositories = repositories;
            }

            //Once properties have been merged, update any property placeholders in the resolved gav
            //coordinates. This is important to do early because any system properties used within the coordinates
            //are transient and will not be available once pom has been serialized/deserialized into a different VM.
            Pom pomReference = ResolvedPom.this.requested;
            pomReference = pomReference.withGav(pomReference.getGav().withRepository(getValue(pomReference.getGav().getRepository())));
            pomReference = pomReference.withGav(pomReference.getGav().withGroupId(getValue(pomReference.getGav().getGroupId())));
            pomReference = pomReference.withGav(pomReference.getGav().withArtifactId(getValue(pomReference.getGav().getArtifactId())));
            pomReference = pomReference.withGav(pomReference.getGav().withVersion(getValue(pomReference.getGav().getVersion())));
            pomReference = pomReference.withGav(pomReference.getGav().withDatedSnapshotVersion(getValue(pomReference.getGav().getDatedSnapshotVersion())));
            if (ResolvedPom.this.requested != pomReference) {
                ResolvedPom.this.requested = pomReference;
            }

            pomAncestry.clear();
            pomAncestry.add(requested);
            resolveParentDependenciesRecursively(pomAncestry);
        }

        private void resolveParentPropertiesAndRepositoriesRecursively(/*~~>*/List<Pom> pomAncestry) {
            Pom pom = pomAncestry.get(0);

            //Resolve properties
            for (Profile profile : pom.getProfiles()) {
                if (profile.isActive(activeProfiles)) {
                    mergeProperties(profile.getProperties(), pom);
                }
            }
            mergeProperties(pom.getProperties(), pom);

            //Resolve repositories (which may rely on properties ^^^)
            for (Profile profile : pom.getProfiles()) {
                if (profile.isActive(activeProfiles)) {
                    mergeRepositories(profile.getRepositories());
                }
            }
            mergeRepositories(pom.getRepositories());

            if (pom.getParent() != null) {
                Pom parentPom = downloader.download(getValues(pom.getParent().getGav()),
                        pom.getParent().getRelativePath(), ResolvedPom.this, repositories);

                for (Pom ancestor : pomAncestry) {
                    if (ancestor.getGav().equals(parentPom.getGav())) {
                        // parent cycle
                        return;
                    }
                }

                pomAncestry.add(0, parentPom);
                resolveParentPropertiesAndRepositoriesRecursively(pomAncestry);
            }
        }

        private void resolveParentDependenciesRecursively(/*~~>*/List<Pom> pomAncestry) {
            Pom pom = pomAncestry.get(0);

            for (Profile profile : pom.getProfiles()) {
                if (profile.isActive(activeProfiles)) {
                    mergeDependencyManagement(profile.getDependencyManagement(), pom);
                    mergeRequestedDependencies(profile.getDependencies());
                }
            }

            mergeDependencyManagement(pom.getDependencyManagement(), pom);
            mergeRequestedDependencies(pom.getDependencies());

            if (pom.getParent() != null) {
                Pom parentPom = downloader.download(getValues(pom.getParent().getGav()),
                        pom.getParent().getRelativePath(), ResolvedPom.this, repositories);

                for (Pom ancestor : pomAncestry) {
                    if (ancestor.getGav().equals(parentPom.getGav())) {
                        // parent cycle
                        return;
                    }
                }

                MavenExecutionContextView.view(ctx)
                        .getResolutionListener()
                        .parent(parentPom, pom);

                pomAncestry.add(0, parentPom);
                resolveParentDependenciesRecursively(pomAncestry);
            }
        }

        private void mergeRequestedDependencies(/*~~>*/List<Dependency> incomingRequestedDependencies) {
            if (!incomingRequestedDependencies.isEmpty()) {
                if (requestedDependencies == null || requestedDependencies.isEmpty()) {
                    //It is possible for the dependencies to be an empty, immutable list.
                    //If it's empty, we ensure to create a mutable list.
                    requestedDependencies = new ArrayList<>(incomingRequestedDependencies);
                } else {
                    requestedDependencies.addAll(incomingRequestedDependencies);
                }
            }
        }

        private void mergeRepositories(/*~~>*/List<MavenRepository> incomingRepositories) {
            if (!incomingRepositories.isEmpty()) {
                if (repositories == null || repositories.isEmpty()) {
                    //It is possible for the repositories to be an empty, immutable list.
                    //If it's empty, we ensure to create a mutable list.
                    repositories = new ArrayList<>(incomingRepositories.size());
                }

                nextRepository:
                for (MavenRepository incomingRepository : incomingRepositories) {
                    @SuppressWarnings("ConstantConditions")
                    MavenRepository incoming = new MavenRepository(
                            getValue(incomingRepository.getId()),
                            getValue(incomingRepository.getUri()),
                            incomingRepository.isReleases(),
                            incomingRepository.isSnapshots(),
                            incomingRepository.isKnownToExist(),
                            incomingRepository.getUsername(),
                            incomingRepository.getPassword()
                    );

                    if (incoming.getId() != null) {
                        for (MavenRepository repository : repositories) {
                            if (incoming.getId().equals(repository.getId())) {
                                continue nextRepository;
                            }
                        }
                    }
                    repositories.add(incoming);
                }
            }
        }

        private void mergeProperties(Map<String, String> incomingProperties, Pom pom) {
            if (!incomingProperties.isEmpty()) {
                if (properties == null || properties.isEmpty()) {
                    //It is possible for the properties to be an empty, immutable map.
                    //If it's empty, we ensure to create a mutable map.
                    properties = new HashMap<>(incomingProperties.size());
                }
                for (Map.Entry<String, String> property : incomingProperties.entrySet()) {
                    MavenExecutionContextView.view(ctx)
                            .getResolutionListener()
                            .property(property.getKey(), property.getValue(), pom);
                    if (!properties.containsKey(property.getKey())) {
                        properties.put(property.getKey(), property.getValue());
                    }
                }
            }
        }

        private void mergeDependencyManagement(/*~~>*/List<ManagedDependency> incomingDependencyManagement, Pom pom) {
            if (!incomingDependencyManagement.isEmpty()) {
                if (dependencyManagement == null || dependencyManagement.isEmpty()) {
                    dependencyManagement = new ArrayList<>();
                }
                for (ManagedDependency d : incomingDependencyManagement) {
                    if (d instanceof Imported) {
                        ResolvedPom bom = downloader.download(getValues(((Imported) d).getGav()), null, ResolvedPom.this, repositories)
                                .resolve(activeProfiles, downloader, initialRepositories, ctx);
                        MavenExecutionContextView.view(ctx)
                                .getResolutionListener()
                                .bomImport(bom.getGav(), pom);
                        dependencyManagement.addAll(ListUtils.map(bom.getDependencyManagement(), dm -> dm
                                .withRequestedBom(d)
                                .withBomGav(bom.getGav())));
                    } else if (d instanceof Defined) {
                        Defined defined = (Defined) d;
                        MavenExecutionContextView.view(ctx)
                                .getResolutionListener()
                                .dependencyManagement(defined.withGav(getValues(defined.getGav())), pom);
                        dependencyManagement.add(new ResolvedManagedDependency(
                                getValues(defined.getGav()),
                                Scope.fromName(getValue(defined.getScope())),
                                getValue(defined.getType()),
                                getValue(defined.getClassifier()),
                                ListUtils.map(defined.getExclusions(), (UnaryOperator<GroupArtifact>) ResolvedPom.this::getValues),
                                defined,
                                null,
                                null
                        ));
                    }
                }
            }
        }
    }

    public /*~~>*/List<ResolvedDependency> resolveDependencies(Scope scope, MavenPomDownloader downloader, ExecutionContext ctx) {
        return resolveDependencies(scope, new HashMap<>(), downloader, ctx);
    }

    public /*~~>*/List<ResolvedDependency> resolveDependencies(Scope scope, Map<GroupArtifact, VersionRequirement> requirements,
                                                        MavenPomDownloader downloader, ExecutionContext ctx) {
        /*~~>*/List<ResolvedDependency> dependencies = new ArrayList<>();

        /*~~>*/List<DependencyAndDependent> dependenciesAtDepth = new ArrayList<>();
        for (Dependency requestedDependency : getRequestedDependencies()) {
            Dependency d = getValues(requestedDependency, 0);
            Scope dScope = Scope.fromName(d.getScope());
            if (dScope == scope || dScope.isInClasspathOf(scope)) {
                dependenciesAtDepth.add(new DependencyAndDependent(requestedDependency, Scope.Compile, null, this));
            }
        }

        int depth = 0;
        while (!dependenciesAtDepth.isEmpty()) {
            /*~~>*/List<DependencyAndDependent> dependenciesAtNextDepth = new ArrayList<>();

            for (DependencyAndDependent dd : dependenciesAtDepth) {
                //First get the dependency (relative to the pom it was defined in)
                Dependency d = dd.getDefinedIn().getValues(dd.getDependency(), depth);
                //The dependency may be modified by the current pom's managed dependencies
                d = getValues(d, depth);
                if (d.getVersion() == null) {
                    throw new MavenParsingException("No version provided for dependency " + d.getGroupId() + ":" + d.getArtifactId());
                }

                if (d.getType() != null && (!"jar".equals(d.getType()) && !"pom".equals(d.getType()))) {
                    continue;
                }

                try {
                    GroupArtifact ga = new GroupArtifact(d.getGroupId(), d.getArtifactId());
                    VersionRequirement existingRequirement = requirements.get(ga);
                    if (existingRequirement == null) {
                        VersionRequirement newRequirement = VersionRequirement.fromVersion(d.getVersion(), depth);
                        requirements.put(ga, newRequirement);
                        String newRequiredVersion = newRequirement.resolve(ga, downloader, getRepositories());
                        d = d.withGav(d.getGav().withVersion(newRequiredVersion));
                    } else {
                        VersionRequirement newRequirement = existingRequirement.addRequirement(d.getVersion());
                        requirements.put(ga, newRequirement);

                        String existingRequiredVersion = existingRequirement.resolve(ga, downloader, getRepositories());
                        String newRequiredVersion = newRequirement.resolve(ga, downloader, getRepositories());

                        if (!Objects.equals(existingRequiredVersion, newRequiredVersion)) {
                            // start over from the top with the knowledge of this new requirement and throwing
                            // away any in progress resolution because this requirement could cause a change
                            // to just about anything we've seen to this point
                            MavenExecutionContextView.view(ctx)
                                    .getResolutionListener()
                                    .clear();
                            return resolveDependencies(scope, requirements, downloader, ctx);
                        } else {
                            // we've already resolved this previously and the requirement didn't change,
                            // so just skip and continue on
                            continue;
                        }
                    }

                    Pom dPom = downloader.download(d.getGav(), null, dd.definedIn, getRepositories());

                    MavenPomCache cache = MavenExecutionContextView.view(ctx).getPomCache();
                    ResolvedPom resolvedPom = cache.getResolvedDependencyPom(dPom.getGav());
                    if (resolvedPom == null) {
                        resolvedPom = new ResolvedPom(dPom, getActiveProfiles(), emptyMap(),
                                emptyList(), initialRepositories, emptyList(), emptyList());
                        resolvedPom.resolver(ctx, downloader).resolveParentsRecursively(dPom);
                        cache.putResolvedDependencyPom(dPom.getGav(), resolvedPom);
                    }

                    ResolvedDependency resolved = new ResolvedDependency(dPom.getRepository(),
                            resolvedPom.getGav(), dd.getDependency(), emptyList(),
                            resolvedPom.getRequested().getLicenses(),
                            depth);

                    MavenExecutionContextView.view(ctx)
                            .getResolutionListener()
                            .dependency(scope, resolved, dd.getDefinedIn());

                    // build link between the including dependency and this one
                    ResolvedDependency includedBy = dd.getDependent();
                    if (includedBy != null) {
                        if (includedBy.getDependencies().isEmpty()) {
                            includedBy.unsafeSetDependencies(new ArrayList<>());
                        }
                        includedBy.getDependencies().add(resolved);
                    }

                    dependencies.add(resolved);

                    nextDependency:
                    for (Dependency d2 : resolvedPom.getRequestedDependencies()) {
                        if (d2.getGroupId() == null) {
                            d2 = d2.withGav(d2.getGav().withGroupId(resolvedPom.getGroupId()));
                        }
                        if (d2.isOptional()) {
                            continue;
                        }
                        if (d.getExclusions() != null) {
                            for (GroupArtifact exclusion : d.getExclusions()) {
                                if (matchesGlob(getValue(d2.getGroupId()), getValue(exclusion.getGroupId())) &&
                                        matchesGlob(getValue(d2.getArtifactId()), getValue(exclusion.getArtifactId()))) {
                                    continue nextDependency;
                                }
                            }
                        }

                        Scope d2Scope = getDependencyScope(d2, resolvedPom);
                        if (d2Scope.isInClasspathOf(dd.getScope())) {
                            dependenciesAtNextDepth.add(new DependencyAndDependent(d2, d2Scope, resolved, resolvedPom));
                        }
                    }
                } catch (MavenDownloadingException e) {
                    ctx.getOnError().accept(e);
                }
            }

            dependenciesAtDepth = dependenciesAtNextDepth;
            depth++;
        }

        return dependencies;
    }

    private Scope getDependencyScope(Dependency d2, ResolvedPom containingPom) {
        if (d2.getScope() == null) {
            // project POM's dependency management overrules the dependency's dependencyManagement
            //noinspection ConstantConditions
            Scope s = getManagedScope(getValue(d2.getGroupId()), getValue(d2.getArtifactId()), getValue(d2.getType()),
                    getValue(d2.getClassifier()));
            if(s == null) {
                //noinspection ConstantConditions
                s = containingPom.getManagedScope(getValue(d2.getGroupId()), getValue(d2.getArtifactId()), getValue(d2.getType()),
                        getValue(d2.getClassifier()));
            }
            return s == null ? Scope.Compile : s;
        }
        return Scope.fromName(getValue(d2.getScope()));
    }

    private GroupArtifact groupArtifact(Dependency dependency) {
        return new GroupArtifact(dependency.getGroupId(), dependency.getArtifactId());
    }

    private Dependency getValues(Dependency dep, int depth) {
        Dependency d = dep.withGav(getValues(dep.getGav()))
                .withScope(getValue(dep.getScope()));

        if (d.getGroupId() == null) {
            return d;
        }

        String version = d.getVersion();
        if (d.getVersion() == null || depth > 0) {
            // dependency management overrides transitive dependency versions
            version = getManagedVersion(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier());
            if (version == null) {
                version = d.getVersion();
            }
        }

        String scope;
        if (d.getScope() == null) {
            Scope parsedScope = getManagedScope(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier());
            scope = parsedScope == null ? null : parsedScope.toString().toLowerCase();
        } else {
            scope = getValue(d.getScope());
        }

        /*~~>*/List<GroupArtifact> managedExclusions = getManagedExclusions(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier());
        if (!managedExclusions.isEmpty()) {
            d = d.withExclusions(ListUtils.concatAll(d.getExclusions(), managedExclusions));
        }

        if (d.getClassifier() != null) {
            d = d.withClassifier(getValue(d.getClassifier()));
        }
        if (d.getType() != null) {
            d = d.withType(getValue(d.getType()));
        }
        return d
                .withGav(d.getGav().withVersion(version))
                .withScope(scope);
    }

    @Value
    private static class DependencyAndDependent {
        Dependency dependency;
        Scope scope;
        ResolvedDependency dependent;
        ResolvedPom definedIn;
    }
}
