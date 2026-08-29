package dev.hmcodes.jrap.crawl.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Filesystem-backed snapshot store (the default). Content-addressed: identical bytes share one file. */
@Component
@ConditionalOnProperty(name = "jrap.snapshots.store", havingValue = "filesystem", matchIfMissing = true)
public class FileSystemSnapshotStore implements SnapshotStore {

    private final Path root;

    public FileSystemSnapshotStore(@Value("${jrap.snapshots.root-dir:./data/snapshots}") String rootDir) {
        this.root = Path.of(rootDir);
    }

    @Override
    public String put(String auditId, String category, String contentHash, byte[] bytes) {
        String key = auditId + "/" + category + "/" + contentHash;
        Path path = root.resolve(key);
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.write(path, bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store snapshot payload " + key, e);
        }
        return key;
    }

    @Override
    public byte[] get(String storageKey) {
        try {
            return Files.readAllBytes(root.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read snapshot payload " + storageKey, e);
        }
    }
}
