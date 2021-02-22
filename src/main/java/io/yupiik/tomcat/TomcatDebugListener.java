package io.yupiik.tomcat;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.juli.logging.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

public class TomcatDebugListener extends BaseListener {
    @Override
    public void accept(final LifecycleEvent lifecycleEvent) {
        if (Container.START_EVENT.equals(lifecycleEvent.getType()) && Context.class.isInstance(lifecycleEvent.getSource())) {
            onStart(Context.class.cast(lifecycleEvent.getSource()));
        }
    }

    private void onStart(final Context context) {
        final Log logger = context.getParent().getParent().getLogger();
        logger.info("[TOMCAT_DEBUG] starting '" + context.getPath() + "'\n" +
                "  - ClassLoader:\n" +
                findUrls(context.getLoader().getClassLoader())
                        // .sorted(): already sorted in tomcat order, don't change it
                        .map(url -> "    * " + url + "\n" + metadata(url))
                        .collect(joining("\n")));
    }

    private String metadata(final URL url) {
        switch (url.getProtocol()) {
            case "jar":
                final String file = url.getFile();
                try {
                    return metadata(new URL(file.substring(0, file.lastIndexOf("!"))));
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException(e);
                }
            case "file":
                final Path path = Paths.get(url.getFile());
                try {
                    return "      o length=" + size(path) + " - use 'du -sb <folder>' to compare,\n" +
                            "      o md5sum=" + md5(path) + " - use " + (Files.isDirectory(path) ?
                            "'find " + path.toAbsolutePath() + " -type f -exec md5sum {} \\; | cut -d\" \" -f1 | md5sum'" :
                            "'md5sum " + path.toAbsolutePath() + "'") + " to compare\n" +
                            "      o lastModified=" + lastModified(path);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
        }
        return null;
    }

    private OffsetDateTime lastModified(final Path path) throws IOException {
        if (Files.isDirectory(path)) {
            final AtomicReference<OffsetDateTime> last = new AtomicReference<>();
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    final OffsetDateTime fileDate = lastModified(file);
                    final OffsetDateTime previous = last.get();
                    if (previous == null || fileDate.isBefore(previous)) {
                        last.set(fileDate);
                    }
                    return super.visitFile(file, attrs);
                }
            });
            return last.get();
        }
        return OffsetDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.of("UTC"));
    }

    private long size(final Path path) throws IOException {
        if (Files.isDirectory(path)) {
            final AtomicLong size = new AtomicLong();
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    size.addAndGet(size(file));
                    return super.visitFile(file, attrs);
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    size.addAndGet(Files.size(dir));
                    return super.postVisitDirectory(dir, exc);
                }
            });
            return size.get();
        }
        return Files.size(path);
    }

    private String md5(final Path path) throws IOException {
        final OutputStream devNull = new OutputStream() {
            @Override
            public void write(final byte[] b) {
                // no-op
            }

            @Override
            public void write(final byte[] b, final int off, final int len) {
                // no-op
            }

            @Override
            public void write(final int b) {
                // no-op
            }
        };
        final String hashingAlgo = "MD5";
        if (Files.isDirectory(path)) {
            try (final DigestOutputStream wrapping = new DigestOutputStream(devNull, MessageDigest.getInstance(hashingAlgo))) {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        try (final DigestOutputStream stream = new DigestOutputStream(devNull, MessageDigest.getInstance(hashingAlgo))) {
                            Files.copy(file, stream);
                            stream.flush();
                            final byte[] bytes = toDigestString(stream).getBytes(StandardCharsets.UTF_8);
                            wrapping.write(bytes);
                            wrapping.write('\n');
                        } catch (final NoSuchAlgorithmException e) {
                            throw new IllegalStateException(e);
                        }
                        return super.visitFile(file, attrs);
                    }
                });
                return toDigestString(wrapping);
            } catch (final NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        try (final DigestOutputStream stream = new DigestOutputStream(devNull, MessageDigest.getInstance(hashingAlgo))) {
            Files.copy(path, stream);
            return toDigestString(stream);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toDigestString(final DigestOutputStream stream) throws IOException {
        stream.flush();
        return hex(stream.getMessageDigest().digest());
    }

    private Stream<URL> findUrls(final ClassLoader loader) {
        if (WebappClassLoaderBase.class.isInstance(loader)) {
            return Stream.concat(
                    Stream.of(WebappClassLoaderBase.class.cast(loader).getResources())
                            .flatMap(root -> root.getBaseUrls().stream()),
                    Stream.of(WebappClassLoaderBase.class.cast(loader).getURLs()));
        }
        if (URLClassLoader.class.isInstance(loader)) {
            return Stream.of(URLClassLoader.class.cast(loader).getURLs());
        }
        return Stream.of();
    }

    private static String hex(final byte[] bytes) {
        return IntStream.range(0, bytes.length)
                .mapToObj(i -> (bytes[i] <= 0x0F && bytes[i] >= 0x00 ? "0" : "") + String.format("%x", bytes[i]))
                .collect(joining());
    }
}
