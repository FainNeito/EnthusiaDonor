import com.enthusia.donors.glorious.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Disposable loopback fixture; never points at a real donor database. */
public final class MariaDbLeaseLossProbe {
    static String url;
    static Connection open() throws SQLException {return DriverManager.getConnection(url,"root","");}
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("barrier timeout");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(args[0]);
        url="jdbc:mariadb://127.0.0.1:"+port+"/mysql?connectTimeout=3000&socketTimeout=5000";
        try(Connection c=open();Statement s=c.createStatement()) {
            s.execute("CREATE DATABASE lease_fault_fixture");s.execute("USE lease_fault_fixture");
            s.execute("CREATE TABLE enthusiadonors_test_projection(source_id VARCHAR(64) PRIMARY KEY,revision BIGINT NOT NULL,owner_token VARCHAR(64),lease_until BIGINT NOT NULL,lease_epoch BIGINT NOT NULL) ENGINE=InnoDB");
            s.execute("CREATE TABLE enthusiadonors_glorious_control(source_id VARCHAR(64) PRIMARY KEY,activated_at BIGINT NOT NULL,timezone VARCHAR(80) NOT NULL) ENGINE=InnoDB");
            s.execute("CREATE TABLE enthusiadonors_glorious_awards(source_id VARCHAR(64) NOT NULL,month_key CHAR(7) NOT NULL,winner_uuid CHAR(36),state VARCHAR(16) NOT NULL,PRIMARY KEY(source_id,month_key)) ENGINE=InnoDB");
            s.execute("INSERT INTO enthusiadonors_test_projection VALUES('fixture',1,'owner',CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)+120000,1)");
        }
        url=url.replace("/mysql?","/lease_fault_fixture?");
        AtomicLong connId=new AtomicLong();
        JdbcAwardJournal journal=new JdbcAwardJournal(()->{Connection c=open();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT CONNECTION_ID()")){r.next();connId.set(r.getLong(1));}return c;},JdbcAwardJournal.Dialect.MARIADB,"fixture");
        var pool=Executors.newSingleThreadExecutor();
        try {
            var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var external=new AtomicInteger();
            final var enteredFirst=entered;final var resumeFirst=resume;
            Future<?> call=pool.submit(()->{try {journal.guardedGrant("owner",1,UUID.randomUUID(),(id,valid)->{enteredFirst.countDown();await(resumeFirst);if(!valid.getAsBoolean())return AwardCoordinator.GrantResult.HELD;external.incrementAndGet();return AwardCoordinator.GrantResult.SAVED;});} catch(SQLException expected) { }});
            check(entered.await(10,TimeUnit.SECONDS),"guard did not enter");long killed=connId.get();
            try(Connection c=open();Statement s=c.createStatement()) {s.execute("KILL CONNECTION "+killed);s.executeUpdate("UPDATE enthusiadonors_test_projection SET owner_token='new',lease_epoch=2 WHERE source_id='fixture'");}
            resume.countDown();call.get(15,TimeUnit.SECONDS);check(external.get()==0,"lost lease changed external rank before recheck");
            System.out.println("PASS: disconnected lease is rejected before external write after lookup");

            try(Connection c=open();Statement s=c.createStatement()) {s.executeUpdate("UPDATE enthusiadonors_test_projection SET owner_token='owner',lease_epoch=3 WHERE source_id='fixture'");}
            entered=new CountDownLatch(1);resume=new CountDownLatch(1);final var checked=entered;final var continueWrite=resume;
            Future<?> ambiguous=pool.submit(()->{try {journal.guardedGrant("owner",3,UUID.randomUUID(),(id,valid)->{check(valid.getAsBoolean(),"lease check failed");checked.countDown();await(continueWrite);external.incrementAndGet();return AwardCoordinator.GrantResult.SAVED;});throw new AssertionError("lost DB transaction unexpectedly committed");}catch(SQLException expected) { }});
            check(entered.await(10,TimeUnit.SECONDS),"prewrite barrier timeout");killed=connId.get();
            try(Connection c=open();Statement s=c.createStatement()) {s.execute("KILL CONNECTION "+killed);s.executeUpdate("UPDATE enthusiadonors_test_projection SET owner_token='new',lease_epoch=4 WHERE source_id='fixture'");}
            resume.countDown();ambiguous.get(15,TimeUnit.SECONDS);check(external.get()==1,"fixture failed to demonstrate delayed external write");
            System.out.println("OBSERVED: external write can complete after database disconnect and takeover; DB failure is reported, so it cannot be claimed atomic");
        } finally {pool.shutdownNow();}
    }
}
