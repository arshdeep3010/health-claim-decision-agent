-- =====================================================================
-- V2__users.sql
-- Application users for JWT authentication and role-based authorization.
-- Passwords are stored only as BCrypt hashes. SYNTHETIC DATA ONLY.
-- =====================================================================

CREATE TABLE app_user (
    id            uuid PRIMARY KEY,
    username      varchar(128) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role          varchar(16)  NOT NULL,
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_app_user_role CHECK (role IN ('USER','REVIEWER','ADMIN'))
);
