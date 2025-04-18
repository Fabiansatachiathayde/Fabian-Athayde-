/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.file.collections;

import org.gradle.api.GradleException;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.file.RelativePath;
import org.gradle.api.specs.Spec;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;

public class ReproducibleDirectoryWalker implements DirectoryWalker {
    private final FileSystem fileSystem;

    public ReproducibleDirectoryWalker(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    @Override
    public void walkDir(Path rootDir, RelativePath rootPath, FileVisitor visitor, Spec<? super FileTreeElement> spec, AtomicBoolean stopFlag, boolean postfix) {
        try {
            PathVisitor pathVisitor = new PathVisitor(spec, postfix, visitor, stopFlag, rootPath, fileSystem);
            PathWithAttributes rootDirWithAttributes = toPathWithAttributes(rootDir);
            visit(rootDirWithAttributes, pathVisitor);
        } catch (IOException e) {
            throw new GradleException(String.format("Could not list contents of directory '%s'.", rootDir), e);
        }
    }

    private static FileVisitResult visit(PathWithAttributes pathWithAttributes, PathVisitor pathVisitor) throws IOException {
        if (pathWithAttributes.exception != null) {
            return pathVisitor.visitFileFailed(pathWithAttributes.path, pathWithAttributes.exception);
        }
        Path path = pathWithAttributes.path;
        BasicFileAttributes attrs = checkNotNull(pathWithAttributes.attrs);

        if (attrs.isDirectory()) {
            FileVisitResult fvr = pathVisitor.preVisitDirectory(path, attrs);
            if (fvr == FileVisitResult.SKIP_SUBTREE || fvr == FileVisitResult.TERMINATE) {
                return fvr;
            }
            IOException exception = null;
            try (Stream<Path> fileStream = Files.list(path)) {
                Iterable<PathWithAttributes> files = () -> fileStream
                    .map(ReproducibleDirectoryWalker::toPathWithAttributes)
                    .sorted(FILES_FIRST)
                    .iterator();
                for (PathWithAttributes child : files) {
                    FileVisitResult childResult = visit(child, pathVisitor);
                    if (childResult == FileVisitResult.TERMINATE) {
                        return childResult;
                    }
                }
            } catch (IOException e) {
                exception = e;
            }
            return pathVisitor.postVisitDirectory(path, exception);
        } else {
            return pathVisitor.visitFile(path, attrs);
        }
    }

    private static PathWithAttributes toPathWithAttributes(Path path) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            try {
                attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException next) {
                return new PathWithAttributes(path, null, e);
            }
        }
        return new PathWithAttributes(path, attrs, null);
    }

    private static class PathWithAttributes {
        private final Path path;
        @Nullable
        private final BasicFileAttributes attrs;
        @Nullable
        private final IOException exception;

        public PathWithAttributes(Path path, @Nullable BasicFileAttributes attrs, @Nullable IOException exception) {
            this.path = path;
            this.attrs = attrs;
            this.exception = exception;
        }

        public boolean isDirectory() {
            return attrs != null && attrs.isDirectory();
        }
    }

    private final static Comparator<PathWithAttributes> FILES_FIRST = Comparator.comparing(PathWithAttributes::isDirectory)
        .thenComparing(p -> p.path.toString());
}
