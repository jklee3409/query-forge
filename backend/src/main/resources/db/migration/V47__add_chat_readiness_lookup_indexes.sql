CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_domain_model
    ON chunk_embeddings (domain_id, embedding_model);

CREATE INDEX IF NOT EXISTS idx_memory_entries_domain_strategy_snapshot
    ON memory_entries (
        domain_id,
        generation_strategy,
        (metadata ->> 'source_gate_run_id'),
        (metadata ->> 'source_gating_batch_id')
    );

CREATE INDEX IF NOT EXISTS idx_synthetic_query_gating_result_batch_strategy
    ON synthetic_query_gating_result (gating_batch_id, accepted, generation_strategy);
