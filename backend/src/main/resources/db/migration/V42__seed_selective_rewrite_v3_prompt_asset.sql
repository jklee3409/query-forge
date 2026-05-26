INSERT INTO prompt_assets (
    prompt_family,
    prompt_name,
    version,
    content_path,
    content_hash,
    is_active,
    metadata,
    storage_backend
)
VALUES (
    'rewrite',
    'selective_rewrite_v3',
    'v1',
    'configs/prompts/rewrite/selective_rewrite_v3.md',
    'seed:configs/prompts/rewrite/selective_rewrite_v3.md:v1',
    TRUE,
    '{"seed": "V42", "reason": "lightweight_two_candidate_rewrite_contract"}'::jsonb,
    'file'
)
ON CONFLICT (prompt_family, prompt_name, version) DO UPDATE
SET content_path = EXCLUDED.content_path,
    content_hash = EXCLUDED.content_hash,
    is_active = TRUE,
    metadata = prompt_assets.metadata || EXCLUDED.metadata,
    storage_backend = EXCLUDED.storage_backend,
    updated_at = NOW();

WITH active_prompt AS (
    SELECT prompt_asset_id
    FROM prompt_assets
    WHERE prompt_family = 'rewrite'
      AND prompt_name = 'selective_rewrite_v3'
      AND version = 'v1'
)
INSERT INTO prompt_asset_binding (
    binding_key,
    prompt_family,
    active_prompt_asset_id,
    description,
    metadata_json
)
SELECT 'rag_rewrite.ko',
       'rewrite',
       active_prompt.prompt_asset_id,
       'Korean and code-mixed lightweight RAG rewrite prompt',
       '{"seed": "V42", "active_prompt": "selective_rewrite_v3", "active_version": "v1", "candidate_count_max": 2}'::jsonb
FROM active_prompt
ON CONFLICT (binding_key) DO UPDATE
SET active_prompt_asset_id = EXCLUDED.active_prompt_asset_id,
    description = EXCLUDED.description,
    metadata_json = prompt_asset_binding.metadata_json || EXCLUDED.metadata_json,
    updated_at = NOW();

WITH fallback_prompts AS (
    SELECT 1 AS sort_order, prompt_asset_id
    FROM prompt_assets
    WHERE prompt_family = 'rewrite'
      AND prompt_name = 'selective_rewrite_v2'
      AND version = 'v5'
    UNION ALL
    SELECT 2 AS sort_order, prompt_asset_id
    FROM prompt_assets
    WHERE prompt_family = 'rewrite'
      AND prompt_name = 'selective_rewrite_v2'
      AND version = 'v4'
    UNION ALL
    SELECT 3 AS sort_order, prompt_asset_id
    FROM prompt_assets
    WHERE prompt_family = 'rewrite'
      AND prompt_name = 'selective_rewrite_v2'
      AND version = 'v3'
    UNION ALL
    SELECT 4 AS sort_order, prompt_asset_id
    FROM prompt_assets
    WHERE prompt_family = 'rewrite'
      AND prompt_name = 'selective_rewrite_v2'
      AND version = 'v2'
    UNION ALL
    SELECT 5 AS sort_order, prompt_asset_id
    FROM prompt_assets
    WHERE prompt_family = 'rewrite'
      AND prompt_name = 'selective_rewrite_v1'
      AND version = 'v1'
),
fallback_json AS (
    SELECT jsonb_agg(to_jsonb(prompt_asset_id::text) ORDER BY sort_order) AS fallback_prompt_asset_ids
    FROM fallback_prompts
)
UPDATE prompt_asset_binding
SET fallback_prompt_asset_ids = COALESCE(fallback_json.fallback_prompt_asset_ids, '[]'::jsonb),
    updated_at = NOW()
FROM fallback_json
WHERE binding_key = 'rag_rewrite.ko';
