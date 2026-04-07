# Latest Retrieval Report

## Mode Summary

| mode | recall@5 | hit@5 | mrr@10 | ndcg@10 | adoption_rate | bad_rewrite_rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| raw_only | 1.0000 | 1.0000 | 0.7048 | 0.8805 | 0.0000 | 0.0000 |
| memory_only_ungated | 1.0000 | 1.0000 | 0.7048 | 0.8805 | 0.0000 | 0.0000 |
| memory_only_gated | 1.0000 | 1.0000 | 0.7048 | 0.8805 | 1.0000 | 0.0000 |
| rewrite_always | 1.0000 | 1.0000 | 0.7048 | 0.8805 | 1.0000 | 0.0000 |
| selective_rewrite | 1.0000 | 1.0000 | 0.7048 | 0.8805 | 0.0000 | 0.0000 |
| selective_rewrite_with_session | 1.0000 | 1.0000 | 0.7048 | 0.8805 | 0.0000 | 0.0000 |

## Quick Graph (MRR@10)

- raw_only: `██████████████······` 0.7048
- memory_only_ungated: `██████████████······` 0.7048
- memory_only_gated: `██████████████······` 0.7048
- rewrite_always: `██████████████······` 0.7048
- selective_rewrite: `██████████████······` 0.7048
- selective_rewrite_with_session: `██████████████······` 0.7048

## Category Summary

| mode | category | recall@5 | hit@5 | mrr@10 | ndcg@10 |
| --- | --- | ---: | ---: | ---: | ---: |
| raw_only | general_ko | 1.0000 | 1.0000 | 0.6933 | 0.8782 |
| raw_only | troubleshooting | 1.0000 | 1.0000 | 0.7889 | 0.9682 |
| raw_only | short_user | 1.0000 | 1.0000 | 0.6833 | 0.8577 |
| raw_only | code_mixed | 1.0000 | 1.0000 | 0.6667 | 0.9077 |
| raw_only | follow_up | 1.0000 | 1.0000 | 0.6667 | 0.7500 |
| memory_only_ungated | general_ko | 1.0000 | 1.0000 | 0.6933 | 0.8782 |
| memory_only_ungated | troubleshooting | 1.0000 | 1.0000 | 0.7889 | 0.9682 |
| memory_only_ungated | short_user | 1.0000 | 1.0000 | 0.6833 | 0.8577 |
| memory_only_ungated | code_mixed | 1.0000 | 1.0000 | 0.6667 | 0.9077 |
| memory_only_ungated | follow_up | 1.0000 | 1.0000 | 0.6667 | 0.7500 |
| memory_only_gated | general_ko | 1.0000 | 1.0000 | 0.6933 | 0.8782 |
| memory_only_gated | troubleshooting | 1.0000 | 1.0000 | 0.7889 | 0.9682 |
| memory_only_gated | short_user | 1.0000 | 1.0000 | 0.6833 | 0.8577 |
| memory_only_gated | code_mixed | 1.0000 | 1.0000 | 0.6667 | 0.9077 |
| memory_only_gated | follow_up | 1.0000 | 1.0000 | 0.6667 | 0.7500 |
| rewrite_always | general_ko | 1.0000 | 1.0000 | 0.6933 | 0.8782 |
| rewrite_always | troubleshooting | 1.0000 | 1.0000 | 0.7889 | 0.9682 |
| rewrite_always | short_user | 1.0000 | 1.0000 | 0.6833 | 0.8577 |
| rewrite_always | code_mixed | 1.0000 | 1.0000 | 0.6667 | 0.9077 |
| rewrite_always | follow_up | 1.0000 | 1.0000 | 0.6667 | 0.7500 |
| selective_rewrite | general_ko | 1.0000 | 1.0000 | 0.6933 | 0.8782 |
| selective_rewrite | troubleshooting | 1.0000 | 1.0000 | 0.7889 | 0.9682 |
| selective_rewrite | short_user | 1.0000 | 1.0000 | 0.6833 | 0.8577 |
| selective_rewrite | code_mixed | 1.0000 | 1.0000 | 0.6667 | 0.9077 |
| selective_rewrite | follow_up | 1.0000 | 1.0000 | 0.6667 | 0.7500 |
| selective_rewrite_with_session | general_ko | 1.0000 | 1.0000 | 0.6933 | 0.8782 |
| selective_rewrite_with_session | troubleshooting | 1.0000 | 1.0000 | 0.7889 | 0.9682 |
| selective_rewrite_with_session | short_user | 1.0000 | 1.0000 | 0.6833 | 0.8577 |
| selective_rewrite_with_session | code_mixed | 1.0000 | 1.0000 | 0.6667 | 0.9077 |
| selective_rewrite_with_session | follow_up | 1.0000 | 1.0000 | 0.6667 | 0.7500 |

## Latency

| mode | avg_latency_ms | p95_latency_ms |
| --- | ---: | ---: |
| raw_only | 1.03 | 1.22 |
| memory_only_ungated | 1.25 | 2.06 |
| memory_only_gated | 2.98 | 3.25 |
| rewrite_always | 5.79 | 6.29 |
| selective_rewrite | 5.79 | 6.39 |
| selective_rewrite_with_session | 5.90 | 7.40 |
