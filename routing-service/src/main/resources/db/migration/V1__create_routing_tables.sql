-- ── routing_rules: source of truth for all topic destinations ──────────
CREATE TABLE routing_rules (
    event_type    VARCHAR(100) PRIMARY KEY,
    topic         VARCHAR(255) NOT NULL,
    owner_service VARCHAR(100) NOT NULL,
    direction     VARCHAR(10)  NOT NULL CHECK (direction IN ('PUBLISH', 'SUBSCRIBE')),
    description   VARCHAR(500),
    active        BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ── routing_audit_log: immutable history of every route change ───────────
CREATE TABLE routing_audit_log (
    id          BIGSERIAL    PRIMARY KEY,
    event_type  VARCHAR(100) NOT NULL,
    old_topic   VARCHAR(255),
    new_topic   VARCHAR(255),
    changed_by  VARCHAR(100),
    changed_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    reason      VARCHAR(500)
);

CREATE INDEX idx_audit_event_type ON routing_audit_log (event_type);
CREATE INDEX idx_audit_changed_at ON routing_audit_log (changed_at DESC);
