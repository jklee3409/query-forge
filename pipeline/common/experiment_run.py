from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


@dataclass(slots=True)
class ExperimentRunContext:
    experiment_id: str
    experiment_run_id: str


class ExperimentRunRecorder:
    def __init__(self, connection: psycopg.Connection[Any]) -> None:
        self.connection = connection

    def start_run(
        self,
        *,
        experiment_key: str,
        category: str,
        description: str,
        config_path: str,
        config_hash: str,
        parameters: dict[str, Any],
        run_label: str | None = None,
    ) -> ExperimentRunContext:
        with self.connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO experiments (
                    experiment_key,
                    category,
                    description,
                    config_path,
                    config_hash,
                    metadata
                ) VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (experiment_key) DO UPDATE
                SET category = EXCLUDED.category,
                    description = EXCLUDED.description,
                    config_path = EXCLUDED.config_path,
                    config_hash = EXCLUDED.config_hash
                RETURNING experiment_id
                """,
                (
                    experiment_key,
                    category,
                    description,
                    config_path,
                    config_hash,
                    Jsonb({}),
                ),
            )
            experiment_row = cursor.fetchone()
            if experiment_row is None:
                raise RuntimeError("Failed to create or update experiment row.")
            if isinstance(experiment_row, dict):
                experiment_id = str(experiment_row["experiment_id"])
            else:
                experiment_id = str(experiment_row[0])

            experiment_run_id = str(uuid.uuid4())
            cursor.execute(
                """
                INSERT INTO experiment_runs (
                    experiment_run_id,
                    experiment_id,
                    run_label,
                    status,
                    parameters,
                    started_at
                ) VALUES (%s, %s, %s, 'running', %s, NOW())
                """,
                (
                    experiment_run_id,
                    experiment_id,
                    run_label,
                    Jsonb(parameters),
                ),
            )
        return ExperimentRunContext(
            experiment_id=experiment_id,
            experiment_run_id=experiment_run_id,
        )

    def finish_run(
        self,
        context: ExperimentRunContext,
        *,
        status: str,
        metrics: dict[str, Any],
        notes: str | None = None,
    ) -> None:
        with self.connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE experiment_runs
                SET status = %s,
                    metrics = %s,
                    notes = %s,
                    finished_at = NOW()
                WHERE experiment_run_id = %s
                """,
                (
                    status,
                    Jsonb(metrics),
                    notes,
                    context.experiment_run_id,
                ),
            )
