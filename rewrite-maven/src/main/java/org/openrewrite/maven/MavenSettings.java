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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.PropertyPlaceholderHelper;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.internal.MavenXmlMapper;
import org.openrewrite.maven.internal.RawRepositories;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.maven.tree.ProfileActivation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static org.openrewrite.maven.tree.MavenRepository.MAVEN_LOCAL_DEFAULT;

@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Data
@AllArgsConstructor
public class MavenSettings {
    @Nullable
    String localRepository;

    @Nullable
    @NonFinal
    MavenRepository mavenLocal;

    @Nullable
    Profiles profiles;

    @Nullable
    ActiveProfiles activeProfiles;

    @Nullable
    Mirrors mirrors;

    @Nullable
    @With
    Servers servers;

    @JsonCreator
    public MavenSettings(@Nullable String localRepository, @Nullable Profiles profiles,
                         @Nullable ActiveProfiles activeProfiles, @Nullable Mirrors mirrors,
                         @Nullable Servers servers) {
        this.localRepository = localRepository;
        this.profiles = profiles;
        this.activeProfiles = activeProfiles;
        this.mirrors = mirrors;
        this.servers = servers;
    }

    @Nullable
    public static MavenSettings parse(Parser.Input source, ExecutionContext ctx) {
        try {
            return new Interpolator().interpolate(
                    MavenXmlMapper.readMapper().readValue(source.getSource(), MavenSettings.class));
        } catch (IOException e) {
            ctx.getOnError().accept(new IOException("Failed to parse " + source.getPath(), e));
            return null;
        }
    }

    @Nullable
    public static MavenSettings parse(Path settingsPath, ExecutionContext ctx) {
        return parse(new Parser.Input(settingsPath, () -> {
            try {
                return Files.newInputStream(settingsPath);
            } catch (IOException e) {
                ctx.getOnError().accept(new IOException("Failed to read settings.xml at " + settingsPath, e));
                return null;
            }
        }), ctx);
    }

    @Nullable
    public static MavenSettings readMavenSettingsFromDisk(ExecutionContext ctx) {
        final Optional<MavenSettings> userSettings = Optional.of(userSettingsPath())
                .filter(MavenSettings::exists)
                .map(path -> parse(path, ctx));
        final MavenSettings installSettings = findMavenHomeSettings().map(path -> parse(path, ctx)).orElse(null);
        return userSettings.map(mavenSettings -> mavenSettings.merge(installSettings))
                .orElse(installSettings);
    }

    public static boolean readFromDiskEnabled() {
        final String propertyValue = System.getProperty("org.openrewrite.test.readMavenSettingsFromDisk");
        return propertyValue != null && !propertyValue.equalsIgnoreCase("false");
    }

    private static Path userSettingsPath() {
        return Paths.get(System.getProperty("user.home")).resolve(".m2/settings.xml");
    }

    private static Optional<Path> findMavenHomeSettings() {
        for (String envVariable : Arrays.asList("MVN_HOME", "M2_HOME", "MAVEN_HOME")) {
            for (String s : Optional.ofNullable(System.getenv(envVariable)).map(Arrays::asList).orElse(emptyList())) {
                Path resolve = Paths.get(s).resolve("conf/settings.xml");
                if (exists(resolve)) {
                    return Optional.of(resolve);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean exists(Path path) {
        try {
            return path.toFile().exists();
        } catch (SecurityException e) {
            return false;
        }
    }

    public MavenSettings merge(@Nullable MavenSettings installSettings) {
        return installSettings == null ? this : new MavenSettings(
                localRepository == null ? installSettings.localRepository : localRepository,
                profiles == null ? installSettings.profiles : profiles.merge(installSettings.profiles),
                activeProfiles == null ? installSettings.activeProfiles : activeProfiles.merge(installSettings.activeProfiles),
                mirrors == null ? installSettings.mirrors : mirrors.merge(installSettings.mirrors),
                servers == null ? installSettings.servers : servers.merge(installSettings.servers)
        );
    }

    public /*~~>*/List<RawRepositories.Repository> getActiveRepositories(Iterable<String> activeProfiles) {
        /*~~>*/List<RawRepositories.Repository> activeRepositories = new ArrayList<>();

        if (profiles != null) {
            for (Profile profile : profiles.getProfiles()) {
                if (profile.isActive(activeProfiles) || (this.activeProfiles != null &&
                        profile.isActive(this.activeProfiles.getActiveProfiles()))) {
                    if (profile.repositories != null) {
                        activeRepositories.addAll(profile.repositories.getRepositories());
                    }
                }
            }
        }

        return activeRepositories;
    }

    public MavenRepository getMavenLocal() {
        if (localRepository == null) {
            return MAVEN_LOCAL_DEFAULT;
        }
        if (mavenLocal == null) {
            mavenLocal = new MavenRepository("local", asUriString(localRepository), true, true, true, null, null);
        }
        return mavenLocal;
    }

    private static String asUriString(final String pathname) {
        return pathname.startsWith("file://") ? pathname : Paths.get(pathname).toUri().toString();
    }

    /**
     * Resolve all properties EXCEPT in the profiles section, which can be affected by
     * the POM using the settings.
     */
    private static class Interpolator {
        private static final PropertyPlaceholderHelper propertyPlaceholders = new PropertyPlaceholderHelper(
                "${", "}", null);

        private static final UnaryOperator<String> propertyResolver = key -> {
            String property = System.getProperty(key);
            if (property != null) {
                return property;
            }
            if (key.startsWith("env.")) {
                return System.getenv().get(key.substring(4));
            }
            return null;
        };

        public MavenSettings interpolate(MavenSettings mavenSettings) {
            return new MavenSettings(
                    interpolate(mavenSettings.localRepository),
                    mavenSettings.profiles,
                    interpolate(mavenSettings.activeProfiles),
                    interpolate(mavenSettings.mirrors),
                    interpolate(mavenSettings.servers));
        }

        @Nullable
        private ActiveProfiles interpolate(@Nullable ActiveProfiles activeProfiles) {
            if (activeProfiles == null) return null;
            return new ActiveProfiles(ListUtils.map(activeProfiles.getActiveProfiles(), this::interpolate));
        }

        @Nullable
        private Mirrors interpolate(@Nullable Mirrors mirrors) {
            if (mirrors == null) return null;
            return new Mirrors(ListUtils.map(mirrors.getMirrors(), this::interpolate));
        }

        private Mirror interpolate(Mirror mirror) {
            return new Mirror(interpolate(mirror.id), interpolate(mirror.url), interpolate(mirror.getMirrorOf()), mirror.releases, mirror.snapshots);
        }

        @Nullable
        private Servers interpolate(@Nullable Servers servers) {
            if (servers == null) return null;
            return new Servers(ListUtils.map(servers.getServers(), this::interpolate));
        }

        private Server interpolate(Server server) {
            return new Server(interpolate(server.id), interpolate(server.username), interpolate(server.password));
        }

        @Nullable
        private String interpolate(@Nullable String s) {
            return s == null ? null : propertyPlaceholders.replacePlaceholders(s, propertyResolver);
        }
    }

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Profiles {
        @JacksonXmlProperty(localName = "profile")
        @JacksonXmlElementWrapper(useWrapping = false)
        /*~~>*/List<Profile> profiles = emptyList();

        public Profiles merge(@Nullable Profiles profiles) {
            final Map<String, Profile> merged = new LinkedHashMap<>();
            for (Profile profile : /*~~>*/this.profiles) {
                merged.put(profile.id, profile);
            }
            if (profiles != null) {
                profiles.getProfiles().forEach(profile -> merged.putIfAbsent(profile.getId(), profile));
            }
            return new Profiles(new ArrayList<>(merged.values()));
        }
    }

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ActiveProfiles {
        @JacksonXmlProperty(localName = "activeProfile")
        @JacksonXmlElementWrapper(useWrapping = false)
        /*~~>*/List<String> activeProfiles = emptyList();

        public ActiveProfiles merge(@Nullable ActiveProfiles activeProfiles) {
            if (activeProfiles == null) {
                return new ActiveProfiles(new ArrayList<>(/*~~>*/this.activeProfiles));
            }
            /*~~>*/List<String> result = new ArrayList<>();
            Set<String> uniqueValues = new HashSet<>();
            for (String s : ListUtils.concatAll(/*~~>*/this.activeProfiles, /*~~>*/activeProfiles.activeProfiles)) {
                if (uniqueValues.add(s)) {
                    result.add(s);
                }
            }
            return new ActiveProfiles(result);
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @Data
    public static class Profile {
        @Nullable
        String id;

        @Nullable
        ProfileActivation activation;

        @Nullable
        RawRepositories repositories;

        public boolean isActive(Iterable<String> activeProfiles) {
            return ProfileActivation.isActive(id, activeProfiles, activation);
        }

        @SuppressWarnings("unused")
        public boolean isActive(String... activeProfiles) {
            return isActive(Arrays.asList(activeProfiles));
        }
    }

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Mirrors {
        @JacksonXmlProperty(localName = "mirror")
        @JacksonXmlElementWrapper(useWrapping = false)
        /*~~>*/List<Mirror> mirrors = emptyList();

        public Mirrors merge(@Nullable Mirrors mirrors) {
            final Map<String, Mirror> merged = new LinkedHashMap<>();
            for (Mirror mirror : /*~~>*/this.mirrors) {
                merged.put(mirror.id, mirror);
            }
            if (mirrors != null) {
                mirrors.getMirrors().forEach(mirror -> merged.putIfAbsent(mirror.getId(), mirror));
            }
            return new Mirrors(new ArrayList<>(merged.values()));
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @Data
    public static class Mirror {
        @Nullable
        String id;

        @Nullable
        String url;

        @Nullable
        String mirrorOf;

        @Nullable
        Boolean releases;

        @Nullable
        Boolean snapshots;
    }

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Servers {
        @JacksonXmlProperty(localName = "server")
        @JacksonXmlElementWrapper(useWrapping = false)
        /*~~>*/List<Server> servers = emptyList();

        public Servers merge(@Nullable Servers servers) {
            final Map<String, Server> merged = new LinkedHashMap<>();
            for (Server server : /*~~>*/this.servers) {
                merged.put(server.id, server);
            }
            if (servers != null) {
                servers.getServers().forEach(server -> merged.putIfAbsent(server.getId(), server));
            }
            return new Servers(new ArrayList<>(merged.values()));
        }
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @Data
    public static class Server {
        String id;

        String username;
        String password;
    }
}
