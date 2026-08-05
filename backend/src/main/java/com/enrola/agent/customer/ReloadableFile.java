package com.enrola.agent.customer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A text file that is re-read when its mtime changes, so the prompt can be edited in place
 * while the app is running. The version stamp is content-derived rather than filename-derived:
 * editing system-v1.md would otherwise leave two messages claiming the same version with
 * different content, which destroys the only evidence for whether a prompt change helped.
 */
public final class ReloadableFile {

    private final Path path;
    private final String stem;
    private volatile long loadedMtime = -1;
    private volatile String content;
    private volatile String version;

    public ReloadableFile(Path path) {
        this.path = path;
        var name = path.getFileName().toString();
        var dot = name.lastIndexOf('.');
        this.stem = dot < 0 ? name : name.substring(0, dot);
        reloadIfStale();
    }

    public String content() {
        reloadIfStale();
        return content;
    }

    public String version() {
        reloadIfStale();
        return version;
    }

    private synchronized void reloadIfStale() {
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (mtime == loadedMtime) {
                return;
            }
            var bytes = Files.readAllBytes(path);
            content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            version = stem + "@" + shortHash(bytes);
            loadedMtime = mtime;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    private static String shortHash(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
