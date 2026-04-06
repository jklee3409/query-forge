# PostgreSQL Container Notes

The Docker Compose setup uses the `pgvector/pgvector:pg16` image.

- `vector` and `pgcrypto` extensions are enabled through Flyway on application startup.
- Database schema source of truth lives under `backend/src/main/resources/db/migration/`.
- Extra bootstrap SQL can be placed under `infra/sql/` if future stages need offline administration scripts.

