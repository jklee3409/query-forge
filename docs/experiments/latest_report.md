# Latest Retrieval Report

## Mode Summary

| mode | recall@5 | hit@5 | mrr@10 | ndcg@10 | adoption_rate | bad_rewrite_rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only | 0.4813 | 0.5375 | 0.3448 | 0.3853 | 0.0000 | 0.0000 |
| memory_only_gated | 0.4313 | 0.4875 | 0.3837 | 0.3838 | 1.0000 | 0.1750 |
| rewrite_always | 0.4813 | 0.5500 | 0.3569 | 0.4005 | 1.0000 | 0.1000 |

## Quick Graph (MRR@10)

- raw_only: `███████·············` 0.3448
- memory_only_gated: `████████············` 0.3837
- rewrite_always: `███████·············` 0.3569

## Category Summary

| mode | category | recall@5 | hit@5 | mrr@10 | ndcg@10 |
| --- | --- | ---: | ---: | ---: | ---: |
| raw_only | short_user | 0.4813 | 0.5375 | 0.3448 | 0.3853 |
| memory_only_gated | short_user | 0.4313 | 0.4875 | 0.3837 | 0.3838 |
| rewrite_always | short_user | 0.4813 | 0.5500 | 0.3569 | 0.4005 |

## Latency

| mode | avg_latency_ms | p95_latency_ms |
| --- | ---: | ---: |
| raw_only | 17771.79 | 1644.35 |
| memory_only_gated | 12283.83 | 3216.54 |
| rewrite_always | 32512.70 | 37738.65 |
