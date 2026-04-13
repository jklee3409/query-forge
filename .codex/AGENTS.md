# AGENTS.md

## 0. Purpose

This document defines strict rules and research constraints for AI agents (Codex) working on the query-forge project.

Agents must follow these rules to ensure:
- consistent architecture
- correct research implementation
- reproducible experiment results

## 1. Project Goal

The project is a research-driven RAG system with:
- synthetic query generation (A/B/C/D strategies)
- quality gating pipeline
- memory construction
- evaluation dataset generation
- RAG evaluation (retrieval + answer)

Agents must not change the fundamental pipeline flow.

## 2. Core Pipeline Flow

The following order must be preserved:

collect → preprocess → chunk → glossary → import  
→ generate-queries → gate-queries → build-memory  
→ build-eval-dataset → eval-retrieval → eval-answer

## 3. Research Constraints

### 3.1 Synthetic Query Separation

Synthetic queries must be stored in separate tables for each strategy:
- synthetic_queries_raw_a
- synthetic_queries_raw_b
- synthetic_queries_raw_c
- synthetic_queries_raw_d

Agents must not merge these tables into a single structure.

Any processing step must explicitly specify the target strategy table or target strategy set.

### 3.2 Selective Quality Gating

Quality gating must support filtering by generation strategy.

Examples:
- only A queries
- only B queries
- A + C combined

Agents must implement strategy-based filtering before gating.

### 3.3 Adjustable Gating Weights

All gating stages must support dynamic configuration.

Examples include:
- Rule Filter: text length threshold
- Utility: Top-K scoring
- LLM Self Evaluation: per-criteria score threshold

These parameters must be:
- injected dynamically per request
- configurable from the Admin GUI

Hardcoding these values is strictly prohibited.

### 3.4 Evaluation Dataset Structure

Evaluation datasets must contain:
- user query
- ground truth answer

Optional metadata may include:
- purpose
- evaluation intent
- target strategy
- query type

Example:

```json
{
  "query": "...",
  "answer": "...",
  "purpose": "comparison",
  "target_strategy": ["A"]
}
```

The exact schema for purpose and evaluation intent is not fully fixed yet.

Agents must not enforce a strict mandatory taxonomy for purpose until the user defines it explicitly.

## 3.5 Evaluation Dataset (CRITICAL - RETRIEVAL AWARE)

Evaluation datasets in this project are NOT simple QA datasets.

They MUST be retrieval-aware datasets that include document and chunk grounding.

Each evaluation item MUST include:

- user_query_ko
- expected_doc_ids
- expected_chunk_ids
- expected_answer_key_points

Agents MUST NOT generate evaluation datasets that only contain question and answer.

---

### Dataset Structure (MANDATORY)

Each item MUST follow JSONL structure:

{
"sample_id": "...",
"split": "test",
"user_query_ko": "...",
"dialog_context": {},
"expected_doc_ids": ["..."],
"expected_chunk_ids": ["..."],
"expected_answer_key_points": ["..."],
"query_category": "...",
"difficulty": "...",
"single_or_multi_chunk": "...",
"source_product": "...",
"source_version_if_available": "..."
}

---

### A/B/C/D Method-Aware Dataset (MANDATORY)

The dataset MUST support evaluation of A/B/C/D synthetic query generation methods.

This means:

- Same document/chunk MUST be used to generate DIFFERENT query styles
- Queries MUST vary depending on A/B/C/D strategy

---

### Method-Specific Query Characteristics

A:
- translated-style Korean queries (English-first artifacts allowed)

B:
- natural Korean developer queries

C:
- structured, precise, multi-step queries
- may require multi-chunk reasoning

D:
- code-mixed queries (Korean + English + technical terms)

---

### Additional Required Field

Each dataset item MUST include:

- "target_method": "A | B | C | D"

Optional:
- "evaluation_focus": ["translation", "grounding", "naturalness", ...]

---

### Grounding Constraint (STRICT)

- All queries MUST be answerable from corpus
- expected_doc_ids MUST be correct
- expected_chunk_ids MUST be correct
- expected_answer_key_points MUST reflect chunk content

NO hallucination is allowed.

---

### Dataset Quality Rules

Agents MUST:

- avoid duplicate questions
- avoid trivial paraphrasing
- generate realistic developer queries
- include both single-chunk and multi-chunk cases

---

### Prohibited

Agents MUST NOT:

- generate QA-only datasets
- ignore chunk grounding
- ignore A/B/C/D differences

## 4. Execution and Documentation Rules

### 4.1 Root Progress Tracking

All work performed by Codex must be recorded in the root `progress.md`.

On every new session, the agent must read `progress.md` before starting work.

The root `progress.md` must:
- remain concise
- record only major progress and important decisions
- avoid overly long narrative descriptions

### 4.2 Directory-Level Documentation

Every actively used directory must contain:
- `index.md` for directory role and structure
- `progress.md` for local code change history

When modifying code in a directory, the agent must also update:
- that directory's `progress.md`
- that directory's `index.md` if structure, role, or implemented features changed

### 4.3 README Rules

The root `README.md` must include:
- overall project objective
- research overview
- research details
- methodology
- end-to-end project flow

Each major directory should have its own `README.md` describing its implementation details.

All `README.md` files must be written in Korean.
Technical terms may remain in English.
README content should be written in descriptive narrative form, not as fragment-only bullet lists.
