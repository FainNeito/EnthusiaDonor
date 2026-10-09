package com.enthusia.donors.network;

import java.sql.*;

/** Fixed test table; readers invoke read only. Transactions fence competing publisher boot tokens. */
public final class JdbcProjectionStore {
    public enum Dialect {MARIADB,SQLITE}
    @FunctionalInterface public interface Connections {Connection open() throws SQLException;}
    private final Connections connections;private final String source;private final Dialect dialect;
    public JdbcProjectionStore(Connections connections,String source,Dialect dialect) {
        if(source==null || !source.matches("[a-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Invalid source ID");
        this.connections=connections;this.source=source;this.dialect=dialect;
    }
    public void initPublisher() throws SQLException {
        try(Connection c=connections.open();Statement s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS enthusiadonors_test_projection (source_id VARCHAR(64) PRIMARY KEY,revision BIGINT NOT NULL,owner_token VARCHAR(64),lease_until BIGINT NOT NULL,lease_epoch BIGINT NOT NULL,payload "+(dialect==Dialect.MARIADB?"MEDIUMTEXT":"TEXT")+")"+(dialect==Dialect.MARIADB?" ENGINE=InnoDB":""));
            String insert=dialect==Dialect.MARIADB?"INSERT IGNORE INTO":"INSERT OR IGNORE INTO";
            try(PreparedStatement p=c.prepareStatement(insert+" enthusiadonors_test_projection(source_id,revision,owner_token,lease_until,lease_epoch,payload) VALUES (?,0,NULL,0,0,NULL)")) {
                p.setString(1,source);p.executeUpdate();
            }
        }
    }
    private long databaseTime(Connection c) throws SQLException {
        String sql=dialect==Dialect.MARIADB?"SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)":"SELECT CAST(strftime('%s','now') AS INTEGER)*1000";
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)) {r.next();return r.getLong(1);}
    }
    private Head lock(Connection c) throws SQLException {
        if(dialect==Dialect.SQLITE)try(PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_test_projection SET revision=revision WHERE source_id=?")) {p.setString(1,source);p.executeUpdate();}
        try(PreparedStatement p=c.prepareStatement("SELECT revision,owner_token,lease_until,lease_epoch FROM enthusiadonors_test_projection WHERE source_id=?"+(dialect==Dialect.MARIADB?" FOR UPDATE":""))) {
            p.setString(1,source);try(ResultSet r=p.executeQuery()) {
                if(!r.next())throw new SQLException("Shared source has not been initialized");
                return new Head(r.getLong(1),r.getString(2),r.getLong(3),r.getLong(4));
            }
        }
    }
    public boolean acquireLease(String token,long durationMillis) throws SQLException {
        if(token==null || token.isBlank() || token.length()>64 || durationMillis<1000)throw new IllegalArgumentException("Invalid publisher lease");
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try {
                Head head=lock(c);long now=databaseTime(c);
                if(head.until()>now && !token.equals(head.owner())) {c.rollback();return false;}
                long epoch=head.until()>now && token.equals(head.owner())?head.epoch():Math.addExact(head.epoch(),1);
                try(PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_test_projection SET owner_token=?,lease_until=?,lease_epoch=? WHERE source_id=?")) {
                    p.setString(1,token);p.setLong(2,Math.addExact(now,durationMillis));p.setLong(3,epoch);p.setString(4,source);p.executeUpdate();
                }
                c.commit();return true;
            } catch(Exception error) {c.rollback();throw sql(error);}
        }
    }
    public NetworkSnapshot publish(String token,NetworkSnapshot draft) throws SQLException {
        return publish(token,leaseEpoch(token),draft);
    }
    public long leaseEpoch(String token) throws SQLException {
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT lease_epoch,owner_token,lease_until FROM enthusiadonors_test_projection WHERE source_id=?")) {
            p.setString(1,source);try(ResultSet r=p.executeQuery()) {
                if(!r.next() || !token.equals(r.getString(2)) || r.getLong(3)<=databaseTime(c))return 0;
                return r.getLong(1);
            }
        }
    }
    public NetworkSnapshot publish(String token,long expectedLeaseEpoch,NetworkSnapshot draft) throws SQLException {
        if(!source.equals(draft.sourceId()))throw new IllegalArgumentException("Wrong network source");
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try {
                Head head=lock(c);
                if(!token.equals(head.owner()) || head.until()<=databaseTime(c) || head.epoch()!=expectedLeaseEpoch)throw new SQLException("Publisher lease lost");
                NetworkSnapshot committed=draft.withRevision(Math.addExact(head.revision(),1));
                String json=committed.json();if(json.length()>8_000_000)throw new SQLException("Shared projection too large");
                try(PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_test_projection SET revision=?,payload=? WHERE source_id=?")) {
                    p.setLong(1,committed.revision());p.setString(2,json);p.setString(3,source);p.executeUpdate();
                }
                c.commit();return committed;
            } catch(Exception error) {c.rollback();throw sql(error);}
        }
    }
    public SnapshotRead read() {
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT revision,payload FROM enthusiadonors_test_projection WHERE source_id=?")) {
            p.setString(1,source);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next() || r.getString(2)==null)return SnapshotRead.notFound();
                NetworkSnapshot snapshot=NetworkSnapshot.parse(r.getString(2));
                if(!source.equals(snapshot.sourceId()) || snapshot.revision()!=r.getLong(1) || snapshot.revision()<1)return SnapshotRead.failed();
                return SnapshotRead.found(snapshot);
            }
        } catch(Exception error) {return SnapshotRead.failed();}
    }
    private static SQLException sql(Exception error) {return error instanceof SQLException s?s:new SQLException("Shared projection operation failed",error);}
    private record Head(long revision,String owner,long until,long epoch) { }
}
