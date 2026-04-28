# Latest Retrieval Report

## Mode Summary

| mode | recall@5 | hit@5 | mrr@10 | ndcg@10 | adoption_rate | bad_rewrite_rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only | 0.5938 | 0.6500 | 0.4081 | 0.4627 | 0.0000 | 0.0000 |
| memory_only_gated | 0.2938 | 0.3000 | 0.2245 | 0.2409 | 1.0000 | 0.4750 |
| rewrite_always | 0.5563 | 0.6125 | 0.3943 | 0.4407 | 1.0000 | 0.1625 |

## Quick Graph (MRR@10)

- raw_only: `████████············` 0.4081
- memory_only_gated: `████················` 0.2245
- rewrite_always: `████████············` 0.3943

## Category Summary

| mode | category | recall@5 | hit@5 | mrr@10 | ndcg@10 |
| --- | --- | ---: | ---: | ---: | ---: |
| raw_only | short_user | 0.5938 | 0.6500 | 0.4081 | 0.4627 |
| memory_only_gated | short_user | 0.2938 | 0.3000 | 0.2245 | 0.2409 |
| rewrite_always | short_user | 0.5563 | 0.6125 | 0.3943 | 0.4407 |

## Latency

| mode | avg_latency_ms | p95_latency_ms |
| --- | ---: | ---: |
| raw_only | 61.84 | 104.14 |
| memory_only_gated | 46.32 | 76.15 |
| rewrite_always | 3497.64 | 6888.51 |
