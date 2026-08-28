package dev.hmcodes.jrap.aigateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable log of one LLM call (NFR-AI-1): prompt version, model id, input snapshot
 * ids, token usage, and the full response — enough to reproduce any audit's AI steps.
 */
@Entity
@Table(name = "llm_call")
public class LlmCall implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id")
    private UUID organisationId;

    @Column(name = "audit_id")
    private UUID auditId;

    @Column(name = "prompt_name", nullable = false)
    private String promptName;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(nullable = false)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_ids", nullable = false)
    private String inputSnapshotIds = "[]";

    @Column(name = "request_chars", nullable = false)
    private int requestChars;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(nullable = false)
    private String status;

    private String error;

    @Column(name = "response_text")
    private String responseText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LlmCall() {}

    public LlmCall(UUID id, UUID organisationId, UUID auditId, String promptName, String promptVersion,
                   String model, String inputSnapshotIds, int requestChars, Integer inputTokens,
                   Integer outputTokens, String status, String error, String responseText,
                   Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.promptName = promptName;
        this.promptVersion = promptVersion;
        this.model = model;
        this.inputSnapshotIds = inputSnapshotIds == null ? "[]" : inputSnapshotIds;
        this.requestChars = requestChars;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.status = status;
        this.error = error;
        this.responseText = responseText;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public String getPromptName() { return promptName; }
    public String getPromptVersion() { return promptVersion; }
    public String getModel() { return model; }
    public String getInputSnapshotIds() { return inputSnapshotIds; }
    public int getRequestChars() { return requestChars; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public String getResponseText() { return responseText; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
