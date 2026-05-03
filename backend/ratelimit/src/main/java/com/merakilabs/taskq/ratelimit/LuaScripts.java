package com.merakilabs.taskq.ratelimit;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the bundled Lua scripts from the classpath, holds the source + the
 * {@code SCRIPT LOAD} SHA, and provides {@code EVALSHA} with transparent
 * {@code EVAL} fallback when the script is evicted (NOSCRIPT).
 */
public final class LuaScripts {

    private static final Logger LOG = LoggerFactory.getLogger(LuaScripts.class);

    public static final String TOKEN_BUCKET = "lua/token-bucket.lua";
    public static final String FAIR_SEMAPHORE = "lua/fair-semaphore.lua";
    public static final String SEMAPHORE_REFRESH = "lua/semaphore-refresh.lua";

    private final Map<String, String> source = new HashMap<>();
    private final Map<String, String> sha = new HashMap<>();

    public LuaScripts() {
        load(TOKEN_BUCKET);
        load(FAIR_SEMAPHORE);
        load(SEMAPHORE_REFRESH);
    }

    private void load(final String resource) {
        try (InputStream in = LuaScripts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing Lua resource: " + resource);
            }
            final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            source.put(resource, text);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }

    /** Lazily SCRIPT LOAD on first use, then call EVALSHA; on NOSCRIPT, reload + retry once. */
    @SuppressWarnings("unchecked")
    public <T> T eval(
            final RedisCommands<String, String> cmds,
            final String resource,
            final ScriptOutputType type,
            final String[] keys,
            final String... args) {
        final String text = source.get(resource);
        if (text == null) {
            throw new IllegalArgumentException("Unknown script: " + resource);
        }
        String digest = sha.get(resource);
        if (digest == null) {
            digest = cmds.scriptLoad(text);
            sha.put(resource, digest);
            LOG.debug("Loaded Lua script {} sha={}", resource, digest);
        }
        try {
            return (T) cmds.evalsha(digest, type, keys, args);
        } catch (final RedisNoScriptException nse) {
            LOG.warn("NOSCRIPT for {}, reloading", resource);
            digest = cmds.scriptLoad(text);
            sha.put(resource, digest);
            return (T) cmds.evalsha(digest, type, keys, args);
        }
    }

    /**
     * Async EVALSHA via Lettuce's async API. Returns a future that completes on Lettuce's
     * I/O event-executor, never blocks the caller. On NOSCRIPT it reloads and retries
     * once, transparently. Caller must not assume any particular completion thread.
     */
    public <T> CompletionStage<T> evalAsync(
            final RedisAsyncCommands<String, String> async,
            final String resource,
            final ScriptOutputType type,
            final String[] keys,
            final String... args) {
        final String text = source.get(resource);
        if (text == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown script: " + resource));
        }
        String digest = sha.get(resource);
        if (digest == null) {
            try {
                digest = async.scriptLoad(text).get();
                sha.put(resource, digest);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(ie);
            } catch (final ExecutionException ee) {
                return CompletableFuture.failedFuture(ee.getCause() == null ? ee : ee.getCause());
            }
        }
        final String d = digest;
        @SuppressWarnings("unchecked")
        final RedisFuture<T> first = (RedisFuture<T>) async.evalsha(d, type, keys, args);
        return first.toCompletableFuture()
                .<T>thenApply(v -> v)
                .exceptionallyCompose(t -> {
                    final Throwable cause = unwrap(t);
                    if (!(cause instanceof RedisNoScriptException)) {
                        return CompletableFuture.failedFuture(cause);
                    }
                    LOG.warn("NOSCRIPT for {} (async), reloading", resource);
                    return async.scriptLoad(text).toCompletableFuture()
                            .thenCompose(reloaded -> {
                                sha.put(resource, reloaded);
                                @SuppressWarnings("unchecked")
                                final RedisFuture<T> retry =
                                        (RedisFuture<T>) async.evalsha(reloaded, type, keys, args);
                                return retry.toCompletableFuture();
                            });
                });
    }

    private static Throwable unwrap(final Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }
        return t;
    }
}
