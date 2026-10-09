package com.enthusia.donors.relay;

import java.sql.*;
import java.util.*;

/** Dedicated test event/receipt tables. No payment or donor projection writes. */
public final class RelayStore {
    public enum Dialect {MARIADB,SQLITE}
    @FunctionalInterface public interface Connections {Connection open() throws SQLException;}
    private final Connections connections;private final Dialect dialect;
    public RelayStore(Connections connections,Dialect dialect) {this.connections=connections;this.dialect=dialect;}
    public void init() throws SQLException {
        try(Connection c=connections.open();Statement s=c.createStatement()) {
            String suffix=dialect==Dialect.MARIADB?" ENGINE=InnoDB":"";
            s.executeUpdate("CREATE TABLE IF NOT EXISTS enthusiadonors_test_events (source_id VARCHAR(64) NOT NULL,event_id VARCHAR(128) NOT NULL,created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,payload "+(dialect==Dialect.MARIADB?"MEDIUMTEXT":"TEXT")+" NOT NULL,PRIMARY KEY(source_id,event_id))"+suffix);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS enthusiadonors_test_receipts (source_id VARCHAR(64) NOT NULL,event_id VARCHAR(128) NOT NULL,proxy_id VARCHAR(64) NOT NULL,state VARCHAR(16) NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(source_id,event_id,proxy_id))"+suffix);
        }
    }
    private String insert() {return dialect==Dialect.MARIADB?"INSERT IGNORE":"INSERT OR IGNORE";}
    private long time(Connection c) throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(dialect==Dialect.MARIADB?"SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)":"SELECT CAST(strftime('%s','now') AS INTEGER)*1000")) {r.next();return r.getLong(1);}
    }
    public void publish(RelayEvent event) throws SQLException {
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement(insert()+" INTO enthusiadonors_test_events(source_id,event_id,created_at,expires_at,payload) VALUES (?,?,?,?,?)")) {
                    p.setString(1,event.source());p.setString(2,event.id());p.setLong(3,event.createdAt());p.setLong(4,event.expiresAt());p.setString(5,event.json());p.executeUpdate();
                }
                try(PreparedStatement p=c.prepareStatement("SELECT payload FROM enthusiadonors_test_events WHERE source_id=? AND event_id=?")) {
                    p.setString(1,event.source());p.setString(2,event.id());
                    try(ResultSet r=p.executeQuery()) {if(!r.next() || !event.equals(RelayEvent.parse(r.getString(1))))throw new SQLIntegrityConstraintViolationException("Relay event identity conflict");}
                }
                c.commit();
            } catch(Exception error) {c.rollback();throw sql(error);}
        }
    }
    public List<RelayEvent> pending(String source,String proxy,int limit) throws SQLException {
        List<RelayEvent> result=new ArrayList<>();
        List<String> invalidIds=new ArrayList<>();
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT e.event_id,e.payload FROM enthusiadonors_test_events e LEFT JOIN enthusiadonors_test_receipts r ON e.source_id=r.source_id AND e.event_id=r.event_id AND r.proxy_id=? WHERE e.source_id=? AND e.expires_at>? AND r.event_id IS NULL ORDER BY e.created_at,e.event_id LIMIT ?")) {
            p.setString(1,proxy);p.setString(2,source);p.setLong(3,time(c));p.setInt(4,Math.max(1,Math.min(limit,100)));
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    String id=r.getString(1);
                    try {
                        RelayEvent event=RelayEvent.parse(r.getString(2));
                        if(!event.source().equals(source) || !event.id().equals(id))throw new IllegalArgumentException("Wrong event identity");
                        result.add(event);
                    } catch(Exception invalid) {invalidIds.add(id);}
                }
            }
        }
        for(String id:invalidIds)reject(source,id,proxy);
        return List.copyOf(result);
    }
    public boolean claim(RelayEvent event,String proxy) throws SQLException {
        if(!RelayEvent.safeId(proxy))throw new IllegalArgumentException("Invalid proxy ID");
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement(insert()+" INTO enthusiadonors_test_receipts(source_id,event_id,proxy_id,state,updated_at) SELECT source_id,event_id,?,'CLAIMED',? FROM enthusiadonors_test_events WHERE source_id=? AND event_id=? AND expires_at>? AND payload=?")) {
            long now=time(c);p.setString(1,proxy);p.setLong(2,now);p.setString(3,event.source());p.setString(4,event.id());p.setLong(5,now);p.setString(6,event.json());return p.executeUpdate()==1;
        }
    }
    public void reject(String source,String id,String proxy) throws SQLException {
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement(insert()+" INTO enthusiadonors_test_receipts(source_id,event_id,proxy_id,state,updated_at) VALUES (?,?,?,'REJECTED',?)")) {
            p.setString(1,source);p.setString(2,id);p.setString(3,proxy);p.setLong(4,time(c));p.executeUpdate();
        }
    }
    public void finish(RelayEvent event,String proxy,boolean delivered) throws SQLException {
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("UPDATE enthusiadonors_test_receipts SET state=?,updated_at=? WHERE source_id=? AND event_id=? AND proxy_id=? AND state='CLAIMED'")) {
            p.setString(1,delivered?"DELIVERED":"UNCERTAIN");p.setLong(2,time(c));p.setString(3,event.source());p.setString(4,event.id());p.setString(5,proxy);
            if(p.executeUpdate()!=1)throw new SQLException("Relay claim not found");
        }
    }
    public String receipt(String source,String id,String proxy) throws SQLException {
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT state FROM enthusiadonors_test_receipts WHERE source_id=? AND event_id=? AND proxy_id=?")) {
            p.setString(1,source);p.setString(2,id);p.setString(3,proxy);try(ResultSet r=p.executeQuery()) {return r.next()?r.getString(1):"PENDING";}
        }
    }
    private static SQLException sql(Exception error) {return error instanceof SQLException s?s:new SQLException("Relay store operation failed",error);}
}
