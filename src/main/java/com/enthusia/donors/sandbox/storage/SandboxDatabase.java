package com.enthusia.donors.sandbox.storage;

import com.enthusia.donors.sandbox.domain.Domain.State;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** A dedicated SQLite file. It never opens the upstream donors.db or player-stat database. */
public final class SandboxDatabase {
    private final Path file;
    private final Gson gson=new GsonBuilder().disableHtmlEscaping().create();
    public SandboxDatabase(Path directory) throws Exception {
        Files.createDirectories(directory);
        file=directory.resolve("donors-test.db").toAbsolutePath();
        Class.forName("org.sqlite.JDBC");
        try(Connection c=open(); Statement st=c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS sandbox_state (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS sandbox_effects (event_id TEXT NOT NULL, channel TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(event_id,channel))");
            st.execute("CREATE TABLE IF NOT EXISTS sandbox_audit (seq INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL)");
            st.execute("UPDATE sandbox_effects SET state='UNCERTAIN',detail='Restart after dispatch claim; not automatically resent' WHERE state='CLAIMED'");
        }
    }
    private Connection open() throws SQLException {
        Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(Statement st=c.createStatement()) { st.execute("PRAGMA busy_timeout=5000"); st.execute("PRAGMA synchronous=FULL"); }
        return c;
    }
    public synchronized State load(State initial) throws Exception {
        try(Connection c=open(); Statement st=c.createStatement(); ResultSet rs=st.executeQuery("SELECT json FROM sandbox_state WHERE id=1")) {
            if(rs.next()) { State state=gson.fromJson(rs.getString(1),State.class); state.validate(); return state; }
        }
        save(initial,"START","Created isolated test data"); return initial;
    }
    public synchronized void save(State state,String action,String detail) throws Exception {
        state.validate(); String json=gson.toJson(state);
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("INSERT INTO sandbox_state(id,json) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET json=excluded.json")) {
                p.setString(1,json); p.executeUpdate(); audit(c,action,detail); c.commit();
            } catch(Exception ex) { c.rollback(); throw ex; }
        }
    }
    public synchronized void audit(String action,String detail) throws SQLException {
        try(Connection c=open()) { audit(c,action,detail); }
    }
    private void audit(Connection c,String action,String detail) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT INTO sandbox_audit(at,action,detail) VALUES(?,?,?)")) {
            p.setLong(1,System.currentTimeMillis()); p.setString(2,action); p.setString(3,detail); p.executeUpdate();
        }
    }
    /** Claim before a side effect. At-most-once attempts, not an impossible exactly-once network guarantee. */
    public synchronized boolean claim(String eventId,String channel) throws SQLException {
        try(Connection c=open(); PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO sandbox_effects VALUES(?,?,'CLAIMED','',?)")) {
            p.setString(1,eventId); p.setString(2,channel); p.setLong(3,System.currentTimeMillis()); return p.executeUpdate()==1;
        }
    }
    public synchronized void finish(String eventId,String channel,String result,String detail) throws SQLException {
        try(Connection c=open(); PreparedStatement p=c.prepareStatement("UPDATE sandbox_effects SET state=?,detail=?,updated_at=? WHERE event_id=? AND channel=?")) {
            p.setString(1,result); p.setString(2,detail); p.setLong(3,System.currentTimeMillis()); p.setString(4,eventId); p.setString(5,channel); p.executeUpdate();
        }
        audit(channel+":"+result,eventId+" - "+detail);
    }
    public synchronized List<String> logs(int limit) throws SQLException {
        List<String> out=new ArrayList<>();
        try(Connection c=open(); PreparedStatement p=c.prepareStatement("SELECT at,action,detail FROM sandbox_audit ORDER BY seq DESC LIMIT ?")) {
            p.setInt(1,Math.max(1,Math.min(limit,100)));
            try(ResultSet rs=p.executeQuery()) { while(rs.next()) out.add(Instant.ofEpochMilli(rs.getLong(1))+" | "+rs.getString(2)+" | "+rs.getString(3)); }
        }
        return out;
    }
    public synchronized Path backup() throws Exception {
        Path backup=file.resolveSibling("backups").resolve("donors-test-"+System.currentTimeMillis()+".db");
        Files.createDirectories(backup.getParent());
        // All DB writes use this object's monitor; each operation closes its connection.
        return Files.copy(file,backup);
    }
    public Path file() { return file; }
}
