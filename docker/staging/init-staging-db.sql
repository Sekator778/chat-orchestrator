-- Schemas required by the application (mirrors the smoke harness).
-- PostgreSQL's public schema exists by default; Liquibase creates the rest of
-- the objects on first app boot (spring.liquibase, currentSchema=bot).
CREATE SCHEMA IF NOT EXISTS bot;
CREATE SCHEMA IF NOT EXISTS tgscan;
