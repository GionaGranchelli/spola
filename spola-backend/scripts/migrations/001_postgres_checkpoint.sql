-- PostgreSQL DDL for TramAI workflow checkpoint table.
-- Used by JdbcWorkflowCheckpointStore when Spola is configured with
-- database.postgres.enabled = true.
--
-- The column names and types match the default JdbcWorkflowCheckpointTable
-- definition in TramAI's orchestration module.

CREATE TABLE IF NOT EXISTS tramai_workflow_checkpoint (
    workflow_name          VARCHAR(255) NOT NULL,
    workflow_id            VARCHAR(255) NOT NULL,
    next_step_index        INTEGER      NOT NULL,
    step_executions        INTEGER      NOT NULL,
    last_completed_step_name VARCHAR(255),
    state_payload          TEXT         NOT NULL,
    revision               BIGINT       NOT NULL,
    metadata_payload       TEXT         NOT NULL,
    saved_at_epoch_millis  BIGINT       NOT NULL,
    PRIMARY KEY (workflow_name, workflow_id)
);

-- Index for listing checkpoints sorted by workflow name and id
CREATE INDEX IF NOT EXISTS idx_checkpoint_workflow
    ON tramai_workflow_checkpoint (workflow_name, workflow_id);
