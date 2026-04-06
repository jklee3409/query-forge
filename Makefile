EXPERIMENT ?= scaffold
GRADLEW ?= ./backend/gradlew

.PHONY: up down logs backend-test run-backend collect-docs preprocess chunk-docs glossary-docs import-corpus generate-queries gate-queries build-memory build-eval-dataset eval-retrieval eval-answer

up:
	docker compose up -d postgres

down:
	docker compose down

logs:
	docker compose logs -f postgres

backend-test:
	$(GRADLEW) -p backend test

run-backend:
	$(GRADLEW) -p backend bootRun

collect-docs:
	python pipeline/cli.py collect-docs --experiment $(EXPERIMENT)

preprocess:
	python pipeline/cli.py preprocess --experiment $(EXPERIMENT)

chunk-docs:
	python pipeline/cli.py chunk-docs --experiment $(EXPERIMENT)

glossary-docs:
	python pipeline/cli.py glossary-docs --experiment $(EXPERIMENT)

import-corpus:
	python pipeline/cli.py import-corpus --experiment $(EXPERIMENT)

generate-queries:
	python pipeline/cli.py generate-queries --experiment $(EXPERIMENT)

gate-queries:
	python pipeline/cli.py gate-queries --experiment $(EXPERIMENT)

build-memory:
	python pipeline/cli.py build-memory --experiment $(EXPERIMENT)

build-eval-dataset:
	python pipeline/cli.py build-eval-dataset --experiment $(EXPERIMENT)

eval-retrieval:
	python pipeline/cli.py eval-retrieval --experiment $(EXPERIMENT)

eval-answer:
	python pipeline/cli.py eval-answer --experiment $(EXPERIMENT)
