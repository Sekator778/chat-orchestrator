-- Schemas required by the application (mirrors the staging and smoke stands).
-- PostgreSQL's public schema exists by default; Liquibase creates every object
-- inside them on first app boot (spring.liquibase, currentSchema=bot).
CREATE SCHEMA IF NOT EXISTS bot;
CREATE SCHEMA IF NOT EXISTS tgscan;
