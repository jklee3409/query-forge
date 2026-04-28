# Latest Retrieval Report

## Mode Summary

| mode | recall@5 | hit@5 | mrr@10 | ndcg@10 | adoption_rate | bad_rewrite_rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only | 0.4750 | 0.5375 | 0.3425 | 0.3811 | 0.0000 | 0.0000 |
| memory_only_gated | 0.4000 | 0.4500 | 0.2968 | 0.3230 | 1.0000 | 0.2375 |
| rewrite_always | 0.4437 | 0.5000 | 0.3420 | 0.3691 | 1.0000 | 0.1750 |
| selective_rewrite | 0.4750 | 0.5375 | 0.3462 | 0.3870 | 0.1375 | 0.0909 |

## Quick Graph (MRR@10)

- raw_only: `███████·············` 0.3425
- memory_only_gated: `██████··············` 0.2968
- rewrite_always: `███████·············` 0.3420
- selective_rewrite: `███████·············` 0.3462

## Category Summary

| mode | category | recall@5 | hit@5 | mrr@10 | ndcg@10 |
| --- | --- | ---: | ---: | ---: | ---: |
| raw_only | short_user | 0.4750 | 0.5375 | 0.3425 | 0.3811 |
| memory_only_gated | short_user | 0.4000 | 0.4500 | 0.2968 | 0.3230 |
| rewrite_always | short_user | 0.4437 | 0.5000 | 0.3420 | 0.3691 |
| selective_rewrite | short_user | 0.4750 | 0.5375 | 0.3462 | 0.3870 |

## Latency

| mode | avg_latency_ms | p95_latency_ms |
| --- | ---: | ---: |
| raw_only | 19740.92 | 21230.77 |
| memory_only_gated | 19800.91 | 21144.34 |
| rewrite_always | 81535.74 | 87248.63 |
| selective_rewrite | 81453.59 | 88123.05 |
