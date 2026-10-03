\set ON_ERROR_STOP on

-- Run only through a separately authorized bootstrap-administrator session.
-- Supply all three values with psql -v; never save the command or values in Git.
\if :{?liw_app_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'liw_app_password is required'; END $$;
\endif
\if :{?liw_migrator_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'liw_migrator_password is required'; END $$;
\endif
\if :{?liw_operator_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'liw_operator_password is required'; END $$;
\endif

DO $roles$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'liw_app') THEN
    CREATE ROLE liw_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'liw_migrator') THEN
    CREATE ROLE liw_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'liw_operator') THEN
    CREATE ROLE liw_operator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END
$roles$;

ALTER ROLE liw_app PASSWORD :'liw_app_password';
ALTER ROLE liw_migrator PASSWORD :'liw_migrator_password';
ALTER ROLE liw_operator PASSWORD :'liw_operator_password';

GRANT CONNECT ON DATABASE line_item_watch TO liw_app, liw_migrator, liw_operator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO liw_migrator;
GRANT USAGE ON SCHEMA public TO liw_app, liw_operator;

-- Migration 008 requires this trusted extension, but release credentials do not.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER DEFAULT PRIVILEGES FOR ROLE liw_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO liw_app;
ALTER DEFAULT PRIVILEGES FOR ROLE liw_migrator IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO liw_app;
ALTER DEFAULT PRIVILEGES FOR ROLE liw_migrator IN SCHEMA public
  GRANT SELECT ON TABLES TO liw_operator;

-- Existing objects, if this script is rerun after a migration.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO liw_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO liw_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO liw_operator;

-- Runtime and operator identities must not mutate Liquibase control state.
DO $grants$
DECLARE
  object_name text;
BEGIN
  FOREACH object_name IN ARRAY ARRAY['databasechangelog', 'databasechangeloglock']
  LOOP
    IF to_regclass('public.' || object_name) IS NOT NULL THEN
      EXECUTE format(
        'REVOKE ALL PRIVILEGES ON TABLE public.%I FROM liw_app, liw_operator',
        object_name);
    END IF;
  END LOOP;

  -- P.8/P.9 one-shot recovery writes only. Rerun this script after migrations
  -- so newly created tables receive the explicit grants below.
  FOREACH object_name IN ARRAY ARRAY[
    'connection_credential',
    'line_item_watch_signal_processing',
    'line_item_watch_reliability_state',
    'line_item_watch_line_item_reliability',
    'line_item_watch_reconciliation_finding'
  ]
  LOOP
    IF to_regclass('public.' || object_name) IS NOT NULL THEN
      EXECUTE format('GRANT UPDATE ON TABLE public.%I TO liw_operator', object_name);
    END IF;
  END LOOP;

  FOREACH object_name IN ARRAY ARRAY[
    'line_item_watch_reliability_operation',
    'line_item_watch_replay_anchor',
    'line_item_watch_replay_anchor_deal'
  ]
  LOOP
    IF to_regclass('public.' || object_name) IS NOT NULL THEN
      EXECUTE format('GRANT INSERT ON TABLE public.%I TO liw_operator', object_name);
    END IF;
  END LOOP;

  FOREACH object_name IN ARRAY ARRAY[
    'line_item_watch_change_signal',
    'line_item_watch_audit_event',
    'line_item_watch_reliability_operation'
  ]
  LOOP
    IF to_regclass('public.' || object_name) IS NOT NULL THEN
      EXECUTE format('GRANT DELETE ON TABLE public.%I TO liw_operator', object_name);
    END IF;
  END LOOP;

  IF to_regclass('public.application_activity_audit') IS NOT NULL THEN
    EXECUTE 'GRANT INSERT, DELETE ON TABLE public.application_activity_audit TO liw_operator';
  END IF;
END
$grants$;
