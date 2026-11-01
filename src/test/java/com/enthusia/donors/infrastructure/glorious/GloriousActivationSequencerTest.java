package com.enthusia.donors.infrastructure.glorious;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class GloriousActivationSequencerTest {
    @Test void activationIsAttemptedBeforeAClosingMonthFetchFails() {
        var activated=new AtomicBoolean();
        var result=GloriousActivationSequencer.beforeFetch(Runnable::run,()->activated.set(true),()->{
            assertTrue(activated.get());return CompletableFuture.<String>failedFuture(new IllegalStateException("Tebex unavailable"));
        });
        assertThrows(CompletionException.class,result::join);assertTrue(activated.get());
    }
    @Test void activationFailureDoesNotBlockPublicDonorRefresh() {
        var result=GloriousActivationSequencer.beforeFetch(Runnable::run,()->{throw new IllegalStateException("database unavailable");},
                ()->CompletableFuture.completedFuture("public data"));
        assertEquals("public data",result.join());
    }
}
