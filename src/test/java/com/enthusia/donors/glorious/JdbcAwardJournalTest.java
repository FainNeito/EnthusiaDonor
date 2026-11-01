package com.enthusia.donors.glorious;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.enthusia.donors.infrastructure.glorious.GloriousAwardService;
import com.enthusia.donors.infrastructure.glorious.GloriousActivationSequencer;
import com.enthusia.donors.tebex.AwardScanFixture;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JdbcAwardJournalTest {
    @TempDir Path folder;
    private static final ZoneId UTC=ZoneId.of("UTC");
    private Connection open() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:"+folder.resolve("awards.db")); }
    private void setup() throws SQLException {
        try(Connection c=open();Statement s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE enthusiadonors_test_projection(source_id TEXT PRIMARY KEY,revision INTEGER NOT NULL,owner_token TEXT,lease_until INTEGER,lease_epoch INTEGER)");
            s.executeUpdate("CREATE TABLE enthusiadonors_glorious_control(source_id TEXT PRIMARY KEY,activated_at INTEGER NOT NULL,timezone TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE enthusiadonors_glorious_awards(source_id TEXT NOT NULL,month_key TEXT NOT NULL,winner_uuid TEXT,state TEXT NOT NULL,PRIMARY KEY(source_id,month_key))");
            s.executeUpdate("CREATE TABLE enthusiadonors_glorious_payer_reviews(source_id TEXT NOT NULL,month_key TEXT NOT NULL,payment_hash TEXT NOT NULL,payer_uuid TEXT NOT NULL,reviewer_uuid TEXT NOT NULL,evidence_digest TEXT NOT NULL,reviewed_at INTEGER NOT NULL,PRIMARY KEY(source_id,payment_hash))");
            s.executeUpdate("CREATE TABLE enthusiadonors_glorious_recoveries(source_id TEXT NOT NULL,month_key TEXT NOT NULL,winner_uuid TEXT NOT NULL,reviewer_uuid TEXT NOT NULL,evidence_digest TEXT NOT NULL,resolved_at INTEGER NOT NULL,PRIMARY KEY(source_id,month_key))");
        }
        try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO enthusiadonors_test_projection VALUES ('test',1,'owner',?,7)")) {
            p.setLong(1,System.currentTimeMillis()+120_000);p.executeUpdate();
        }
    }
    private void seedActivation(Instant activation) throws SQLException {
        try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO enthusiadonors_glorious_control VALUES ('test',?,'UTC')")) {p.setLong(1,activation.toEpochMilli());p.executeUpdate();}
    }
    @Test void activationIsPersistentAndOldMonthsCannotBeBackfilled() throws Exception {
        setup();var journal=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");
        var first=journal.activate("owner",7,UTC);assertEquals(first,journal.activate("owner",7,UTC));
        assertThrows(SQLException.class,()->journal.activate("owner",7,ZoneId.of("America/Chicago")));
        assertThrows(SQLException.class,()->journal.decide("owner",7,UTC,AwardDecision.noWinner(YearMonth.now(UTC).minusMonths(1))));
        assertThrows(SQLException.class,()->journal.activate("other",7,UTC));
    }
    @Test void activationSurvivesFailedTebexFetchAndSubsequentRestart() throws Exception {
        setup();var journal=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");
        var first=new java.util.concurrent.atomic.AtomicReference<ActivationBoundary>();
        var refresh=GloriousActivationSequencer.beforeFetch(Runnable::run,()->{
            try {first.set(journal.activate("owner",7,UTC));}catch(SQLException error){throw new IllegalStateException(error);}
        },()->java.util.concurrent.CompletableFuture.<String>failedFuture(new IllegalStateException("Tebex unavailable")));
        assertThrows(java.util.concurrent.CompletionException.class,refresh::join);assertNotNull(first.get());
        var restarted=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");
        assertEquals(first.get(),restarted.activate("owner",7,UTC));
        assertThrows(SQLException.class,()->restarted.decide("owner",7,UTC,AwardDecision.noWinner(YearMonth.from(first.get().activatedAt().atZone(UTC)).minusMonths(1))));
    }
    @Test void finalizationAndClaimAreUniqueAndFenced() throws Exception {
        setup();YearMonth previous=YearMonth.now(ZoneOffset.UTC).minusMonths(1);
        seedActivation(previous.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        var journal=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");
        UUID winner=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var award=AwardDecision.winner(previous,winner);
        assertEquals(award,journal.decide("owner",7,UTC,award));
        assertEquals(award,journal.decide("owner",7,UTC,award));
        assertThrows(SQLException.class,()->journal.decide("owner",7,UTC,AwardDecision.winner(previous,UUID.randomUUID())));
        assertThrows(SQLException.class,()->journal.transition("owner",8,previous,AwardDecision.State.READY,AwardDecision.State.GRANTING));
        assertTrue(journal.transition("owner",7,previous,AwardDecision.State.READY,AwardDecision.State.GRANTING));
        assertFalse(journal.transition("owner",7,previous,AwardDecision.State.READY,AwardDecision.State.GRANTING));
        assertTrue(journal.transition("owner",7,previous,AwardDecision.State.GRANTING,AwardDecision.State.UNCERTAIN));
        assertFalse(journal.transition("owner",7,previous,AwardDecision.State.GRANTING,AwardDecision.State.AWARDED));
    }
    @Test void reviewsAreAuditedIdempotentAndCannotOverwriteOrCrossMonths() throws Exception {
        setup(); var month=YearMonth.now(UTC).minusMonths(1);
        seedActivation(month.atDay(1).atStartOfDay(UTC).toInstant());
        var journal=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");
        var payer=UUID.randomUUID();var reviewer=UUID.randomUUID();String payment="a".repeat(64), evidence="b".repeat(64);
        assertThrows(SQLException.class,()->journal.recordPayerReview("other",7,month,payment,payer,reviewer,evidence));
        assertThrows(IllegalArgumentException.class,()->journal.recordPayerReview("owner",7,month,"bad",payer,reviewer,evidence));
        journal.recordPayerReview("owner",7,month,payment,payer,reviewer,evidence);
        journal.recordPayerReview("owner",7,month,payment,payer,reviewer,evidence);
        assertEquals(java.util.Map.of(payment,payer),journal.reviewedPayers("owner",7,month));
        assertTrue(journal.reviewedPayers("owner",7,month.minusMonths(1)).isEmpty());
        assertThrows(SQLException.class,()->journal.recordPayerReview("owner",7,month,payment,UUID.randomUUID(),reviewer,evidence));
        assertThrows(SQLException.class,()->journal.recordPayerReview("owner",7,month.minusMonths(1),payment,payer,reviewer,evidence));
        journal.decide("owner",7,UTC,AwardDecision.noWinner(month));
        assertThrows(SQLException.class,()->journal.recordPayerReview("owner",7,month,"c".repeat(64),payer,reviewer,evidence));
        try(Connection c=open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT reviewer_uuid,evidence_digest,reviewed_at FROM enthusiadonors_glorious_payer_reviews")) {
            assertTrue(r.next());assertEquals(reviewer.toString(),r.getString(1));assertEquals(evidence,r.getString(2));assertTrue(r.getLong(3)>0);assertFalse(r.next());
        }
    }
    @Test void serviceHoldsUnreviewedThenGrantsVerifiedPayerOnce() throws Exception {
        setup();var month=YearMonth.now(UTC).minusMonths(1);
        var date=month.atDay(2).atStartOfDay(UTC).toInstant();seedActivation(month.atDay(1).atStartOfDay(UTC).toInstant());
        var journal=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");
        var payer=UUID.randomUUID();var recipient=UUID.randomUUID();var reviewer=UUID.randomUUID();String payment="a".repeat(64);
        int[] grants={0};var service=GloriousAwardService.start(journal,"owner",7,UTC,player->{assertEquals(payer,player);grants[0]++;return AwardCoordinator.GrantResult.SAVED;});
        var config=new com.enthusia.donors.config.DonorsConfig("test",10,10,3,1000,"tebex_payments_only",false,true,true,true,
                java.util.Set.of(),java.util.Set.of(),java.util.Set.of(),java.util.Set.of(),UTC,"$",true,10,"None","$0","-",true,false,
                Path.of("unused"),false,"","","","","","","",false,true,false,12);
        var record=new com.enthusia.donors.model.PaymentRecord(payment,recipient,"Recipient",new java.math.BigDecimal("30"),"USD","Complete",date,false,false,java.util.List.of(1),date);
        Instant scan=Instant.now().minusSeconds(1), now=Instant.now();
        var unsettled=new com.enthusia.donors.model.PaymentRecord(payment,recipient,"Recipient",new java.math.BigDecimal("30"),"USD","Pending",date,false,false,java.util.List.of(1),date);
        var unsettledScan=AwardScanFixture.create(scan,scan,java.util.List.of(unsettled));
        assertEquals(AwardCoordinator.Result.HELD,service.evaluate(month,now,unsettledScan,config,java.util.Set.of(),new java.math.BigDecimal("30")).result());
        assertTrue(journal.findDecision("owner",7,month).isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.evaluate(month,now,AwardScanFixture.fromSource(scan,scan,java.util.List.of(record),"wrong"),config,java.util.Set.of(),new java.math.BigDecimal("30")));
        assertEquals(0,grants[0]);
        var scanEvidence=AwardScanFixture.create(scan,scan,java.util.List.of(record));
        var held=service.evaluate(month,now,scanEvidence,config,java.util.Set.of(),new java.math.BigDecimal("30"));
        assertEquals(AwardCoordinator.Result.HELD,held.result());assertEquals(java.util.Set.of(payment),held.unverifiedPayments());assertEquals(0,grants[0]);
        assertEquals(java.util.Set.of(payment),service.pending(month,now,scanEvidence,config,java.util.Set.of()));
        assertThrows(IllegalArgumentException.class,()->service.review(month,now,scanEvidence,config,java.util.Set.of(),"c".repeat(64),payer,reviewer,"b".repeat(64)));
        service.review(month,now,scanEvidence,config,java.util.Set.of(),payment,payer,reviewer,"b".repeat(64));
        assertTrue(service.pending(month,now,scanEvidence,config,java.util.Set.of()).isEmpty());
        var awarded=service.evaluate(month,now,scanEvidence,config,java.util.Set.of(),new java.math.BigDecimal("30"));
        assertEquals(AwardCoordinator.Result.AWARDED,awarded.result());assertTrue(awarded.unverifiedPayments().isEmpty());assertEquals(1,grants[0]);
        assertThrows(IllegalStateException.class,()->service.pending(month,now,scanEvidence,config,java.util.Set.of()));
        assertEquals(AwardCoordinator.Result.ALREADY_RECORDED,service.evaluate(month,now,scanEvidence,config,java.util.Set.of(),new java.math.BigDecimal("30")).result());
        var laterScan=AwardScanFixture.create(scan,scan,java.util.List.of());
        assertEquals(AwardCoordinator.Result.ALREADY_RECORDED,service.evaluate(month,now,laterScan,config,java.util.Set.of(),new java.math.BigDecimal("30")).result());
        assertEquals(1,grants[0]);
    }
    @Test void uncertainRecoveryRequiresExactWinnerAndRecordsReviewerWithoutGranting() throws Exception {
        setup();var month=YearMonth.now(UTC).minusMonths(1);seedActivation(month.atDay(1).atStartOfDay(UTC).toInstant());
        var journal=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");UUID winner=UUID.randomUUID(),reviewer=UUID.randomUUID();
        journal.decide("owner",7,UTC,AwardDecision.winner(month,winner));
        assertThrows(SQLException.class,()->journal.confirmUncertain("owner",7,month,winner,reviewer,"b".repeat(64)));
        assertTrue(journal.transition("owner",7,month,AwardDecision.State.READY,AwardDecision.State.GRANTING));
        assertTrue(journal.transition("owner",7,month,AwardDecision.State.GRANTING,AwardDecision.State.UNCERTAIN));
        assertThrows(SQLException.class,()->journal.confirmUncertain("owner",8,month,winner,reviewer,"b".repeat(64)));
        assertThrows(SQLException.class,()->journal.confirmUncertain("owner",7,month,UUID.randomUUID(),reviewer,"b".repeat(64)));
        assertTrue(journal.confirmUncertain("owner",7,month,winner,reviewer,"b".repeat(64)));
        assertEquals(AwardDecision.State.AWARDED,journal.findDecision("owner",7,month).orElseThrow().state());
        assertThrows(SQLException.class,()->journal.confirmUncertain("owner",7,month,winner,reviewer,"b".repeat(64)));
        try(Connection c=open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT reviewer_uuid,evidence_digest FROM enthusiadonors_glorious_recoveries")) {
            assertTrue(r.next());assertEquals(reviewer.toString(),r.getString(1));assertEquals("b".repeat(64),r.getString(2));assertFalse(r.next());
        }
    }
    @Test void interruptedGrantCanBeConfirmedWithEvidenceWithoutGranting() throws Exception {
        setup();var month=YearMonth.now(UTC).minusMonths(1);seedActivation(month.atDay(1).atStartOfDay(UTC).toInstant());
        var journal=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");var winner=UUID.randomUUID();
        journal.decide("owner",7,UTC,AwardDecision.winner(month,winner));
        assertTrue(journal.transition("owner",7,month,AwardDecision.State.READY,AwardDecision.State.GRANTING));
        assertThrows(SQLException.class,()->journal.confirmUncertain("owner",7,month,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64)));
        assertTrue(journal.confirmUncertain("owner",7,month,winner,UUID.randomUUID(),"a".repeat(64)));
        assertEquals(AwardDecision.State.AWARDED,journal.findDecision("owner",7,month).orElseThrow().state());
    }
    @Test void guardedGrantChecksDatabaseLeaseBeforeCallingAdapter() throws Exception {
        setup();var journal=new JdbcAwardJournal(this::open,JdbcAwardJournal.Dialect.SQLITE,"test");var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(SQLException.class,()->journal.guardedGrant("owner",8,UUID.randomUUID(),(player,valid)->{calls.incrementAndGet();return AwardCoordinator.GrantResult.SAVED;}));
        assertEquals(0,calls.get());
        assertEquals(AwardCoordinator.GrantResult.HELD,journal.guardedGrant("owner",7,UUID.randomUUID(),(player,valid)->{
            assertTrue(valid.getAsBoolean());calls.incrementAndGet();return AwardCoordinator.GrantResult.HELD;
        }));
        assertEquals(1,calls.get());
    }
}
