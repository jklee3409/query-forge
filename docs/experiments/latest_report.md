# Latest Retrieval Report

## Mode Summary

| mode | recall@5 | hit@5 | mrr@10 | ndcg@10 | adoption_rate | bad_rewrite_rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only | 0.4813 | 0.5375 | 0.3448 | 0.3853 | 0.0000 | 0.0000 |
| memory_only_gated | 0.4750 | 0.5375 | 0.3220 | 0.3662 | 1.0000 | 0.2125 |
| rewrite_always | 0.4562 | 0.5125 | 0.3405 | 0.3834 | 1.0000 | 0.0500 |
| selective_rewrite | 0.4813 | 0.5375 | 0.3448 | 0.3853 | 0.0000 | 0.0000 |

## Quick Graph (MRR@10)

- raw_only: `███████·············` 0.3448
- memory_only_gated: `██████··············` 0.3220
- rewrite_always: `███████·············` 0.3405
- selective_rewrite: `███████·············` 0.3448

## Category Summary

| mode | category | recall@5 | hit@5 | mrr@10 | ndcg@10 |
| --- | --- | ---: | ---: | ---: | ---: |
| raw_only | short_user | 0.4813 | 0.5375 | 0.3448 | 0.3853 |
| memory_only_gated | short_user | 0.4750 | 0.5375 | 0.3220 | 0.3662 |
| rewrite_always | short_user | 0.4562 | 0.5125 | 0.3405 | 0.3834 |
| selective_rewrite | short_user | 0.4813 | 0.5375 | 0.3448 | 0.3853 |

## Latency

| mode | avg_latency_ms | p95_latency_ms |
| --- | ---: | ---: |
| raw_only | 8951.87 | 248.78 |
| memory_only_gated | 9518.96 | 870.81 |
| rewrite_always | 15651.43 | 16400.02 |
| selective_rewrite | 16232.19 | 14283.01 |
