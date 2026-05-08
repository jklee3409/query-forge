# Latest Retrieval Report

## Mode Summary

| mode | recall@5 | hit@5 | mrr@10 | ndcg@10 | adoption_rate | bad_rewrite_rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only | 0.4813 | 0.5375 | 0.3448 | 0.3853 | 0.0000 | 0.0000 |
| memory_only_gated | 0.4313 | 0.4875 | 0.3837 | 0.3838 | 1.0000 | 0.1750 |
| rewrite_always | 0.4688 | 0.5250 | 0.3437 | 0.3856 | 1.0000 | 0.0375 |

## Quick Graph (MRR@10)

- raw_only: `███████·············` 0.3448
- memory_only_gated: `████████············` 0.3837
- rewrite_always: `███████·············` 0.3437

## Category Summary

| mode | category | recall@5 | hit@5 | mrr@10 | ndcg@10 |
| --- | --- | ---: | ---: | ---: | ---: |
| raw_only | short_user | 0.4813 | 0.5375 | 0.3448 | 0.3853 |
| memory_only_gated | short_user | 0.4313 | 0.4875 | 0.3837 | 0.3838 |
| rewrite_always | short_user | 0.4688 | 0.5250 | 0.3437 | 0.3856 |

## Latency

| mode | avg_latency_ms | p95_latency_ms |
| --- | ---: | ---: |
| raw_only | 15611.09 | 503.26 |
| memory_only_gated | 8765.03 | 992.87 |
| rewrite_always | 14770.33 | 20224.21 |
