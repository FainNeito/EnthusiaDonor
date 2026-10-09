package com.enthusia.donors.network;

public record SnapshotRead(Status status,NetworkSnapshot snapshot) {
    public enum Status {FOUND,NOT_FOUND,FAILED}
    public static SnapshotRead found(NetworkSnapshot snapshot) {return new SnapshotRead(Status.FOUND,snapshot);}
    public static SnapshotRead notFound() {return new SnapshotRead(Status.NOT_FOUND,null);}
    public static SnapshotRead failed() {return new SnapshotRead(Status.FAILED,null);}
}
