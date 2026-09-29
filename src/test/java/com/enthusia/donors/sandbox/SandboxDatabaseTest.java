package com.enthusia.donors.sandbox;
import com.enthusia.donors.sandbox.domain.*;
import com.enthusia.donors.sandbox.domain.Domain.*;
import com.enthusia.donors.sandbox.storage.SandboxDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class SandboxDatabaseTest {
 @TempDir Path directory;Person alice=Person.synthetic("Alice");Instant now=Instant.parse("2026-09-20T12:00:00Z");ZoneId zone=ZoneId.of("America/Chicago");
 State sample(){State s=Ledger.empty(now,zone);Ledger.add(s,"payment",alice,alice,Product.AVID,Kind.PURCHASE,2000,s.now,30);Ledger.manualGlorious(s,alice);return s;}
 @Test void roundTripPersistsProfilesAndAwards() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);db.save(sample(),"TEST","test");State restored=new SandboxDatabase(directory).load(Ledger.empty(now,zone));assertEquals(2000,Ledger.view(restored).profile(alice.uuid()).alltimeCents());assertTrue(Ledger.view(restored).profile(alice.uuid()).glorious());}
 @Test void onlyDedicatedDatabaseCreated() throws Exception {new SandboxDatabase(directory);assertTrue(Files.exists(directory.resolve("donors-test.db")));assertFalse(Files.exists(directory.resolve("donors.db")));}
 @Test void duplicateDispatchClaimSuppressedAcrossRestart() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);assertTrue(db.claim("event","chat"));assertFalse(new SandboxDatabase(directory).claim("event","chat"));}
 @Test void channelsHaveSeparateClaims() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);assertTrue(db.claim("event","chat"));assertTrue(db.claim("event","discord"));}
 @Test void restartMarksUnfinishedDispatchUncertain() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);db.claim("event","discord");new SandboxDatabase(directory);try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db.file());Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT state FROM sandbox_effects")){assertTrue(rs.next());assertEquals("UNCERTAIN",rs.getString(1));}}
 @Test void successfulReceiptSurvivesRestart() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);db.claim("event","discord");db.finish("event","discord","SENT","Test accepted");new SandboxDatabase(directory);assertTrue(db.logs(10).stream().anyMatch(l -> l.contains("SENT")));assertFalse(db.claim("event","discord"));}
 @Test void backupContainsExistingState() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);db.save(sample(),"T","t");Path backup=db.backup();assertTrue(Files.size(backup)>0);try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+backup);Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT json FROM sandbox_state")){assertTrue(rs.next());assertTrue(rs.getString(1).contains("payment"));}}
 @Test void corruptStateNotSilentlyReset() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db.file());Statement st=c.createStatement()){st.execute("INSERT INTO sandbox_state VALUES(1,'not json')");}assertThrows(Exception.class,()->db.load(Ledger.empty(now,zone)));}
 @Test void failedValidationDoesNotReplaceGoodState() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);db.save(sample(),"GOOD","good");State bad=sample();bad.openMonth="broken";assertThrows(Exception.class,()->db.save(bad,"BAD","bad"));assertEquals(2000,Ledger.view(db.load(Ledger.empty(now,zone))).profile(alice.uuid()).alltimeCents());}
 @Test void concurrentDuplicateEventsAreSerialized() throws Exception {try(SandboxEngine e=new SandboxEngine(new SandboxDatabase(directory),now,zone)){List<CompletableFuture<Boolean>> futures=new ArrayList<>();for(int i=0;i<50;i++)futures.add(e.edit("EVENT",s -> Ledger.add(s,"one",alice,alice,Product.AVID,Kind.PURCHASE,2000,s.now,30)));CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);assertEquals(1,futures.stream().filter(CompletableFuture::join).count());assertEquals(2000,e.view().profile(alice.uuid()).alltimeCents());}}
 @Test void injectedFailureLeavesSnapshotUnchanged() throws Exception {try(SandboxEngine e=new SandboxEngine(new SandboxDatabase(directory),now,zone)){e.injectDatabaseFailure();assertThrows(ExecutionException.class,()->e.edit("FAIL",s -> Ledger.add(s,"x",alice,alice,Product.AVID,Kind.PURCHASE,2000,s.now,30)).get());assertTrue(e.view().payments().isEmpty());assertEquals(0,new SandboxDatabase(directory).load(Ledger.empty(now,zone)).payments.size());}}
 @Test void resetBacksUpAndRetainsDispatchReceipts() throws Exception {SandboxDatabase db=new SandboxDatabase(directory);try(SandboxEngine e=new SandboxEngine(db,now,zone)){e.edit("PAY",s -> Ledger.add(s,"x",alice,alice,Product.AVID,Kind.PURCHASE,2000,s.now,30)).get();db.claim("x","chat");e.reset(now).get();assertTrue(e.view().payments().isEmpty());assertFalse(db.claim("x","chat"));try(var backups=Files.list(directory.resolve("backups"))){assertEquals(1,backups.count());}}}
}
