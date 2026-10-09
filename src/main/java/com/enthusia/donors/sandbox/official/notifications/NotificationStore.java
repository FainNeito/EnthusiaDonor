package com.enthusia.donors.sandbox.official.notifications;

import com.enthusia.donors.model.PaymentRecord;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

/** Shared observation and channel outbox: one transaction per complete official snapshot. */
public final class NotificationStore {
    public enum Dialect {MARIADB,SQLITE}
    @FunctionalInterface public interface Connections {Connection open() throws SQLException;}
    public record Payload(String chat,String discord) { }
    public record Job(String source,String payment,String channel,String payload,long expiresAt) { }
    private final Connections connections;private final Dialect dialect;private final String source;
    public NotificationStore(Connections connections,Dialect dialect,String source) {this.connections=connections;this.dialect=dialect;this.source=source;}
    private String insert() {return dialect==Dialect.SQLITE?"INSERT OR IGNORE":"INSERT IGNORE";}
    private Connection connect() throws SQLException {
        Connection c=connections.open();
        if(dialect==Dialect.SQLITE)try(Statement s=c.createStatement()) {s.execute("PRAGMA busy_timeout=5000");}
        return c;
    }
    public void init() throws SQLException {
        try(Connection c=connect();Statement s=c.createStatement()) {
            String engine=dialect==Dialect.MARIADB?" ENGINE=InnoDB":"";
            s.executeUpdate("CREATE TABLE IF NOT EXISTS enthusiadonors_notify_sources(source_id VARCHAR(64) PRIMARY KEY,baseline BIGINT NOT NULL)"+engine);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS enthusiadonors_notify_seen(source_id VARCHAR(64) NOT NULL,payment_hash VARCHAR(128) NOT NULL,terminal INTEGER NOT NULL,PRIMARY KEY(source_id,payment_hash))"+engine);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS enthusiadonors_notify_jobs(source_id VARCHAR(64) NOT NULL,payment_hash VARCHAR(128) NOT NULL,channel VARCHAR(16) NOT NULL,payload TEXT NOT NULL,state VARCHAR(16) NOT NULL,expires_at BIGINT NOT NULL,PRIMARY KEY(source_id,payment_hash,channel))".replace("payload TEXT","payload "+(dialect==Dialect.MARIADB?"MEDIUMTEXT":"TEXT"))+engine);
        }
        register();
    }
    public void register() throws SQLException {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(insert()+" INTO enthusiadonors_notify_sources(source_id,baseline) VALUES (?,0)")) {p.setString(1,source);p.executeUpdate();}
    }
    public int observe(List<PaymentRecord> payments,Instant now,Predicate<PaymentRecord> eligible,Function<PaymentRecord,Payload> renderer) throws SQLException {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                // Serialize initialization and observations across publishers; acquire SQLite write lock before reading.
                if(dialect==Dialect.SQLITE)try(PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_notify_sources SET baseline=baseline WHERE source_id=?")) {p.setString(1,source);p.executeUpdate();}
                long baseline;
                try(PreparedStatement p=c.prepareStatement("SELECT baseline FROM enthusiadonors_notify_sources WHERE source_id=?"+(dialect==Dialect.MARIADB?" FOR UPDATE":""))) {
                    p.setString(1,source);try(ResultSet r=p.executeQuery()) {if(!r.next())throw new SQLException("Notification source is not provisioned");baseline=r.getLong(1);}
                }
                boolean first=baseline==0;int queued=0;
                Map<String,Boolean> observed=observed(c);
                for(PaymentRecord payment:payments) {
                    if(payment.createdAt().isAfter(now))continue;
                    boolean known=observed.containsKey(payment.paymentIdHash()),terminal=Boolean.TRUE.equals(observed.get(payment.paymentIdHash()));
                    if(terminal)continue;
                    String status=payment.status()==null?"":payment.status().trim().toLowerCase(Locale.ROOT);
                    boolean completed=Set.of("complete","completed","successful","success","paid").contains(status);
                    // The legacy API parser also flags pending/failed statuses as invalid. Only a
                    // reversal status (or a completed record with its reversal flag) is terminal here.
                    boolean reversed=Set.of("refund","refunded","chargeback").contains(status) || completed && payment.refundedOrChargeback();
                    boolean old=payment.createdAt().toEpochMilli()<baseline;
                    boolean consume=first || old || completed || reversed;
                    if(!first && !old && completed && !reversed && eligible.test(payment)) {
                        Payload payload=renderer.apply(payment);
                        if(payload.chat()!=null) {job(c,payment,"chat",payload.chat(),now.plusSeconds(900).toEpochMilli());queued++;}
                        if(payload.discord()!=null) {job(c,payment,"discord",payload.discord(),now.plusSeconds(86400).toEpochMilli());queued++;}
                    }
                    if(known)try(PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_notify_seen SET terminal=? WHERE source_id=? AND payment_hash=?")) {p.setInt(1,consume?1:0);p.setString(2,source);p.setString(3,payment.paymentIdHash());p.executeUpdate();}
                    else try(PreparedStatement p=c.prepareStatement("INSERT INTO enthusiadonors_notify_seen(source_id,payment_hash,terminal) VALUES (?,?,?)")) {p.setString(1,source);p.setString(2,payment.paymentIdHash());p.setInt(3,consume?1:0);p.executeUpdate();}
                    observed.put(payment.paymentIdHash(),consume);
                }
                if(first)try(PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_notify_sources SET baseline=? WHERE source_id=?")) {p.setLong(1,now.toEpochMilli());p.setString(2,source);p.executeUpdate();}
                c.commit();return queued;
            } catch(Exception failure) {c.rollback();throw failure instanceof SQLException sql?sql:new SQLException("Notification observation rolled back",failure);}
        }
    }
    private Map<String,Boolean> observed(Connection c) throws SQLException {
        Map<String,Boolean> values=new HashMap<>();
        try(PreparedStatement p=c.prepareStatement("SELECT payment_hash,terminal FROM enthusiadonors_notify_seen WHERE source_id=?")) {
            p.setString(1,source);try(ResultSet r=p.executeQuery()) {while(r.next())values.put(r.getString(1),r.getInt(2)!=0);}
        }
        return values;
    }
    /** Pre-render only unseen current purchases, before entering the observation transaction. */
    public List<PaymentRecord> candidates(List<PaymentRecord> payments,Instant now,Predicate<PaymentRecord> eligible) throws SQLException {
        try(Connection c=connect()) {
            long baseline;
            try(PreparedStatement p=c.prepareStatement("SELECT baseline FROM enthusiadonors_notify_sources WHERE source_id=?")) {
                p.setString(1,source);try(ResultSet r=p.executeQuery()) {if(!r.next())throw new SQLException("Notification source is not provisioned");baseline=r.getLong(1);}
            }
            if(baseline==0)return List.of();
            Map<String,Boolean> known=observed(c);
            return payments.stream().filter(p->p.createdAt().toEpochMilli()>=baseline && !p.createdAt().isAfter(now) && !Boolean.TRUE.equals(known.get(p.paymentIdHash())) && eligible.test(p)).toList();
        }
    }
    private void job(Connection c,PaymentRecord payment,String channel,String payload,long expiry) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT INTO enthusiadonors_notify_jobs(source_id,payment_hash,channel,payload,state,expires_at) VALUES (?,?,?,?,'PENDING',?)")) {p.setString(1,source);p.setString(2,payment.paymentIdHash());p.setString(3,channel);p.setString(4,payload);p.setLong(5,expiry);p.executeUpdate();}
    }
    public List<Job> pending() throws SQLException {
        List<Job> jobs=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT payment_hash,channel,payload,expires_at FROM enthusiadonors_notify_jobs WHERE source_id=? AND channel=? AND state='PENDING' ORDER BY expires_at,payment_hash LIMIT 50")) {
            for(String channel:List.of("chat","discord")) {
                p.setString(1,source);p.setString(2,channel);try(ResultSet r=p.executeQuery()) {while(r.next())jobs.add(new Job(source,r.getString(1),r.getString(2),r.getString(3),r.getLong(4)));}
            }
        }
        return List.copyOf(jobs);
    }
    public boolean claim(Job job) throws SQLException {return update(job,"CLAIMED","PENDING")==1;}
    public void finish(Job job,String state) throws SQLException {
        if(!Set.of("PUBLISHED","SENT","FAILED","UNCERTAIN","EXPIRED").contains(state))throw new IllegalArgumentException("Invalid delivery state");
        update(job,state,job.channel().equals("chat")?"PENDING":"CLAIMED");
    }
    private int update(Job job,String next,String previous) throws SQLException {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_notify_jobs SET state=? WHERE source_id=? AND payment_hash=? AND channel=? AND state=? AND payload=?")) {
            p.setString(1,next);p.setString(2,source);p.setString(3,job.payment());p.setString(4,job.channel());p.setString(5,previous);p.setString(6,job.payload());return p.executeUpdate();
        }
    }
    public String status() throws SQLException {
        List<String> states=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT state,COUNT(*) FROM enthusiadonors_notify_jobs WHERE source_id=? GROUP BY state")) {p.setString(1,source);try(ResultSet r=p.executeQuery()) {while(r.next())states.add(r.getString(1)+"="+r.getLong(2));}}
        return String.join(", ",states);
    }
}
