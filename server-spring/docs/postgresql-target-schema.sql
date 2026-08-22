-- Documentation convenience entry point.
-- Flyway's migration below is the single executable source of truth; do not
-- duplicate DDL here because a copied schema inevitably drifts.
\ir ../bootstrap/src/main/resources/db/migration/V1__create_owned_storage.sql
