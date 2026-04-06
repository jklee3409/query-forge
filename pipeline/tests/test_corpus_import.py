from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

import psycopg
from testcontainers.postgres import PostgresContainer

from pipeline.loaders.common import ImportOptions
from pipeline.loaders.import_corpus_to_postgres import run_import


REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "corpus_small"
MIGRATION_DIR = REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"


class CorpusImportIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.container = PostgresContainer(
            image="pgvector/pgvector:pg16",
            dbname="query_forge_test",
            username="query_forge",
            password="query_forge",
        )
        cls.container.start()
        cls.base_options = {
            "database_url": None,
            "host": cls.container.get_container_host_ip(),
            "port": int(cls.container.get_exposed_port(5432)),
            "database": "query_forge_test",
            "user": "query_forge",
            "password": "query_forge",
        }
        cls._apply_migrations()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.container.stop()

    @classmethod
    def _apply_migrations(cls) -> None:
        with psycopg.connect(
            host=cls.base_options["host"],
            port=cls.base_options["port"],
            dbname=cls.base_options["database"],
            user=cls.base_options["user"],
            password=cls.base_options["password"],
            autocommit=True,
        ) as connection:
            with connection.cursor() as cursor:
                for migration in sorted(MIGRATION_DIR.glob("V*.sql")):
                    cursor.execute(migration.read_text(encoding="utf-8"))

    def setUp(self) -> None:
        self.work_dir = Path(tempfile.mkdtemp(prefix="corpus-import-fixture-"))
        for name in ("raw.jsonl", "sections.jsonl", "chunks.jsonl", "glossary_terms.jsonl", "sources.yaml"):
            shutil.copy(FIXTURE_DIR / name, self.work_dir / name)
        self._cleanup_corpus_tables()

    def tearDown(self) -> None:
        shutil.rmtree(self.work_dir, ignore_errors=True)

    def _cleanup_corpus_tables(self) -> None:
        with self._connect(autocommit=True) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    TRUNCATE TABLE
                        corpus_glossary_evidence,
                        corpus_glossary_aliases,
                        corpus_glossary_terms,
                        corpus_chunk_relations,
                        corpus_chunks,
                        corpus_sections,
                        corpus_documents,
                        corpus_run_steps,
                        corpus_runs,
                        corpus_sources
                    RESTART IDENTITY CASCADE
                    """
                )

    def _connect(self, *, autocommit: bool = False) -> psycopg.Connection:
        return psycopg.connect(
            host=self.base_options["host"],
            port=self.base_options["port"],
            dbname=self.base_options["database"],
            user=self.base_options["user"],
            password=self.base_options["password"],
            autocommit=autocommit,
        )

    def _options(self, dry_run: bool = False) -> ImportOptions:
        return ImportOptions(
            database_url=None,
            host=self.base_options["host"],
            port=self.base_options["port"],
            database=self.base_options["database"],
            user=self.base_options["user"],
            password=self.base_options["password"],
            source_config_dir=self.work_dir,
            raw_input_path=self.work_dir / "raw.jsonl",
            sections_input_path=self.work_dir / "sections.jsonl",
            chunks_input_path=self.work_dir / "chunks.jsonl",
            glossary_input_path=self.work_dir / "glossary_terms.jsonl",
            dry_run=dry_run,
            batch_size=50,
            trigger_type="manual",
            created_by="test-user",
            source_ids=set(),
            document_ids=set(),
            run_type="import",
        )

    def _table_count(self, table_name: str) -> int:
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(f"SELECT COUNT(*) FROM {table_name}")
                return int(cursor.fetchone()[0])

    def test_small_fixture_import_inserts_expected_rows(self) -> None:
        summary = run_import(self._options())

        self.assertEqual(summary["docs_inserted"], 3)
        self.assertEqual(summary["sections_inserted"], 4)
        self.assertEqual(summary["chunks_inserted"], 4)
        self.assertGreaterEqual(summary["relations_inserted"], 2)
        self.assertEqual(summary["glossary_terms_inserted"], 2)
        self.assertEqual(self._table_count("corpus_documents"), 3)
        self.assertEqual(self._table_count("corpus_sections"), 4)
        self.assertEqual(self._table_count("corpus_chunks"), 4)
        self.assertEqual(self._table_count("corpus_glossary_terms"), 2)

    def test_reimport_is_idempotent_and_does_not_duplicate_rows(self) -> None:
        run_import(self._options())
        second_summary = run_import(self._options())

        self.assertEqual(second_summary["docs_inserted"], 0)
        self.assertEqual(second_summary["docs_updated"], 0)
        self.assertEqual(second_summary["chunks_inserted"], 0)
        self.assertEqual(second_summary["glossary_terms_inserted"], 0)
        self.assertEqual(self._table_count("corpus_documents"), 3)
        self.assertEqual(self._table_count("corpus_chunks"), 4)
        self.assertEqual(self._table_count("corpus_glossary_aliases"), 2)

    def test_changed_rows_only_update_targeted_document_and_chunk(self) -> None:
        run_import(self._options())

        sections_path = self.work_dir / "sections.jsonl"
        sections = [json.loads(line) for line in sections_path.read_text(encoding="utf-8").splitlines() if line.strip()]
        sections[1]["cleaned_text"] = "@Bean and @Value updated examples."
        sections[1]["structural_blocks"][0]["text"] = "@Bean and @Value updated examples."
        sections_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in sections) + "\n",
            encoding="utf-8",
        )

        chunks_path = self.work_dir / "chunks.jsonl"
        chunks = [json.loads(line) for line in chunks_path.read_text(encoding="utf-8").splitlines() if line.strip()]
        chunks[1]["content"] = "Overlap context from previous chunk:\nBeanFactory basics.\n\nSection Path: Bean Basics > Bean Definitions\n\n@Bean and @Value updated examples."
        chunks[1]["char_len"] = len(chunks[1]["content"])
        chunks[1]["token_len"] = 20
        chunks_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in chunks) + "\n",
            encoding="utf-8",
        )

        summary = run_import(self._options())

        self.assertEqual(summary["docs_updated"], 1)
        self.assertEqual(summary["sections_updated"], 1)
        self.assertEqual(summary["chunks_updated"], 1)

        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT cleaned_text FROM corpus_documents WHERE document_id = 'doc_test_1'"
                )
                self.assertIn("updated examples", cursor.fetchone()[0])
                cursor.execute(
                    "SELECT chunk_text FROM corpus_chunks WHERE chunk_id = 'chk_test_2'"
                )
                self.assertIn("updated examples", cursor.fetchone()[0])


if __name__ == "__main__":
    unittest.main()
