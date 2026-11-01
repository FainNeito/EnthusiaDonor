package com.enthusia.donors.glorious;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Shared journal. Callers must provision its schema; every mutation is fenced by the publisher lease. */
public final class JdbcAwardJournal {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    public enum Dialect { MARIADB, SQLITE }
    private final Connections connections;
    private final Dialect dialect;
    private final String source;

    public JdbcAwardJournal(Connections connections, Dialect dialect, String source) {
        this.connections=Objects.requireNonNull(connections); this.dialect=Objects.requireNonNull(dialect);
        if (source==null || !source.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid source");
        this.source=source;
    }
    private long dbNow(Connection c) throws SQLException {
        String sql=dialect==Dialect.MARIADB ? "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)" : "SELECT CAST(strftime('%s','now') AS INTEGER)*1000";
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery(sql)) { if(!r.next()) throw new SQLException("Missing database time"); return r.getLong(1); }
    }
    private long lockLease(Connection c, String token, long epoch) throws SQLException {
        if (token==null || token.isBlank() || epoch<1) throw new SQLException("Invalid lease");
        if (dialect==Dialect.SQLITE) try (PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_test_projection SET revision=revision WHERE source_id=?")) {p.setString(1,source);p.executeUpdate();}
        String sql="SELECT owner_token,lease_until,lease_epoch FROM enthusiadonors_test_projection WHERE source_id=?"+(dialect==Dialect.MARIADB?" FOR UPDATE":"");
        try(PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,source);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next() || !token.equals(r.getString(1)) || r.getLong(3)!=epoch) throw new SQLException("Publisher lease lost");
                long now=dbNow(c); if(r.getLong(2)<=now) throw new SQLException("Publisher lease expired"); return now;
            }
        }
    }
    private String insertIgnore() { return dialect==Dialect.MARIADB?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO "; }
    @FunctionalInterface private interface Work<T> { T run(Connection c, long now) throws Exception; }
    private <T> T transaction(String token,long epoch,Work<T> work) throws SQLException {
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try { long now=lockLease(c,token,epoch); T result=work.run(c,now); c.commit(); return result; }
            catch(Exception error) { try {c.rollback();} catch(SQLException rollback) {error.addSuppressed(rollback);} throw error instanceof SQLException s?s:new SQLException("Award journal failed",error); }
        }
    }
    /** Initializes once using database time; a later restart cannot move activation backward. */
    public ActivationBoundary activate(String token,long epoch,ZoneId timezone) throws SQLException {
        Objects.requireNonNull(timezone);
        return transaction(token,epoch,(c,now)->{
            try(PreparedStatement p=c.prepareStatement(insertIgnore()+"enthusiadonors_glorious_control(source_id,activated_at,timezone) VALUES (?,?,?)")) {
                p.setString(1,source);p.setLong(2,now);p.setString(3,timezone.getId());p.executeUpdate();
            }
            try(PreparedStatement p=c.prepareStatement("SELECT activated_at,timezone FROM enthusiadonors_glorious_control WHERE source_id=?")) {
                p.setString(1,source);try(ResultSet r=p.executeQuery()) {
                    if(!r.next() || !timezone.getId().equals(r.getString(2))) throw new SQLException("Activation timezone changed");
                    return new ActivationBoundary(Instant.ofEpochMilli(r.getLong(1)),timezone);
                }
            }
        });
    }
    /** Persist one immutable winner/no-winner per eligible month; conflicts are held, never overwritten. */
    public AwardDecision decide(String token,long epoch,ZoneId timezone,AwardDecision decision) throws SQLException {
        Objects.requireNonNull(timezone);Objects.requireNonNull(decision);
        if(decision.state()!=AwardDecision.State.READY && decision.state()!=AwardDecision.State.NO_WINNER) throw new IllegalArgumentException("Invalid initial decision");
        return transaction(token,epoch,(c,now)->{
            long activated; String storedZone;
            try(PreparedStatement p=c.prepareStatement("SELECT activated_at,timezone FROM enthusiadonors_glorious_control WHERE source_id=?")) {
                p.setString(1,source);try(ResultSet r=p.executeQuery()) { if(!r.next())throw new SQLException("Activation missing"); activated=r.getLong(1);storedZone=r.getString(2); }
            }
            if(!timezone.getId().equals(storedZone) || !new ActivationBoundary(Instant.ofEpochMilli(activated),timezone).mayFinalize(decision.month(),Instant.ofEpochMilli(now)))
                throw new SQLException("Month is outside activation boundary");
            try(PreparedStatement p=c.prepareStatement(insertIgnore()+"enthusiadonors_glorious_awards(source_id,month_key,winner_uuid,state) VALUES (?,?,?,?)")) {
                p.setString(1,source);p.setString(2,decision.month().toString());p.setString(3,decision.winner()==null?null:decision.winner().toString());p.setString(4,decision.state().name());p.executeUpdate();
            }
            AwardDecision stored=read(c,decision.month());
            if(!Objects.equals(stored.winner(),decision.winner()))throw new SQLException("Conflicting immutable monthly winner");
            return stored;
        });
    }
    private AwardDecision read(Connection c,YearMonth month) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT winner_uuid,state FROM enthusiadonors_glorious_awards WHERE source_id=? AND month_key=?")) {
            p.setString(1,source);p.setString(2,month.toString());try(ResultSet r=p.executeQuery()) {
                if(!r.next())throw new SQLException("Award missing");String id=r.getString(1);
                try{return new AwardDecision(month,id==null?null:UUID.fromString(id),AwardDecision.State.valueOf(r.getString(2)));}
                catch(RuntimeException invalid){throw new SQLException("Invalid stored award",invalid);}
            }
        }
    }
    /** Look up a final or in-progress decision while verifying the publisher lease. */
    public Optional<AwardDecision> findDecision(String token,long epoch,YearMonth month) throws SQLException {
        Objects.requireNonNull(month);
        return transaction(token,epoch,(c,now)->{
            try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM enthusiadonors_glorious_awards WHERE source_id=? AND month_key=?")) {
                p.setString(1,source);p.setString(2,month.toString());try(ResultSet r=p.executeQuery()) {
                    return r.next()?Optional.of(read(c,month)):Optional.empty();
                }
            }
        });
    }
    /** Records an audited attestation; the caller must authenticate and authorize the reviewer. */
    public void recordPayerReview(String token,long epoch,YearMonth month,String paymentHash,
                                  UUID payer,UUID reviewer,String evidenceDigest) throws SQLException {
        Objects.requireNonNull(month); Objects.requireNonNull(payer); Objects.requireNonNull(reviewer);
        if(paymentHash==null || !paymentHash.matches("[0-9a-f]{64}") || evidenceDigest==null || !evidenceDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Review requires lowercase SHA-256 digests");
        transaction(token,epoch,(c,now)->{
            try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM enthusiadonors_glorious_awards WHERE source_id=? AND month_key=?")) {
                p.setString(1,source);p.setString(2,month.toString());try(ResultSet r=p.executeQuery()) {
                    if(r.next())throw new SQLException("Month already finalized");
                }
            }
            try(PreparedStatement p=c.prepareStatement("SELECT month_key,payer_uuid,reviewer_uuid,evidence_digest FROM enthusiadonors_glorious_payer_reviews WHERE source_id=? AND payment_hash=?")) {
                p.setString(1,source);p.setString(2,paymentHash);try(ResultSet r=p.executeQuery()) {
                    if(r.next()) {
                        if(!month.toString().equals(r.getString(1)) || !payer.toString().equals(r.getString(2))
                                || !reviewer.toString().equals(r.getString(3)) || !evidenceDigest.equals(r.getString(4)))
                            throw new SQLException("Conflicting payer review");
                        return null;
                    }
                }
            }
            try(PreparedStatement p=c.prepareStatement("INSERT INTO enthusiadonors_glorious_payer_reviews(source_id,month_key,payment_hash,payer_uuid,reviewer_uuid,evidence_digest,reviewed_at) VALUES (?,?,?,?,?,?,?)")) {
                p.setString(1,source);p.setString(2,month.toString());p.setString(3,paymentHash);p.setString(4,payer.toString());
                p.setString(5,reviewer.toString());p.setString(6,evidenceDigest);p.setLong(7,now);p.executeUpdate();
            }
            return null;
        });
    }
    /** Read only reviews for the target month under the same publisher lease. */
    public Map<String,UUID> reviewedPayers(String token,long epoch,YearMonth month) throws SQLException {
        Objects.requireNonNull(month);
        return transaction(token,epoch,(c,now)->{
            Map<String,UUID> values=new HashMap<>();
            try(PreparedStatement p=c.prepareStatement("SELECT payment_hash,payer_uuid FROM enthusiadonors_glorious_payer_reviews WHERE source_id=? AND month_key=?")) {
                p.setString(1,source);p.setString(2,month.toString());try(ResultSet r=p.executeQuery()) {
                    while(r.next()) {try {values.put(r.getString(1),UUID.fromString(r.getString(2)));}
                        catch(RuntimeException invalid) {throw new SQLException("Invalid stored payer review",invalid);}}
                }
            }
            return Map.copyOf(values);
        });
    }
    /** Record a human attestation that an interrupted or uncertain winner already has the rank. */
    public boolean confirmUncertain(String token,long epoch,YearMonth month,UUID winner,UUID reviewer,String evidenceDigest) throws SQLException {
        Objects.requireNonNull(month);Objects.requireNonNull(winner);Objects.requireNonNull(reviewer);
        if(evidenceDigest==null || !evidenceDigest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Recovery requires a lowercase SHA-256 evidence digest");
        return transaction(token,epoch,(c,now)->{
            AwardDecision decision=read(c,month);
            if(!winner.equals(decision.winner()))throw new SQLException("Recovery winner does not match decision");
            if(decision.state()!=AwardDecision.State.UNCERTAIN && decision.state()!=AwardDecision.State.GRANTING)throw new SQLException("Only an interrupted or uncertain award can be confirmed");
            try(PreparedStatement p=c.prepareStatement("INSERT INTO enthusiadonors_glorious_recoveries(source_id,month_key,winner_uuid,reviewer_uuid,evidence_digest,resolved_at) VALUES (?,?,?,?,?,?)")) {
                p.setString(1,source);p.setString(2,month.toString());p.setString(3,winner.toString());p.setString(4,reviewer.toString());p.setString(5,evidenceDigest);p.setLong(6,now);p.executeUpdate();
            }
            try(PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_glorious_awards SET state=? WHERE source_id=? AND month_key=? AND state=? AND winner_uuid=?")) {
                p.setString(1,AwardDecision.State.AWARDED.name());p.setString(2,source);p.setString(3,month.toString());p.setString(4,decision.state().name());p.setString(5,winner.toString());
                if(p.executeUpdate()!=1)throw new SQLException("Uncertain award changed during recovery");
            }
            return true;
        });
    }
    /** Atomic transitions guard against duplicate grants and interrupted prior attempts. */
    public boolean transition(String token,long epoch,YearMonth month,AwardDecision.State expected,AwardDecision.State next) throws SQLException {
        Objects.requireNonNull(month);
        if(!((expected==AwardDecision.State.READY && next==AwardDecision.State.GRANTING)
                || (expected==AwardDecision.State.GRANTING && (next==AwardDecision.State.AWARDED || next==AwardDecision.State.UNCERTAIN || next==AwardDecision.State.READY))))
            throw new IllegalArgumentException("Invalid state transition");
        return transaction(token,epoch,(c,now)->{
            try(PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_glorious_awards SET state=? WHERE source_id=? AND month_key=? AND state=?")) {
                p.setString(1,next.name());p.setString(2,source);p.setString(3,month.toString());p.setString(4,expected.name());return p.executeUpdate()==1;
            }
        });
    }

    /** Hold the publisher row lock across the external grant while the database connection survives.
     * A disconnected transaction cannot fence an already-started external LuckPerms write. */
    public AwardCoordinator.GrantResult guardedGrant(String token,long epoch,UUID player,
            java.util.function.BiFunction<UUID,java.util.function.BooleanSupplier,AwardCoordinator.GrantResult> grant) throws SQLException {
        return transaction(token,epoch,(connection,now) -> grant.apply(player,() -> {
            try { lockLease(connection,token,epoch); return true; }
            catch(SQLException lost) { return false; }
        }));
    }
}
