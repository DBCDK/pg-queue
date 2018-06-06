
CREATE OR REPLACE FUNCTION pgqueue_admin_requeue(like_expr TEXT) RETURNS SETOF queue_error AS $$
  DECLARE
    columns1 TEXT;
    columns2 TEXT;
    func TEXT;
  BEGIN
    SELECT STRING_AGG(column_name, ', '), STRING_AGG('q.' || column_name, ', ') INTO columns1, columns2 FROM information_schema.columns WHERE table_schema='public' AND table_name='queue' AND column_name NOT IN ('queued', 'dequeueafter', 'tries');
    func := 'CREATE FUNCTION pg_temp.pgqueue_admin_requeue_impl(like_expr TEXT) RETURNS SETOF queue_error AS $' || '$ DECLARE q queue_error; c CURSOR FOR SELECT * FROM queue_error WHERE diag LIKE like_expr; BEGIN FOR q IN c LOOP INSERT INTO queue(' || columns1 || ') VALUES(' || columns2 || '); DELETE FROM queue_error WHERE CURRENT OF c; RETURN NEXT q; END LOOP; END $' || '$ LANGUAGE plpgsql;';
    --RAISE NOTICE 'sql: %', func;
    EXECUTE func;
    RETURN QUERY SELECT * FROM pg_temp.pgqueue_admin_requeue_impl(like_expr);
    EXECUTE 'DROP FUNCTION pg_temp.pgqueue_admin_requeue_impl(like_expr TEXT)';
  END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgqueue_admin_requeue(consumer TEXT, like_expr TEXT) RETURNS SETOF queue_error AS $$
  DECLARE
    columns1 TEXT;
    columns2 TEXT;
    func TEXT;
  BEGIN
    SELECT STRING_AGG(column_name, ', '), STRING_AGG('q.' || column_name, ', ') INTO columns1, columns2 FROM information_schema.columns WHERE table_schema='public' AND table_name='queue' AND column_name NOT IN ('queued', 'dequeueafter', 'tries');
    func := 'CREATE FUNCTION pg_temp.pgqueue_admin_requeue_impl(consumer_expr TEXT, like_expr TEXT) RETURNS SETOF queue_error AS $' || '$ DECLARE q queue_error; c CURSOR FOR SELECT * FROM queue_error WHERE consumer = consumer_expr AND  diag LIKE like_expr; BEGIN FOR q IN c LOOP INSERT INTO queue('  || columns1 || ') VALUES(' || columns2 || '); DELETE FROM queue_error WHERE CURRENT OF c; RETURN NEXT q; END LOOP; END $' || '$ LANGUAGE plpgsql;';
    --RAISE NOTICE 'sql: %', func;
    EXECUTE func;
    RETURN QUERY SELECT * FROM pg_temp.pgqueue_admin_requeue_impl(consumer, like_expr);
    EXECUTE 'DROP FUNCTION pg_temp.pgqueue_admin_requeue_impl(consumer_expr TEXT, like_expr TEXT)';
  END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgqueue_admin_requeue() RETURNS SETOF queue_error AS $$
  BEGIN
    RETURN QUERY SELECT * FROM pgqueue_admin_requeue('%');
  END
$$ LANGUAGE plpgsql;


CREATE OR REPLACE FUNCTION pgqueue_admin_discard(like_expr TEXT) RETURNS SETOF queue_error AS $$
  DECLARE
    columns1 TEXT;
    columns2 TEXT;
    func TEXT;
  BEGIN
    SELECT STRING_AGG(column_name, ', '), STRING_AGG('q.' || column_name, ', ') INTO columns1, columns2 FROM information_schema.columns WHERE table_schema='public' AND table_name='queue' AND column_name NOT IN ('queued', 'dequeueafter', 'tries');
    func := 'CREATE FUNCTION pg_temp.pgqueue_admin_discard_impl(like_expr TEXT) RETURNS SETOF queue_error AS $' || '$ DECLARE q queue_error; c CURSOR FOR SELECT * FROM queue_error WHERE diag LIKE like_expr; BEGIN FOR q IN c LOOP DELETE FROM queue_error WHERE CURRENT OF c; RETURN NEXT q; END LOOP; END $' || '$ LANGUAGE plpgsql;';
    --RAISE NOTICE 'sql: %', func;
    EXECUTE func;
    RETURN QUERY SELECT * FROM pg_temp.pgqueue_admin_discard_impl(like_expr);
    EXECUTE 'DROP FUNCTION pg_temp.pgqueue_admin_discard_impl(like_expr TEXT)';
  END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgqueue_admin_discard(consumer TEXT, like_expr TEXT) RETURNS SETOF queue_error AS $$
  DECLARE
    columns1 TEXT;
    columns2 TEXT;
    func TEXT;
  BEGIN
    SELECT STRING_AGG(column_name, ', '), STRING_AGG('q.' || column_name, ', ') INTO columns1, columns2 FROM information_schema.columns WHERE table_schema='public' AND table_name='queue' AND column_name NOT IN ('queued', 'dequeueafter', 'tries');
    func := 'CREATE FUNCTION pg_temp.pgqueue_admin_discard_impl(consumer_expr TEXT, like_expr TEXT) RETURNS SETOF queue_error AS $' || '$ DECLARE q queue_error; c CURSOR FOR SELECT * FROM queue_error WHERE consumer = consumer_expr AND  diag LIKE like_expr; BEGIN FOR q IN c LOOP DELETE FROM queue_error WHERE CURRENT OF c; RETURN NEXT q; END LOOP; END $' || '$ LANGUAGE plpgsql;';
    --RAISE NOTICE 'sql: %', func;
    EXECUTE func;
    RETURN QUERY SELECT * FROM pg_temp.pgqueue_admin_discard_impl(consumer, like_expr);
    EXECUTE 'DROP FUNCTION pg_temp.pgqueue_admin_discard_impl(consumer_expr TEXT, like_expr TEXT)';
  END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgqueue_admin_discard() RETURNS SETOF queue_error AS $$
  BEGIN
    RETURN QUERY SELECT * FROM pgqueue_admin_discard('%');
  END
$$ LANGUAGE plpgsql;

