package com.enthusia.donors.sandbox.domain;

import com.enthusia.donors.sandbox.domain.Domain.*;
import com.enthusia.donors.sandbox.storage.SandboxDatabase;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Serial, atomic state updates with immutable snapshots for all main-thread readers. */
public final class SandboxEngine implements AutoCloseable {
    private final SandboxDatabase db;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"EnthusiaDonors-test-db");t.setDaemon(true);return t;});
    private State state;
    private volatile View view;
    private final AtomicBoolean failNext=new AtomicBoolean();
    public SandboxEngine(SandboxDatabase db,Instant now,ZoneId zone) throws Exception {
        this.db=db; state=db.load(Ledger.empty(now,zone)); view=Ledger.view(state);
    }
    public <T> CompletableFuture<T> edit(String action,Function<State,T> edit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if(failNext.getAndSet(false)) throw new IllegalStateException("Injected database failure; no changes were committed.");
                State candidate=state.copy(); T result=edit.apply(candidate);
                db.save(candidate,action,"Isolated sandbox operation");
                View snapshot=Ledger.view(candidate); state=candidate; view=snapshot; return result;
            } catch(Exception ex) { throw new CompletionException(ex); }
        },worker);
    }
    public CompletableFuture<List<String>> logs() { return CompletableFuture.supplyAsync(() -> {try{return db.logs(30);}catch(Exception e){throw new CompletionException(e);}},worker); }
    public void injectDatabaseFailure() { failNext.set(true); }
    public View view() { return view; }
    public SandboxDatabase database() { return db; }
    public CompletableFuture<Void> reset(Instant now) {
        return CompletableFuture.runAsync(() -> {
            try { db.backup(); State next=Ledger.empty(now,ZoneId.of(state.zone));
                db.save(next,"RESET","Test-state reset; previous database backed up; dispatch receipts retained"); state=next; view=Ledger.view(next);
            } catch(Exception ex) {throw new CompletionException(ex);}
        },worker);
    }
    @Override public void close() { worker.shutdown(); try {if(!worker.awaitTermination(8,TimeUnit.SECONDS)) worker.shutdownNow();} catch(InterruptedException e){Thread.currentThread().interrupt();worker.shutdownNow();} }
}
