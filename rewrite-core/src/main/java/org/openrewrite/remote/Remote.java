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
package org.openrewrite.remote;

import org.intellij.lang.annotations.Language;
import org.openrewrite.*;
import org.openrewrite.binary.BinaryVisitor;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;
import org.openrewrite.marker.Markers;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Represents a source hosted at a remote URI.
 * <p>
 * Downloading the remote file is not handled during Recipe execution. Post-processing of Recipe results by a build
 * plugin or other caller of OpenRewrite is responsible for this.
 * <p>
 * Metadata like Charset or FileAttributes are supplied by the Recipe creating the Remote so that when the file is
 * later downloaded it can be configured correctly.
 * If no Charset is configured the downloaded file will be interpreted as binary data.
 * If no FileAttributes are set the downloaded file will be marked as readable and writable but not executable.
 * If a Checksum is provided it will be used to validate the integrity of the downloaded file.
 */
public interface Remote extends SourceFile {
    URI getUri();

    <R extends Remote> R withUri(URI uri);

    /**
     * Any text describing what this remote URI represents. Used to present human-readable results to an end user.
     */
    String getDescription();

    <R extends Remote> R withDescription(String description);

    @Nullable
    default Checksum getChecksum() {
        return null;
    }

    default <T extends SourceFile> T withChecksum(@Nullable Checksum checksum) {
        //noinspection unchecked
        return (T) this;
    }


    /**
     * Download the remote file
     *
     * @param httpSender used to download the file represented by this Remote
     */
    InputStream getInputStream(HttpSender httpSender);

    @Override
    default <P> String printAll(P p) {
        return StringUtils.readFully(getInputStream(new HttpUrlConnectionSender()));
    }

    @Override
    default <P> String printAllTrimmed(P p) {
        return StringUtils.trimIndentPreserveCRLF(printAll(p));
    }

    @SuppressWarnings("unchecked")
    @Override
    default <R extends Tree, P> R accept(TreeVisitor<R, P> v, P p) {
        return (R) ((RemoteVisitor<P>) v).visitRemote(this, p);
    }

    @Override
    default <P> boolean isAcceptable(TreeVisitor<?, P> v, P p) {
        return v instanceof BinaryVisitor;
    }

    static void applyPatch(/*~~>*/List<Remote> remotes, Path projectDir) {

    }

    static Builder builder(SourceFile before, URI uri) {
        return new Builder(before.getId(), before.getSourcePath(), before.getMarkers(), uri);
    }

    static Builder builder(Path sourcePath, URI uri) {
        return new Builder(Tree.randomId(), sourcePath, Markers.EMPTY, uri);
    }

    static Builder builder(UUID id, Path sourcePath, Markers markers, URI uri) {
        return new Builder(id, sourcePath, markers, uri);
    }

    class Builder {
        protected final UUID id;
        protected final Path sourcePath;
        protected final Markers markers;
        protected final URI uri;

        @Nullable
        @Language("markdown")
        protected String description;

        @Nullable
        protected Checksum checksum;

        @Nullable
        Charset charset;

        boolean charsetBomMarked;

        @Nullable
        FileAttributes fileAttributes;

        Builder(UUID id, Path sourcePath, Markers markers, URI uri) {
            this.id = id;
            this.sourcePath = sourcePath;
            this.markers = markers;
            this.uri = uri;
        }

        public Builder description(@Language("markdown") String description) {
            this.description = description;
            return this;
        }

        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        public Builder charsetBomMarked(boolean charsetBomMarked) {
            this.charsetBomMarked = charsetBomMarked;
            return this;
        }

        public Builder fileAttributes(FileAttributes fileAttributes) {
            this.fileAttributes = fileAttributes;
            return this;
        }

        public RemoteFile build() {
            return new RemoteFile(id, sourcePath, markers, uri, charset, charsetBomMarked, fileAttributes, description);
        }

        public RemoteArchive build(Path path) {
            return new RemoteArchive(id, sourcePath, markers, uri, charset, charsetBomMarked, fileAttributes, description, path);
        }
    }
}
