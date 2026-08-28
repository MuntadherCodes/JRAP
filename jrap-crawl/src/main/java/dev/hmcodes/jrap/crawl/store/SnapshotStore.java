package dev.hmcodes.jrap.crawl.store;

/**
 * Object storage boundary for snapshot payloads (FR-CRWL-3, SRS §2.4).
 * The filesystem implementation serves development, tests and single-node beta;
 * an S3/MinIO adapter lands behind this same interface in the ops-hardening phase.
 */
public interface SnapshotStore {

    /** Stores the bytes and returns the storage key. Keys are content-addressed per audit. */
    String put(String auditId, String category, String contentHash, byte[] bytes);

    byte[] get(String storageKey);
}
