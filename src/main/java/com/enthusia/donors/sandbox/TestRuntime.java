package com.enthusia.donors.sandbox;

import com.enthusia.donors.sandbox.api.DonorProfileService;
import com.enthusia.donors.sandbox.domain.*;
import com.enthusia.donors.sandbox.domain.Domain.*;
import com.enthusia.donors.sandbox.storage.SandboxDatabase;
import com.enthusia.donors.sandbox.skin.SkinService;
import com.enthusia.donors.sandbox.discord.TestDiscord;
import com.enthusia.donors.sandbox.ui.*;
import com.enthusia.donors.sandbox.ui.Broadcasts.Notice;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** First test release: production adapters are unreachable by construction, not a fragile config flag. */
@SuppressWarnings("deprecation")
public final class TestRuntime implements CommandExecutor,TabCompleter,Listener,AutoCloseable {
    public record Targets(Person buyer,Person recipient) { }
    private static final class Session {
        Targets targets; boolean serverAudience; boolean networkAudience; String lastPayment=""; long lastAction;
        Session(Person self){targets=new Targets(self,self);}
    }
    private record Attempt(boolean created,Payment payment) { }
    private final JavaPlugin plugin;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final ExecutorService io=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"EnthusiaDonors-test-dispatch");t.setDaemon(true);return t;});
    private volatile SandboxEngine engine;
    private volatile TestSettings settings;
    private SkinService skins;
    private TestDiscord discord;
    private Menus menus;
    private volatile boolean closed;
    public TestRuntime(JavaPlugin plugin) throws Exception {
        this.plugin=plugin; settings=TestSettings.load(plugin);
        skins=new SkinService(settings);menus=new Menus(this);
        for(String name:List.of("enthusiadonors","donors")) {
            PluginCommand command=Objects.requireNonNull(plugin.getCommand(name),"Missing command "+name);
            command.setExecutor(this);command.setTabCompleter(this);
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,ProxyChatBroadcast.CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(menus,plugin);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getLogger().warning("TEST BUILD: production Tebex, donor databases, R2, and LuckPerms writes are DISCONNECTED.");
        CompletableFuture.supplyAsync(() -> {
            try {return new SandboxEngine(new SandboxDatabase(plugin.getDataFolder().toPath().resolve("testing")),Instant.now(),settings.zone());}
            catch(Exception ex){throw new CompletionException(ex);}
        },io).whenComplete((loaded,error) -> {
            if(closed){if(loaded!=null)loaded.close();return;}
            main(() -> {
                if(error!=null){plugin.getLogger().severe("Sandbox database could not be loaded: "+root(error).getClass().getSimpleName()+". Existing data preserved.");plugin.getServer().getPluginManager().disablePlugin(plugin);return;}
                engine=loaded;discord=new TestDiscord(loaded.database());
                plugin.getServer().getServicesManager().register(DonorProfileService.class,new DonorProfileService(){
                    public boolean isTestEnvironment(){return true;}
                    public Optional<Profile> profile(UUID uuid){return Optional.ofNullable(engine.view().profile(uuid));}
                    public View snapshot(){return engine.view();}
                },plugin,ServicePriority.Normal);
                plugin.getLogger().info("Sandbox ready. Use /edonors test menu. Persisted test month: "+view().month());
            });
        });
    }
    public TestSettings settings(){return settings;}
    public SkinService skins(){return skins;}
    public SandboxEngine engine(){return engine;}
    public TestDiscord discord(){return discord;}
    public View view(){return engine.view();}
    public Person person(Player p){return new Person(p.getUniqueId(),p.getName(),false);}
    private Session session(Player p){return sessions.computeIfAbsent(p.getUniqueId(),id -> new Session(person(p)));}
    public Targets targets(Player p){return session(p).targets;}
    public void target(Player p,boolean buyer,Person person){Targets old=targets(p);session(p).targets=buyer?new Targets(person,old.recipient):new Targets(old.buyer,person);}
    public String audienceName(Player p){return session(p).networkAudience?"NETWORK":session(p).serverAudience?"BETA SERVER":"OPERATOR ONLY";}
    public void main(Runnable action){
        if(closed)return;
        if(Bukkit.isPrimaryThread())action.run();else mainNext(action);
    }
    public void mainNext(Runnable action){if(!closed&&plugin.isEnabled())try{Bukkit.getScheduler().runTask(plugin,() -> {if(!closed)action.run();});}catch(org.bukkit.plugin.IllegalPluginAccessException ignored){ }}
    public void reply(CommandSender sender,String message){main(() -> sender.sendMessage(Component.text("[EnthusiaDonors TEST] ",NamedTextColor.GOLD).append(Component.text(message,NamedTextColor.GRAY))));}
    private static Throwable root(Throwable error){Throwable e=error;while(e.getCause()!=null && (e instanceof CompletionException || e instanceof ExecutionException))e=e.getCause();return e;}
    private <T> void watch(CommandSender sender,CompletableFuture<T> future,Consumer<T> complete){
        future.whenComplete((result,error) -> main(() -> {
            if(error!=null){Throwable r=root(error);String detail=(r instanceof IllegalArgumentException || r instanceof IllegalStateException)?r.getMessage():r.getClass().getSimpleName();reply(sender,"Not completed: "+detail);}
            else complete.accept(result);
        }));
    }
    public void help(CommandSender sender){
        for(String line:List.of("/donors | /donors monthly | /donors alltime | /donors profile <name>",
                "/edonors test menu | target <buyer> <recipient> | status",
                "/edonors test purchase <player> <avid|devotee> [amount]",
                "/edonors test gift <buyer> <recipient> <avid|devotee> [amount]",
                "/edonors test renewal <player> devotee [amount] | cancel <player> | expire <player>",
                "/edonors test refund <player|event-id> | chargeback <player|event-id> | replay <last|event-id>",
                "/edonors test skin <player> | glorious <player> | preview <purchase|subscription|gift|renewal|glorious>",
                "/edonors test monthend | advance <days> | populate [count] | profile <player>",
                "/edonors test discord <type> | failure <skin|database|discord> | logs",
                "/edonors test luckperms | audience <self|server confirm|network confirm> | reset confirm"))reply(sender,line);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(engine==null){reply(sender,"Sandbox is still loading. No production systems are being started.");return true;}
        try {
            if(command.getName().equals("donors")){publicCommand(sender,args);return true;}
            if(!sender.hasPermission("enthusiadonors.test")){reply(sender,"You do not have access to test controls.");return true;}
            if(args.length==0){if(sender instanceof Player p)menus.testing(p);else help(sender);return true;}
            switch(args[0].toLowerCase(Locale.ROOT)){
                case "test" -> test(sender,Arrays.copyOfRange(args,1,args.length));
                case "reload" -> {TestSettings next=TestSettings.load(plugin);settings=next;skins.settings(next);if(!next.networkBroadcasts())sessions.values().forEach(s -> s.networkAudience=false);if(!next.serverBroadcasts())sessions.values().forEach(s -> s.serverAudience=false);reply(sender,"testing.yml reloaded. The persisted sandbox timezone remains "+view().zone()+" until a new sandbox database is created.");}
                case "status","refresh" -> status(sender);
                case "top" -> {List<Ranked> rows=args.length>1&&args[1].equalsIgnoreCase("monthly")?view().monthly():view().alltime();reply(sender,"TEST leaderboard (not live donations)");rows.stream().limit(10).forEach(r -> reply(sender,"#"+r.rank()+" "+r.person().name()+" - "+(settings.showAmounts()?Menus.money(r.cents()):"Hidden")));}
                default -> help(sender);
            }
        }catch(Exception ex){Throwable r=root(ex);reply(sender,"Not completed: "+(r.getMessage()==null?r.getClass().getSimpleName():r.getMessage()));}
        return true;
    }
    private void publicCommand(CommandSender sender,String[] args){
        if(!sender.hasPermission("enthusiadonors.view")){reply(sender,"You do not have permission to view donor profiles.");return;}
        if(!(sender instanceof Player p)){reply(sender,"Use /enthusiadonors top monthly from console.");return;}
        String sub=args.length==0?"monthly":args[0].toLowerCase(Locale.ROOT);
        switch(sub){
            case "monthly" -> menus.leaderboard(p,true,0);case "alltime" -> menus.leaderboard(p,false,0);
            case "me" -> menus.profile(p,p.getUniqueId());case "directory" -> menus.directory(p,0);case "glorious" -> menus.awards(p,null,0);
            case "profile" -> {
                if(args.length<2){menus.profile(p,p.getUniqueId());return;}
                UUID id=null;try{id=UUID.fromString(args[1]);}catch(IllegalArgumentException ignored){ }
                if(id==null)id=view().byName(args[1]).map(Person::uuid).orElse(null);
                if(id==null && p.getName().equalsIgnoreCase(args[1]))id=p.getUniqueId();
                if(id==null)reply(p,"That player has no sandbox profile yet.");else menus.profile(p,id);
            }
            default -> reply(p,"Use /donors monthly, alltime, me, directory, glorious, or profile <player>.");
        }
    }
    private void test(CommandSender sender,String[] a){
        if(a.length==0){if(sender instanceof Player p)menus.testing(p);else help(sender);return;}
        String sub=a[0].toLowerCase(Locale.ROOT);
        switch(sub){
            case "menu" -> {if(sender instanceof Player p)menus.testing(p);else reply(sender,"Open the menu in-game.");}
            case "status" -> status(sender);case "logs" -> showLogs(sender);
            case "luckperms" -> luckPermsReference(sender);
            case "purchase" -> {
                require(a,3,"purchase <player> <avid|devotee> [amount]");Product product=Product.parse(a[2]);long cents=a.length>3?TestSettings.money(a[3]):price(product);
                watch(sender,resolve(a[1]),p -> payment(sender,p,p,product,product==Product.DEVOTEE?Kind.SUBSCRIPTION:Kind.PURCHASE,cents));
            }
            case "gift" -> {
                require(a,4,"gift <buyer> <recipient> <avid|devotee> [amount]");Product product=Product.parse(a[3]);long cents=a.length>4?TestSettings.money(a[4]):price(product);
                watch(sender,resolve(a[1]).thenCombine(resolve(a[2]),Targets::new),t -> payment(sender,t.buyer,t.recipient,product,Kind.GIFT,cents));
            }
            case "renewal" -> {
                require(a,3,"renewal <player> devotee [amount]");if(Product.parse(a[2])!=Product.DEVOTEE)throw new IllegalArgumentException("Only Devotee renews.");
                long cents=a.length>3?TestSettings.money(a[3]):price(Product.DEVOTEE);watch(sender,resolve(a[1]),p -> payment(sender,p,p,Product.DEVOTEE,Kind.RENEWAL,cents));
            }
            case "target" -> {
                require(a,3,"target <buyer> <recipient>");Player p=requirePlayer(sender);
                watch(p,resolve(a[1]).thenCombine(resolve(a[2]),Targets::new),t -> {session(p).targets=t;reply(p,"Buyer: "+t.buyer.name()+"; recipient/skin: "+t.recipient.name());});
            }
            case "cancel","expire" -> {require(a,2,sub+" <player>");watch(sender,resolve(a[1]),p -> subscriptionAction(sender,p,sub));}
            case "refund","chargeback" -> {require(a,2,sub+" <player|event-id>");reverse(sender,a[1],sub.equals("refund")?Status.REFUNDED:Status.CHARGEBACK);}
            case "replay" -> {require(a,2,"replay <last|event-id>");replay(sender,a[1]);}
            case "glorious" -> {require(a,2,"glorious <player>");watch(sender,resolve(a[1]),p -> glorious(sender,p));}
            case "skin" -> {require(a,2,"skin <player>");watch(sender,resolve(a[1]),p -> preview(sender,new Notice("preview:"+UUID.randomUUID(),"purchase",p,p,Product.AVID,view().month())));}
            case "preview","discord" -> {require(a,2,sub+" <purchase|subscription|gift|renewal|glorious>");Player p=requirePlayer(sender);Notice n=notice(p,a[1]);if(sub.equals("preview"))preview(p,n);else discordPreview(p,n);}
            case "monthend" -> closeMonth(sender);
            case "advance" -> {require(a,2,"advance <days>");int days=Integer.parseInt(a[1]);if(days<1||days>366)throw new IllegalArgumentException("Use 1 to 366 days.");watch(sender,engine.edit("ADVANCE",s -> Ledger.advanceTo(s,Math.addExact(s.now,Duration.ofDays(days).toMillis()))),awards -> {announceAwards(sender,awards);reply(sender,"Virtual clock: "+Instant.ofEpochMilli(view().now())+". Real server time unchanged.");});}
            case "populate" -> {int count=a.length>1?Integer.parseInt(a[1]):25;if(count<1||count>100)throw new IllegalArgumentException("Choose 1 to 100 test donors.");populate(sender,count);}
            case "profile" -> {require(a,2,"profile <player>");watch(sender,resolve(a[1]),p -> sampleProfile(sender,p));}
            case "failure" -> {require(a,2,"failure <skin|database|discord>");switch(a[1].toLowerCase(Locale.ROOT)){case "skin"->skins.injectFailure();case "database"->engine.injectDatabaseFailure();case "discord"->discord.injectFailure();default->throw new IllegalArgumentException("Supported injected failures: skin, database, discord. Live adapters are disconnected.");}reply(sender,"Armed one "+a[1]+" failure. No production settings changed.");}
            case "audience" -> {
                require(a,2,"audience <self|server confirm|network confirm>");Player p=requirePlayer(sender);
                if(a[1].equalsIgnoreCase("self")){session(p).serverAudience=false;session(p).networkAudience=false;reply(p,"Donation previews are private to you.");}
                else if(a[1].equalsIgnoreCase("server")&&a.length>2&&a[2].equalsIgnoreCase("confirm")&&settings.serverBroadcasts()){
                    session(p).serverAudience=true;session(p).networkAudience=false;reply(p,"[TEST] broadcasts will be visible to this server until you leave or choose audience self.");
                }else if(a[1].equalsIgnoreCase("network")&&a.length>2&&a[2].equalsIgnoreCase("confirm")&&settings.networkBroadcasts()){
                    session(p).serverAudience=false;session(p).networkAudience=true;reply(p,"[TEST] broadcasts will be sent through the proxy to all connected network players until you leave or choose audience self.");
                }else throw new IllegalArgumentException("Enable the matching broadcast audience in testing.yml, reload, then confirm it here.");
            }
            case "reset" -> {
                require(a,2,"reset confirm");if(!a[1].equalsIgnoreCase("confirm"))throw new IllegalArgumentException("Type reset confirm to back up and clear only the sandbox.");
                watch(sender,engine.reset(Instant.now()),ignored -> {sessions.clear();reply(sender,"Sandbox cleared and backed up. Production data and all LuckPerms groups were untouched.");});
            }
            default -> help(sender);
        }
    }
    private static void require(String[] a,int count,String usage){if(a.length<count)throw new IllegalArgumentException("Usage: /edonors test "+usage);}
    private static Player requirePlayer(CommandSender sender){if(sender instanceof Player p)return p;throw new IllegalArgumentException("This preview control requires an in-game operator.");}
    private long price(Product product){return product==Product.AVID?settings.avidCents():settings.devoteeCents();}
    private CompletableFuture<Person> resolve(String name){
        Person existing=view().byName(name).orElse(null);if(existing!=null)return CompletableFuture.completedFuture(existing);
        Player online=Bukkit.getPlayerExact(name);if(online!=null)return CompletableFuture.completedFuture(person(online));
        OfflinePlayer cached=Bukkit.getOfflinePlayerIfCached(name);
        if(cached!=null && cached.getName()!=null)return CompletableFuture.completedFuture(new Person(cached.getUniqueId(),cached.getName(),false));
        // Construct first to validate the name before any network lookup.
        Person fallback=Person.synthetic(name);
        if(!settings.skinNetwork())return CompletableFuture.completedFuture(fallback);
        PlayerProfile profile=Bukkit.createPlayerProfile(null,name);
        return profile.update().orTimeout(settings.skinTimeout(),TimeUnit.SECONDS).handle((updated,error) -> {
            if(error==null&&updated.getUniqueId()!=null)return new Person(updated.getUniqueId(),updated.getName()==null?name:updated.getName(),false);
            return fallback;
        });
    }
    private void payment(CommandSender sender,Person buyer,Person recipient,Product product,Kind kind,long cents){
        String id="test:"+UUID.randomUUID();
        watch(sender,engine.edit("PAYMENT",s -> {boolean added=Ledger.add(s,id,buyer,recipient,product,kind,cents,s.now,settings.termDays());return new Attempt(added,s.payments.get(id));}),result -> {
            if(!result.created){reply(sender,"Duplicate ignored; no balance or announcements changed.");return;}
            if(sender instanceof Player p)session(p).lastPayment=id;
            reply(sender,"Saved sandbox event "+id+". "+buyer.name()+" paid; "+recipient.name()+" receives "+product.display()+". No real rank was changed.");
            if(recipient.synthetic())reply(sender,"Recipient identity is synthetic (lookup unavailable); showing a neutral fallback, not another player's skin.");
            announce(Broadcasts.purchase(result.payment,view()),sender,true);
        });
    }
    private void subscriptionAction(CommandSender sender,Person player,String action){
        watch(sender,engine.edit(action.toUpperCase(Locale.ROOT),s -> {if(action.equals("cancel"))Ledger.cancel(s,player.uuid());else Ledger.expire(s,player.uuid());return true;}),ok -> reply(sender,action.equals("cancel")?"Renewal cancelled in sandbox; paid access remains until expiry.":"Sandbox Devotee access expired; paid support and permanent Glorious history remain."));
    }
    private void reverse(CommandSender sender,String target,Status status){
        Payment exact=view().payments().stream().filter(p -> p.id().equals(target)).findFirst().orElse(null);
        if(exact==null){Person person=view().byName(target).orElseThrow(() -> new IllegalArgumentException("No sandbox payment/player found. Use the event ID from test logs."));
            exact=view().payments().stream().filter(p -> p.status()==Status.PAID && (p.buyer().equals(person.uuid())||p.recipient().equals(person.uuid())))
                    .reduce((first,second) -> second.paidAt()>=first.paidAt()?second:first).orElseThrow(() -> new IllegalArgumentException("No paid test transaction to reverse."));}
        String id=exact.id();watch(sender,engine.edit(status.name(),s -> Ledger.reverse(s,id,status)),p -> reply(sender,status+" applied to "+id+". Totals recalculated. Permanent Glorious awards retained."));
    }
    private void replay(CommandSender sender,String id){
        if(id.equalsIgnoreCase("last"))id=session(requirePlayer(sender)).lastPayment;
        String selected=id;Payment original=view().payments().stream().filter(p -> p.id().equals(selected)).findFirst().orElseThrow(() -> new IllegalArgumentException("No last test event; create a purchase first."));
        watch(sender,engine.edit("REPLAY",s -> Ledger.add(s,original.id(),s.people.get(original.buyer()),s.people.get(original.recipient()),original.product(),original.kind(),original.cents(),original.paidAt(),settings.termDays())),added -> reply(sender,added?"Unexpected new event; inspect audit log.":"PASS: duplicate ignored. No extra support, ranks, gift records, chat, or Discord send."));
    }
    private void glorious(CommandSender sender,Person person){
        watch(sender,engine.edit("MANUAL_GLORIOUS",s -> Ledger.manualGlorious(s,person)),a -> {
            reply(sender,"Glorious is permanent in the sandbox profile. This manual test does not count as a monthly victory.");
            // A manual preview has a distinct ID. It does not falsify a monthly win.
            preview(sender,new Notice("manual-preview:"+UUID.randomUUID(),"glorious",person,person,null,"MANUAL TEST"));
        });
    }
    private void closeMonth(CommandSender sender){watch(sender,engine.edit("MONTH_END",Ledger::closeMonth),awards -> {
        announceAwards(sender,awards);reply(sender,"Test month closed. New month: "+view().month()+". "+(awards.isEmpty()?"No eligible winner; no award issued.":"Winner(s) permanently recorded."));
    });}
    private void announceAwards(CommandSender sender,List<Award> awards){for(Award a:awards){Person p=view().people().get(a.winner());announce(new Notice(a.key(),"glorious",p,p,null,a.month()),sender,true);}}
    private void populate(CommandSender sender,int count){
        watch(sender,engine.edit("POPULATE",s -> {
            long start=YearMonth.parse(s.openMonth).atDay(1).atStartOfDay(ZoneId.of(s.zone)).toInstant().toEpochMilli();
            long old=YearMonth.parse(s.openMonth).minusMonths(1).atDay(15).atStartOfDay(ZoneId.of(s.zone)).toInstant().toEpochMilli();int inserted=0;
            for(int i=1;i<=count;i++){
                Person buyer=Person.synthetic(String.format(Locale.ROOT,"TestDonor%02d",i));
                Person recipient=i%5==0?Person.synthetic(String.format(Locale.ROOT,"TestDonor%02d",i%count+1)):buyer;
                Product product=i%3==0?Product.DEVOTEE:Product.AVID;
                Kind kind=buyer.uuid().equals(recipient.uuid())?(product==Product.DEVOTEE?Kind.SUBSCRIPTION:Kind.PURCHASE):Kind.GIFT;
                if(Ledger.add(s,"seed:"+s.openMonth+":"+i,buyer,recipient,product,kind,500+(count-i)*137L,Math.min(s.now,start+i*1000L),settings.termDays()))inserted++;
                Ledger.add(s,"seed-old:"+s.openMonth+":"+i,buyer,buyer,Product.AVID,Kind.PURCHASE,i*311L,old,settings.termDays());
            }return inserted;
        }),countAdded -> reply(sender,"Added "+countAdded+" monthly sample donors plus historical records. No broadcast or Discord spam. Open /donors."));
    }
    private void sampleProfile(CommandSender sender,Person person){
        watch(sender,engine.edit("SAMPLE_PROFILE",s -> {
            for(int i=0;i<28;i++){
                Person other=Person.synthetic("GiftFriend"+String.format(Locale.ROOT,"%02d",i%5+1));
                Ledger.add(s,"profile:"+person.uuid()+":"+s.openMonth+":"+i,person,other,i%2==0?Product.AVID:Product.DEVOTEE,Kind.GIFT,i%2==0?2000:500,s.now,settings.termDays());
            }
            Ledger.add(s,"profile-self:"+person.uuid()+":"+s.openMonth,person,person,Product.DEVOTEE,Kind.SUBSCRIPTION,500,s.now,settings.termDays());
            Person friend=Person.synthetic("GiftFriend01");Ledger.add(s,"profile-received:"+person.uuid()+":"+s.openMonth,friend,person,Product.AVID,Kind.GIFT,2000,s.now,settings.termDays());
            Ledger.manualGlorious(s,person);return true;
        }),ok -> {reply(sender,"Sample profile prepared, including paginated gift history. All records are test-only.");if(sender instanceof Player p)menus.profile(p,person.uuid());});
    }
    public void action(Player player,String action){
        if(!player.hasPermission("enthusiadonors.test"))return;Session session=session(player);long now=System.currentTimeMillis();
        if(now-session.lastAction<800){reply(player,"Please wait a moment between test actions.");return;}session.lastAction=now;
        Targets t=session.targets;
        try {
            switch(action){
                case "purchase-avid" -> payment(player,t.recipient,t.recipient,Product.AVID,Kind.PURCHASE,price(Product.AVID));
                case "purchase-devotee" -> payment(player,t.recipient,t.recipient,Product.DEVOTEE,Kind.SUBSCRIPTION,price(Product.DEVOTEE));
                case "gift-avid" -> payment(player,t.buyer,t.recipient,Product.AVID,Kind.GIFT,price(Product.AVID));
                case "gift-devotee" -> payment(player,t.buyer,t.recipient,Product.DEVOTEE,Kind.GIFT,price(Product.DEVOTEE));
                case "renewal" -> payment(player,t.recipient,t.recipient,Product.DEVOTEE,Kind.RENEWAL,price(Product.DEVOTEE));
                case "cancel","expire" -> subscriptionAction(player,t.recipient,action);
                case "refund","chargeback" -> reverse(player,t.recipient.name(),action.equals("refund")?Status.REFUNDED:Status.CHARGEBACK);
                case "replay" -> replay(player,"last");case "glorious" -> glorious(player,t.recipient);case "monthend" -> closeMonth(player);case "populate" -> populate(player,25);
                case "skin" -> preview(player,new Notice("skin:"+UUID.randomUUID(),"purchase",t.recipient,t.recipient,Product.AVID,view().month()));
                case "discord" -> discordPreview(player,notice(player,"gift"));
                default -> help(player);
            }
        }catch(Exception ex){reply(player,"Not completed: "+root(ex).getMessage());}
    }
    private Notice notice(Player player,String type){
        if(!List.of("purchase","subscription","gift","renewal","glorious").contains(type))throw new IllegalArgumentException("Choose purchase, subscription, gift, renewal, or glorious.");
        Targets t=targets(player);Person buyer=type.equals("gift")?t.buyer:t.recipient;
        return new Notice("preview:"+UUID.randomUUID(),type,buyer,t.recipient,type.equals("subscription")||type.equals("renewal")?Product.DEVOTEE:Product.AVID,view().month());
    }
    private void preview(CommandSender sender,Notice notice){announce(notice,sender,false);}
    private void announce(Notice notice,CommandSender sender,boolean sendDiscord){
        boolean toNetwork=sender instanceof Player p && session(p).networkAudience && settings.networkBroadcasts();
        boolean toServer=!toNetwork && sender instanceof Player p && session(p).serverAudience && settings.serverBroadcasts();
        Player carrier=toNetwork?(sender instanceof Player p?p:Bukkit.getOnlinePlayers().stream().findFirst().orElse(null)):null;
        if(toNetwork&&carrier==null){reply(sender,"Network test broadcast needs a connected player on this server to carry the proxy message.");return;}
        String channel=toNetwork?"chat:network":toServer?"chat:server":"chat:"+(sender instanceof Player p?p.getUniqueId():"console");
        CompletableFuture.supplyAsync(() -> {try{return engine.database().claim(notice.id(),channel);}catch(Exception ex){throw new CompletionException(ex);}},io)
            .whenComplete((claimed,error) -> main(() -> {
                if(error!=null){reply(sender,"Chat not sent: could not save a dispatch claim.");return;}if(!claimed){reply(sender,"Duplicate broadcast suppressed.");return;}
                skins.get(notice.recipient()).whenComplete((skin,failure) -> main(() -> {
                    try {
                        if(failure!=null)throw new IllegalStateException("Skin preview failed.");
                        List<Component> messageLines=Broadcasts.renderLines(notice,skin.pixels(),settings);
                        if(toNetwork) {
                            ProxyChatBroadcast.send(plugin,carrier,messageLines);
                        } else if(toServer) {
                            Bukkit.getOnlinePlayers().forEach(player -> messageLines.forEach(player::sendMessage));
                        } else {
                            messageLines.forEach(sender::sendMessage);
                        }
                        mark(notice.id(),channel,"SENT",skin.source());reply(sender,toNetwork?"Network-wide TEST broadcast sent through the proxy. Face: "+notice.recipient().name()+" ("+skin.source()+").":"Chat preview sent. Face: "+notice.recipient().name()+" ("+skin.source()+").");
                    }catch(Exception ex){mark(notice.id(),channel,"FAILED","Unable to render/send test message.");reply(sender,"Unable to render test broadcast. Check testing.yml templates.");}
                }));
            }));
        if(sendDiscord && settings.discordEnabled())discordPreview(sender,notice);
    }
    private void mark(String id,String channel,String status,String detail){
        if(!closed)io.execute(() -> {try{engine.database().finish(id,channel,status,detail);}catch(Exception ex){plugin.getLogger().warning("Could not store test dispatch receipt; do not blindly resend.");}});
    }
    private void discordPreview(CommandSender sender,Notice n){watch(sender,discord.send(n,settings),r -> reply(sender,"Discord: "+r.state()+" — "+r.detail()));}
    public void storeLink(Player p){p.closeInventory();if(settings.storeUrl().isBlank()){reply(p,"Set store-url in testing.yml and use /edonors reload.");return;}
        p.sendMessage(Component.text("[TEST] Visit the Enthusia Store",NamedTextColor.GREEN).decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.openUrl(settings.storeUrl())));
    }
    public void showLogs(CommandSender sender){watch(sender,engine.logs(),lines -> {reply(sender,"Recent sandbox audit entries (newest first):");lines.stream().limit(15).forEach(line -> reply(sender,line));});}
    public void luckPermsReference(CommandSender sender){
        LuckPermsReference.summaryLines().forEach(line -> reply(sender,line));
    }
    private void status(CommandSender sender){
        reply(sender,"Build 1.1.0-test.4 | isolated, persistent sandbox | test month "+view().month()+" ("+view().zone()+")");
        reply(sender,"Profiles: "+view().people().size()+" | payments: "+view().payments().size()+" | permanent awards: "+view().awards().size());
        reply(sender,"Tebex: DISCONNECTED | production DB/R2: DISCONNECTED | LuckPerms writes: DISCONNECTED");
        reply(sender,"Discord: "+(settings.discordEnabled()&&!settings.discordUrl().isBlank()?"DEDICATED TEST WEBHOOK ENABLED":"DISABLED")+" | virtual clock is paused between test actions.");
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String label,String[] args){
        if(command.getName().equals("donors"))return args.length==1?filter(List.of("monthly","alltime","me","profile","directory","glorious"),args[0]):List.of();
        if(!sender.hasPermission("enthusiadonors.test"))return List.of();
        if(args.length==1)return filter(List.of("test","status","reload","top"),args[0]);
        if(args.length==2 && args[0].equalsIgnoreCase("test"))return filter(List.of("menu","target","purchase","gift","renewal","cancel","expire","refund","chargeback","replay","glorious","skin","preview","monthend","advance","populate","profile","discord","failure","luckperms","audience","logs","status","reset"),args[1]);
        if(args.length==3 && args[0].equalsIgnoreCase("test") && args[1].equalsIgnoreCase("audience"))return filter(List.of("self","server","network"),args[2]);
        if(args.length>=3){String last=args[args.length-1];if(args[1].equals("purchase")&&args.length==4 ||args[1].equals("gift")&&args.length==5 ||args[1].equals("renewal")&&args.length==4)return filter(List.of("avid","devotee"),last);
            if((args[1].equals("preview")||args[1].equals("discord"))&&args.length==3)return filter(List.of("purchase","subscription","gift","renewal","glorious"),last);
            if(args[1].equals("failure")&&args.length==3)return filter(List.of("skin","database","discord"),last);
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),last);
        }return List.of();
    }
    private List<String> filter(List<String> values,String prefix){return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();}
    @EventHandler public void quit(PlayerQuitEvent event){sessions.remove(event.getPlayer().getUniqueId());}
    @Override public void close(){
        closed=true;for(Player p:Bukkit.getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menus.Holder)p.closeInventory();
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin,ProxyChatBroadcast.CHANNEL);
        plugin.getServer().getServicesManager().unregisterAll(plugin);HandlerList.unregisterAll(plugin);
        if(discord!=null)discord.close();if(skins!=null)skins.close();
        io.shutdown();try{if(!io.awaitTermination(5,TimeUnit.SECONDS))io.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();io.shutdownNow();}
        if(engine!=null)engine.close();sessions.clear();
    }
}
