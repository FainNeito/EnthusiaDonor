package com.enthusia.donors.velocity;

import com.enthusia.donors.relay.*;
import com.google.inject.Inject;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.slf4j.Logger;
import java.nio.file.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Database polling only; no incoming plugin-message handler or originating-player requirement. */
public final class VelocityRelay {
    private final ProxyServer proxy;private final Logger logger;private final Path directory;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"EnthusiaDonors-velocity-relay");t.setDaemon(true);return t;});
    private volatile String status="DISABLED";
    private volatile boolean closed;
    private RelayStore store;private RelayDispatcher dispatcher;private boolean initialized;
    private String sourceId,proxyId;
    private long lastWarning;
    @Inject public VelocityRelay(ProxyServer proxy,Logger logger,@DataDirectory Path directory) {this.proxy=proxy;this.logger=logger;this.directory=directory;}
    @Subscribe public void start(ProxyInitializeEvent ignored) {
        proxy.getCommandManager().register("donorrelay",new SimpleCommand() {
            public void execute(Invocation invocation) {
                String[] args=invocation.arguments();
                if(args.length==2 && args[0].equalsIgnoreCase("receipt") && args[1].matches("[a-z0-9_.:-]{1,128}") && store!=null) {
                    worker.execute(() -> {
                        try {invocation.source().sendMessage(Component.text("Relay receipt "+args[1]+": "+store.receipt(sourceId,args[1],proxyId)+" (PENDING means no receipt; it may also be absent or expired)."));}
                        catch(Exception failure) {invocation.source().sendMessage(Component.text("Relay receipt unavailable; inspect database access."));}
                    });
                } else invocation.source().sendMessage(Component.text("EnthusiaDonors relay: "+status+" | /donorrelay receipt <event-id>"));
            }
            public boolean hasPermission(Invocation invocation) {return invocation.source().hasPermission("enthusiadonors.relay.admin");}
        });
        try {
            Files.createDirectories(directory);Path file=directory.resolve("relay.properties");
            if(!Files.exists(file)) {
                Properties defaults=new Properties();defaults.setProperty("enabled","false");defaults.setProperty("source-id","enthusia-donors-test");defaults.setProperty("proxy-id","velocity-test-1");
                defaults.setProperty("jdbc-url","");defaults.setProperty("username","");defaults.setProperty("password","");defaults.setProperty("signing-key","");defaults.setProperty("poll-seconds","2");defaults.setProperty("initialize-schema","false");
                try(Writer out=Files.newBufferedWriter(file)) {defaults.store(out,"Dedicated test relay. Set credentials/key locally and restart. Never use the donor Tebex key here.");}
            }
            Properties settings=new Properties();try(Reader in=Files.newBufferedReader(file)) {settings.load(in);}
            if(!Boolean.parseBoolean(settings.getProperty("enabled","false"))) {logger.info("EnthusiaDonors relay disabled; edit relay.properties and restart to test.");return;}
            String url=settings.getProperty("jdbc-url",""),source=settings.getProperty("source-id",""),id=settings.getProperty("proxy-id",""),key=settings.getProperty("signing-key","");
            int interval=Integer.parseInt(settings.getProperty("poll-seconds","2"));
            if(!url.startsWith("jdbc:mariadb://") || interval<1 || interval>60)throw new IllegalArgumentException("Invalid relay database or poll interval");
            Class.forName("org.mariadb.jdbc.Driver");
            Properties credentials=new Properties();credentials.setProperty("user",settings.getProperty("username",""));credentials.setProperty("password",settings.getProperty("password",""));credentials.setProperty("connectTimeout","5000");credentials.setProperty("socketTimeout","5000");
            store=new RelayStore(() -> DriverManager.getConnection(url,credentials),RelayStore.Dialect.MARIADB);
            dispatcher=new RelayDispatcher(store,source,id,key);
            sourceId=source;proxyId=id;
            boolean initialize=Boolean.parseBoolean(settings.getProperty("initialize-schema","false"));
            status="STARTING";worker.scheduleWithFixedDelay(() -> poll(initialize),0,interval,TimeUnit.SECONDS);
        } catch(Exception invalid) {status="CONFIGURATION_ERROR";logger.error("EnthusiaDonors relay configuration is invalid; credentials were not logged. Inspect relay.properties.");}
    }
    private void poll(boolean initialize) {
        if(closed)return;
        try {
            if(initialize && !initialized) {store.init();initialized=true;}
            List<UUID> audience=proxy.getAllPlayers().stream().map(p -> p.getUniqueId()).toList();
            int delivered=dispatcher.poll(audience,(event,recipients) -> {
                List<Component> lines=event.lines().stream().map(GsonComponentSerializer.gson()::deserialize).toList();
                return () -> {
                    if(closed)throw new IllegalStateException("Relay stopping");
                    for(UUID player:recipients)proxy.getPlayer(player).ifPresent(p -> lines.forEach(p::sendMessage));
                };
            });
            status=audience.isEmpty()?"IDLE | no connected players":"OK | last poll delivered "+delivered+" | players "+audience.size();
        } catch(Exception failure) {
            status="DATABASE_OR_DELIVERY_ERROR";
            long now=System.currentTimeMillis();if(now-lastWarning>60_000) {lastWarning=now;logger.warn("EnthusiaDonors relay poll failed; queued events/claims preserved. Inspect database access and receipts.");}
        }
    }
    @Subscribe public void stop(ProxyShutdownEvent ignored) {closed=true;worker.shutdownNow();status="STOPPED";}
}
