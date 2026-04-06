INSERT INTO corpus_sources (
    source_id, source_type, product_name, source_name, base_url, include_patterns, exclude_patterns, default_version, enabled
) VALUES (
    'spring-framework-reference',
    'html',
    'spring-framework',
    'spring-framework-reference',
    'https://docs.spring.io/spring-framework/reference/',
    '["https://docs.spring.io/spring-framework/reference/"]'::jsonb,
    '["/api/"]'::jsonb,
    '7.0.6',
    TRUE
);

INSERT INTO corpus_runs (
    run_id, run_type, run_status, trigger_type, source_scope, config_snapshot, started_at, finished_at, duration_ms, summary_json, created_by
) VALUES (
    '11111111-1111-1111-1111-111111111111',
    'import',
    'success',
    'manual',
    '{"source_ids":["spring-framework-reference"]}'::jsonb,
    '{"batch_size":50}'::jsonb,
    NOW() - INTERVAL '2 minutes',
    NOW() - INTERVAL '1 minute',
    60000,
    '{"docs_inserted":2}'::jsonb,
    'test-user'
);

INSERT INTO corpus_run_steps (
    step_id, run_id, step_name, step_order, step_status, input_artifact_path, output_artifact_path, metrics_json, started_at, finished_at
) VALUES
(
    '11111111-1111-1111-1111-111111111112',
    '11111111-1111-1111-1111-111111111111',
    'import_docs',
    1,
    'success',
    'fixtures/sections.jsonl',
    'postgresql://corpus',
    '{"documents":{"inserted":2}}'::jsonb,
    NOW() - INTERVAL '2 minutes',
    NOW() - INTERVAL '90 seconds'
),
(
    '11111111-1111-1111-1111-111111111113',
    '11111111-1111-1111-1111-111111111111',
    'import_glossary',
    2,
    'success',
    'fixtures/glossary.jsonl',
    'postgresql://corpus',
    '{"terms":{"inserted":1}}'::jsonb,
    NOW() - INTERVAL '80 seconds',
    NOW() - INTERVAL '60 seconds'
);

INSERT INTO corpus_documents (
    document_id, source_id, product_name, version_label, canonical_url, title, section_path_text,
    heading_hierarchy_json, raw_checksum, cleaned_checksum, raw_text, cleaned_text, language_code,
    content_type, collected_at, normalized_at, is_active, import_run_id, metadata_json
) VALUES
(
    'doc_fixture_1',
    'spring-framework-reference',
    'spring-framework',
    '7.0.6',
    'https://docs.spring.io/spring-framework/reference/core/beans.html',
    'Bean Basics',
    'Bean Basics',
    '["Bean Basics"]'::jsonb,
    'raw-doc-1',
    'clean-doc-1',
    '[PARAGRAPH] BeanFactory basics. [NAV] Previous / Next',
    'BeanFactory basics.',
    'en',
    'html',
    NOW() - INTERVAL '3 days',
    NOW() - INTERVAL '2 days',
    TRUE,
    '11111111-1111-1111-1111-111111111111',
    '{"section_count":2}'::jsonb
),
(
    'doc_fixture_2',
    'spring-framework-reference',
    'spring-framework',
    '7.0.6',
    'https://docs.spring.io/spring-framework/reference/core/aop.html',
    'AOP Basics',
    'AOP Basics',
    '["AOP Basics"]'::jsonb,
    'raw-doc-2',
    'clean-doc-2',
    '[PARAGRAPH] AOP intro',
    'AOP intro',
    'en',
    'html',
    NOW() - INTERVAL '3 days',
    NOW() - INTERVAL '2 days',
    TRUE,
    '11111111-1111-1111-1111-111111111111',
    '{"section_count":1}'::jsonb
);

INSERT INTO corpus_sections (
    section_id, document_id, parent_section_id, heading_level, heading_text, section_order,
    section_path_text, content_text, code_block_count, table_count, list_count, import_run_id, structural_blocks_json
) VALUES
(
    'sec_fixture_1',
    'doc_fixture_1',
    NULL,
    1,
    'Bean Basics',
    0,
    'Bean Basics',
    'BeanFactory basics.',
    0,
    0,
    0,
    '11111111-1111-1111-1111-111111111111',
    '[{"type":"paragraph","text":"BeanFactory basics."}]'::jsonb
),
(
    'sec_fixture_2',
    'doc_fixture_1',
    'sec_fixture_1',
    2,
    'Bean Definitions',
    1,
    'Bean Basics > Bean Definitions',
    'Bean definitions can use @Value and BeanFactory.',
    1,
    0,
    1,
    '11111111-1111-1111-1111-111111111111',
    '[{"type":"paragraph","text":"Bean definitions can use @Value and BeanFactory."}]'::jsonb
),
(
    'sec_fixture_3',
    'doc_fixture_2',
    NULL,
    1,
    'AOP Basics',
    0,
    'AOP Basics',
    'AOP intro',
    0,
    0,
    0,
    '11111111-1111-1111-1111-111111111111',
    '[{"type":"paragraph","text":"AOP intro"}]'::jsonb
);

INSERT INTO corpus_chunks (
    chunk_id, document_id, section_id, chunk_index_in_document, chunk_index_in_section, section_path_text,
    chunk_text, char_len, token_len, overlap_from_prev_chars, previous_chunk_id, next_chunk_id,
    code_presence, table_presence, list_presence, product_name, version_label, content_checksum,
    import_run_id, metadata_json
) VALUES
(
    'chk_fixture_1',
    'doc_fixture_1',
    'sec_fixture_1',
    0,
    0,
    'Bean Basics',
    'Section Path: Bean Basics\n\nBeanFactory basics.',
    42,
    8,
    0,
    NULL,
    'chk_fixture_2',
    FALSE,
    FALSE,
    FALSE,
    'spring-framework',
    '7.0.6',
    'checksum-chk-1',
    '11111111-1111-1111-1111-111111111111',
    '{"section_ids":["sec_fixture_1"]}'::jsonb
),
(
    'chk_fixture_2',
    'doc_fixture_1',
    'sec_fixture_2',
    1,
    0,
    'Bean Basics > Bean Definitions',
    'Overlap context from previous chunk:\nBeanFactory basics.\n\nSection Path: Bean Basics > Bean Definitions\n\n@Bean and @Value examples.',
    124,
    21,
    19,
    'chk_fixture_1',
    NULL,
    TRUE,
    FALSE,
    TRUE,
    'spring-framework',
    '7.0.6',
    'checksum-chk-2',
    '11111111-1111-1111-1111-111111111111',
    '{"section_ids":["sec_fixture_2"]}'::jsonb
),
(
    'chk_fixture_3',
    'doc_fixture_2',
    'sec_fixture_3',
    0,
    0,
    'AOP Basics',
    'Section Path: AOP Basics\n\nAOP intro',
    33,
    6,
    0,
    NULL,
    NULL,
    FALSE,
    FALSE,
    FALSE,
    'spring-framework',
    '7.0.6',
    'checksum-chk-3',
    '11111111-1111-1111-1111-111111111111',
    '{"section_ids":["sec_fixture_3"]}'::jsonb
);

INSERT INTO corpus_chunk_relations (
    relation_id, source_chunk_id, target_chunk_id, relation_type, distance_in_doc, import_run_id
) VALUES
(
    '33333333-3333-3333-3333-333333333331',
    'chk_fixture_1',
    'chk_fixture_2',
    'near',
    1,
    '11111111-1111-1111-1111-111111111111'
);

INSERT INTO corpus_glossary_terms (
    term_id, canonical_form, normalized_form, term_type, keep_in_english, description_short,
    source_confidence, first_seen_document_id, first_seen_chunk_id, evidence_count, is_active,
    import_run_id, metadata_json
) VALUES
(
    '22222222-2222-2222-2222-222222222222',
    '@Value',
    '@value',
    'annotation',
    TRUE,
    'Imported annotation term.',
    0.9,
    'doc_fixture_1',
    'chk_fixture_2',
    2,
    TRUE,
    '11111111-1111-1111-1111-111111111111',
    '{"document_ids":["doc_fixture_1"]}'::jsonb
);

INSERT INTO corpus_glossary_aliases (
    alias_id, term_id, alias_text, alias_language, alias_type, import_run_id
) VALUES
(
    '44444444-4444-4444-4444-444444444441',
    '22222222-2222-2222-2222-222222222222',
    'Value',
    'en',
    'same_case',
    '11111111-1111-1111-1111-111111111111'
),
(
    '44444444-4444-4444-4444-444444444442',
    '22222222-2222-2222-2222-222222222222',
    '@value',
    'en',
    'same_case',
    '11111111-1111-1111-1111-111111111111'
);

INSERT INTO corpus_glossary_evidence (
    evidence_id, term_id, document_id, chunk_id, matched_text, line_or_offset_info, import_run_id
) VALUES
(
    '55555555-5555-5555-5555-555555555551',
    '22222222-2222-2222-2222-222222222222',
    'doc_fixture_1',
    'chk_fixture_2',
    '@Value',
    '{"start_offset": 10, "end_offset": 16, "chunk_index_in_document": 1}'::jsonb,
    '11111111-1111-1111-1111-111111111111'
),
(
    '55555555-5555-5555-5555-555555555552',
    '22222222-2222-2222-2222-222222222222',
    'doc_fixture_1',
    'chk_fixture_2',
    'Value',
    '{"start_offset": 10, "end_offset": 15, "chunk_index_in_document": 1}'::jsonb,
    '11111111-1111-1111-1111-111111111111'
);
