package com.enthusia.donors.infrastructure.glorious;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Persist an activation attempt before the network fetch without blocking other donor features on failure. */
public final class GloriousActivationSequencer {
    private GloriousActivationSequencer() { }
    public static <T> CompletableFuture<T> beforeFetch(Executor executor,Runnable activation,Supplier<CompletableFuture<T>> fetch) {
        Objects.requireNonNull(executor);Objects.requireNonNull(activation);Objects.requireNonNull(fetch);
        return CompletableFuture.runAsync(activation,executor).handle((ignored,error)->null).thenCompose(ignored->fetch.get());
    }
}
