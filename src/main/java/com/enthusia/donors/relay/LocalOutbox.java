package com.enthusia.donors.relay;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** One connection per operation; local SQLite WAL outbox survives backend restarts. */
public final class LocalOutbox {
    private final Path file;
    public LocalOutbox(Path file) {this.file=file.toAbsolutePath();}
    private Connection connect() throws SQLException {return DriverManager.getConnection("jdbc:sqlite:"+file);}
    public void init() throws Exception {
        Files.createDirectories(file.getParent());
        try(Connection c=connect();Statement s=c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS relay_outbox(event_id TEXT PRIMARY KEY,payload TEXT NOT NULL,state TEXT NOT NULL)");
        }
    }
    public void put(RelayEvent event) throws SQLException {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO relay_outbox(event_id,payload,state) VALUES (?,?,'PENDING')")) {p.setString(1,event.id());p.setString(2,event.json());p.executeUpdate();}
                try(PreparedStatement p=c.prepareStatement("SELECT payload FROM relay_outbox WHERE event_id=?")) {
                    p.setString(1,event.id());try(ResultSet r=p.executeQuery()) {if(!r.next() || !event.equals(RelayEvent.parse(r.getString(1))))throw new SQLException("Local event identity conflict");}
                }
                c.commit();
            } catch(Exception error) {c.rollback();throw error instanceof SQLException s?s:new SQLException("Local relay save failed",error);}
        }
    }
    public List<RelayEvent> pending() throws SQLException {
        List<RelayEvent> result=new ArrayList<>();
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT payload FROM relay_outbox WHERE state='PENDING' ORDER BY rowid LIMIT 100")) {
            while(r.next())result.add(RelayEvent.parse(r.getString(1)));
        }
        return List.copyOf(result);
    }
    public void flush(RelayStore remote) throws SQLException {
        for(RelayEvent event:pending()) {
            boolean expired=event.expiresAt()<=System.currentTimeMillis();
            String state=expired?"EXPIRED":"PUBLISHED";
            if(!expired)try {remote.publish(event);}catch(SQLIntegrityConstraintViolationException conflict) {state="CONFLICT";}
            try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE relay_outbox SET state=? WHERE event_id=?")) {p.setString(1,state);p.setString(2,event.id());p.executeUpdate();}
        }
    }
}
