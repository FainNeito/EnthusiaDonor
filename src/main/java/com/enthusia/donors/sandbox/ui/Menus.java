package com.enthusia.donors.sandbox.ui;

import com.enthusia.donors.sandbox.TestRuntime;
import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.sandbox.domain.Domain.*;
import com.enthusia.donors.sandbox.skin.SkinService;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** Holder-based ownership, not title comparisons. Every movement path is cancelled. */
public final class Menus implements Listener {
    private static final int[] PODIUM={13,21,23,28,29,30,31,32,33,34};
    private static final int[] GRID={10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private final TestRuntime runtime;
    public Menus(TestRuntime runtime){this.runtime=runtime;}
    public static int[] leaderboardSlots(){return PODIUM.clone();}
    public static int[] contentSlots(){return GRID.clone();}
    public static final class Holder implements InventoryHolder {
        private Inventory inventory; private final UUID viewer; private final Map<Integer,Runnable> actions=new HashMap<>();
        private Holder(UUID viewer){this.viewer=viewer;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private Holder base(Player p,String title) {
        return base(p,title,true);
    }
    private Holder base(Player p,String title,boolean test) {
        Holder h=new Holder(p.getUniqueId()); h.inventory=Bukkit.createInventory(h,54,Component.text(title+(test?" [TEST]":""),NamedTextColor.GOLD));
        ItemStack filler=item(Material.BLACK_STAINED_GLASS_PANE," ",NamedTextColor.DARK_GRAY);
        for(int i=0;i<54;i++)h.inventory.setItem(i,filler);
        button(h,49,Material.BARRIER,"Close",NamedTextColor.RED,p::closeInventory,"Return to the game.");
        return h;
    }
    private void show(Player p,Holder h){p.openInventory(h.inventory);}
    private void button(Holder h,int slot,Material type,String name,TextColor color,Runnable action,String... lore) {
        h.inventory.setItem(slot,item(type,name,color,lore));if(action!=null)h.actions.put(slot,action);
    }
    public static ItemStack item(Material type,String name,TextColor color,String... lore) {
        ItemStack item=new ItemStack(type);ItemMeta meta=item.getItemMeta();
        meta.displayName(Component.text(name,color).decoration(TextDecoration.ITALIC,false));
        List<Component> lines=new ArrayList<>();for(String s:lore)lines.add(Component.text(s,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false));
        meta.lore(lines);meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES,ItemFlag.HIDE_ENCHANTS);item.setItemMeta(meta);return item;
    }
    private void head(Player viewer,Holder h,int slot,Person person,String title,TextColor color,Runnable action,String... lore) {
        ItemStack stack=item(Material.PLAYER_HEAD,title,color,lore);h.inventory.setItem(slot,stack);if(action!=null)h.actions.put(slot,action);
        runtime.skins().getHeadTexture(person).thenAccept(texture -> runtime.mainNext(() -> {
            if(!viewer.isOnline() || viewer.getOpenInventory().getTopInventory().getHolder()!=h || texture==null)return;
            ItemStack current=h.inventory.getItem(slot);if(current==null||current.getType()!=Material.PLAYER_HEAD)return;
            PlayerProfile profile=Bukkit.createPlayerProfile(person.uuid(),null);
            PlayerTextures textures=profile.getTextures();textures.setSkin(texture);profile.setTextures(textures);
            SkullMeta meta=(SkullMeta)current.getItemMeta();meta.setOwnerProfile(profile);current.setItemMeta(meta);
            h.inventory.setItem(slot,current);
        }));
    }
    private String amount(long cents){return runtime.settings().showAmounts()?money(cents):"Hidden";}
    public static String money(long cents){return "$"+java.math.BigDecimal.valueOf(cents,2).toPlainString();}
    private String rank(int rank){return rank>0?"#"+rank:"Unranked";}
    private String date(long millis){return millis==0?"Not yet":Instant.ofEpochMilli(millis).atZone(ZoneId.of(runtime.view().zone())).format(DateTimeFormatter.ofPattern("MMM d, yyyy"));}
    private String officialAmount(java.math.BigDecimal amount){return runtime.settings().showAmounts()
            ?runtime.officialDonors().cache().formatAmount(amount,runtime.officialConfig().get()):"Hidden";}
    private Person officialPerson(DonorEntry entry){
        String skinName=entry.name().matches("[A-Za-z0-9_. -]{1,32}")?entry.name():"Unknown";
        return new Person(entry.uuid(),skinName,false);
    }
    private String officialState(){return runtime.officialDonors().cache().state().name();}
    public void officialLeaderboard(Player p,boolean monthly,int requestedPage) {
        DonorCache.Snapshot snapshot=runtime.officialDonors().cache().snapshot();
        List<DonorEntry> board=monthly?snapshot.monthly():snapshot.alltime();
        int max=Math.max(0,(board.size()-1)/10),page=Math.max(0,Math.min(requestedPage,max));
        Holder h=base(p,monthly?"Monthly Donors":"All-Time Donors",false);
        button(h,1,Material.CLOCK,"Monthly",monthly?NamedTextColor.GOLD:NamedTextColor.GRAY,
                () -> officialLeaderboard(p,true,0),"Official Tebex payments only.");
        head(p,h,4,runtime.person(p),"Your Official Profile",NamedTextColor.AQUA,
                () -> officialProfile(p,p.getUniqueId()),"Public-safe Tebex totals; no test purchases.");
        button(h,7,Material.NETHER_STAR,"All-Time",!monthly?NamedTextColor.GOLD:NamedTextColor.GRAY,
                () -> officialLeaderboard(p,false,0),"Official Tebex payments only.");
        for(int i=0;i<10 && page*10+i<board.size();i++) {
            DonorEntry entry=board.get(page*10+i);
            int rank=monthly?entry.monthlyRank():entry.alltimeRank();
            TextColor color=rank==1?NamedTextColor.GOLD:rank==2?NamedTextColor.WHITE:rank==3?NamedTextColor.YELLOW:NamedTextColor.GRAY;
            head(p,h,PODIUM[i],officialPerson(entry),"#"+rank+"  "+entry.name(),color,
                    () -> officialProfile(p,entry.uuid()),
                    (monthly?"Monthly: ":"All-time: ")+officialAmount(monthly?entry.monthlyTotal():entry.alltimeTotal()),
                    "Source: official Tebex payments",monthly&&rank==1?"Current monthly leader; winner is not finalized yet.":"Click for public profile.");
        }
        if(board.isEmpty())button(h,22,Material.PAPER,"No official donor values loaded",NamedTextColor.GRAY,null,
                "Status: "+officialState(),"Configure tebex.api-key or wait for a full refresh.","Simulated donors are never used here.");
        button(h,45,Material.BOOK,"Donor Directory",NamedTextColor.AQUA,() -> officialDirectory(p,0));
        button(h,48,Material.COMPASS,"Tebex status: "+officialState(),NamedTextColor.GRAY,
                () -> officialLeaderboard(p,monthly,page),
                "Last valid refresh: "+(snapshot.updatedAt()==null?"Never":snapshot.updatedAt().toString()),
                "Page "+(page+1)+" / "+(max+1),"Click to reopen this board.");
        button(h,50,Material.EMERALD,"Visit the Store",NamedTextColor.GREEN,() -> runtime.storeLink(p));
        if(p.hasPermission("enthusiadonors.test"))button(h,51,Material.REDSTONE,"Testing Controls",NamedTextColor.RED,() -> testing(p),
                "Separate simulated donors and broadcasts.");
        if(page>0)button(h,46,Material.ARROW,"Previous",NamedTextColor.YELLOW,() -> officialLeaderboard(p,monthly,page-1));
        if(page<max)button(h,53,Material.ARROW,"Next",NamedTextColor.YELLOW,() -> officialLeaderboard(p,monthly,page+1));
        show(p,h);
    }
    public void officialProfile(Player p,UUID target) {
        DonorCache.Snapshot snapshot=runtime.officialDonors().cache().snapshot();
        DonorEntry entry=snapshot.byUuid(target).orElse(null);
        String name=entry==null?(target.equals(p.getUniqueId())?p.getName():target.toString()):entry.name();
        Holder h=base(p,name+"'s Donor Profile",false);
        if(entry==null)button(h,4,Material.PLAYER_HEAD,name,NamedTextColor.WHITE,null,"Source: official Tebex payments only.");
        else head(p,h,4,officialPerson(entry),name,NamedTextColor.WHITE,null,"Source: official Tebex payments only.");
        if(entry==null)button(h,22,Material.PAPER,"No official donations found",NamedTextColor.GRAY,null,
                "Status: "+officialState(),"Test purchases do not appear in public profiles.");
        else {
            button(h,20,Material.GOLD_INGOT,"All-Time Support",NamedTextColor.GOLD,null,
                    officialAmount(entry.alltimeTotal()),"Rank: "+rank(entry.alltimeRank()));
            button(h,24,Material.CLOCK,"This Month",NamedTextColor.AQUA,null,
                    officialAmount(entry.monthlyTotal()),"Rank: "+rank(entry.monthlyRank()));
            button(h,31,Material.COMPASS,"Official Data Status",NamedTextColor.GRAY,null,
                    officialState(),"Last valid refresh: "+(snapshot.updatedAt()==null?"Never":snapshot.updatedAt().toString()));
        }
        button(h,45,Material.ARROW,"Back to Donors",NamedTextColor.YELLOW,() -> officialLeaderboard(p,true,0));show(p,h);
    }
    public void officialDirectory(Player p,int requestedPage) {
        List<DonorEntry> donors=runtime.officialDonors().cache().snapshot().alltime().stream()
                .sorted(Comparator.comparing(DonorEntry::name,String.CASE_INSENSITIVE_ORDER)).toList();
        int max=Math.max(0,(donors.size()-1)/GRID.length),page=Math.max(0,Math.min(max,requestedPage));
        Holder h=base(p,"Official Donor Directory",false);
        button(h,4,Material.BOOK,"Tebex Donor Profiles",NamedTextColor.AQUA,null,"Public-safe official payment totals only.");
        for(int i=0;i<GRID.length && page*GRID.length+i<donors.size();i++) {
            DonorEntry entry=donors.get(page*GRID.length+i);
            head(p,h,GRID[i],officialPerson(entry),entry.name(),NamedTextColor.WHITE,
                    () -> officialProfile(p,entry.uuid()),"All-time: "+officialAmount(entry.alltimeTotal()));
        }
        paging(p,h,page,max,n -> officialDirectory(p,n),() -> officialLeaderboard(p,true,0));show(p,h);
    }
    public void leaderboard(Player p,boolean monthly,int requestedPage) {
        View view=runtime.view(); List<Ranked> board=monthly?view.monthly():view.alltime();
        int max=Math.max(0,(board.size()-1)/10),page=Math.max(0,Math.min(requestedPage,max));
        Holder h=base(p,monthly?"Monthly Donors":"All-Time Donors");
        button(h,1,Material.CLOCK,"Monthly",monthly?NamedTextColor.GOLD:NamedTextColor.GRAY,() -> leaderboard(p,true,0),"Test month: "+view.month(),"Positive paid support; gifts credit the payer.");
        head(p,h,4,runtime.person(p),"Your Profile",NamedTextColor.AQUA,() -> profile(p,p.getUniqueId()),"Your test support, gifts and earned ranks.");
        button(h,7,Material.NETHER_STAR,"All-Time",!monthly?NamedTextColor.GOLD:NamedTextColor.GRAY,() -> leaderboard(p,false,0),"Lifetime qualifying test support.");
        for(int i=0;i<10 && page*10+i<board.size();i++) {
            Ranked r=board.get(page*10+i);Profile profile=view.profile(r.person().uuid());
            TextColor color=r.rank()==1?NamedTextColor.GOLD:r.rank()==2?NamedTextColor.WHITE:r.rank()==3?NamedTextColor.YELLOW:NamedTextColor.GRAY;
            head(p,h,PODIUM[i],r.person(),"#"+r.rank()+"  "+r.person().name(),color,() -> profile(p,r.person().uuid()),
                    (monthly?"Monthly: ":"All-time: ")+amount(r.cents()),"Sandbox ranks: "+profile.rankText(),
                    monthly&&r.rank()==1?"Current leader, not yet this month's winner.":"Click to view this donor's profile.");
        }
        if(board.isEmpty())button(h,22,Material.PAPER,"No qualifying donations",NamedTextColor.GRAY,null,"Create a test purchase or use /edonors test populate 25.");
        if(page>0)button(h,45,Material.ARROW,"Previous",NamedTextColor.YELLOW,() -> leaderboard(p,monthly,page-1));
        button(h,46,Material.BOOK,"Donor Directory",NamedTextColor.AQUA,() -> directory(p,0));
        button(h,47,Material.GOLDEN_HELMET,"Glorious Hall",NamedTextColor.GOLD,() -> awards(p,null,0),"Finish a month at #1 to earn Glorious permanently.","Monthly minimum: "+runtime.officialConfig().get().currencySymbol()+runtime.officialConfig().glorious().minimumAmount(),"Previous winners never lose their award.");
        button(h,48,Material.COMPASS,"Sandbox Clock",NamedTextColor.GRAY,() -> leaderboard(p,monthly,page),"Month: "+view.month(),"Virtual time: "+date(view.now()),"Page "+(page+1)+" / "+(max+1),"These test ranks never affect official Tebex displays.");
        button(h,50,Material.EMERALD,"Visit the Store",NamedTextColor.GREEN,() -> runtime.storeLink(p),"Sends a clickable link; does not create a purchase.");
        if(p.hasPermission("enthusiadonors.test"))button(h,51,Material.REDSTONE,"Testing Controls",NamedTextColor.RED,() -> testing(p));
        if(page<max)button(h,53,Material.ARROW,"Next",NamedTextColor.YELLOW,() -> leaderboard(p,monthly,page+1));
        show(p,h);
    }
    public void directory(Player p,int requestedPage){
        List<Person> people=runtime.view().people().values().stream().sorted(Comparator.comparing(Person::name,String.CASE_INSENSITIVE_ORDER)).toList();
        Holder h=base(p,"Donor Profiles");int max=Math.max(0,(people.size()-1)/GRID.length),page=Math.max(0,Math.min(max,requestedPage));
        button(h,4,Material.BOOK,"Public Test Profiles",NamedTextColor.AQUA,null,"Donors and gift recipients both have profiles.");
        for(int i=0;i<GRID.length && page*GRID.length+i<people.size();i++) {
            Person person=people.get(page*GRID.length+i);Profile profile=runtime.view().profile(person.uuid());
            head(p,h,GRID[i],person,person.name(),NamedTextColor.WHITE,() -> profile(p,person.uuid()),"Support: "+amount(profile.alltimeCents()),"Sandbox ranks: "+profile.rankText());
        }
        paging(p,h,page,max,n -> directory(p,n),() -> leaderboard(p,true,0));show(p,h);
    }
    public void profile(Player p,UUID target){
        View view=runtime.view();Profile profile=view.profile(target);
        Person person=profile==null ? (target.equals(p.getUniqueId())?runtime.person(p):view.people().get(target)) : profile.person();
        if(person==null){runtime.reply(p,"No test donor profile was found.");return;}
        Holder h=base(p,person.name()+"'s Profile");
        head(p,h,4,person,person.name(),NamedTextColor.WHITE,null,"Public sandbox profile","No private payment details are shown.");
        if(profile==null)button(h,22,Material.PAPER,"No test donations yet",NamedTextColor.GRAY,null,"Create an Avid or Devotee test purchase.");
        else {
            button(h,19,Material.EMERALD,"Support",NamedTextColor.GREEN,() -> history(p,target,"support",0),"All-time: "+amount(profile.alltimeCents())+"  "+rank(profile.alltimeRank()),"Monthly: "+amount(profile.monthlyCents())+"  "+rank(profile.monthlyRank()),"Supporting since: "+date(profile.firstSupportedAt()),"Click for public-safe purchase history.");
            button(h,21,Material.NAME_TAG,"Sandbox Ranks",NamedTextColor.LIGHT_PURPLE,null,profile.rankText(),profile.devotee()?"Devotee until: "+date(profile.devoteeUntil()):"Devotee is not active.",profile.renewalCancelled()?"Renewal cancelled; paid access remains until expiry.":"No cancellation recorded.","Preview entitlements only. LuckPerms is not modified.");
            button(h,23,Material.GOLDEN_HELMET,"Glorious",NamedTextColor.GOLD,() -> awards(p,target,0),profile.glorious()?"Permanently earned":"Not yet earned","Monthly victories: "+profile.monthlyWins(),"Manual test awards are not monthly victories.");
            boolean privateHistory = !runtime.settings().showGiftIdentities() && !target.equals(p.getUniqueId());
            button(h,29,Material.CHEST,"Gifts Sent",NamedTextColor.AQUA,() -> history(p,target,"sent",0),
                    privateHistory?"Private gifts are hidden from public history.":"Completed gifts: "+profile.giftsSent(),
                    "Gift purchases count towards the payer's support.");
            button(h,33,Material.ENDER_CHEST,"Gifts Received",NamedTextColor.AQUA,() -> history(p,target,"received",0),
                    privateHistory?"Private gifts are hidden from public history.":"Completed gifts: "+profile.giftsReceived(),
                    "Receiving a gift does not add to money paid.");
        }
        button(h,45,Material.ARROW,"Back to Donors",NamedTextColor.YELLOW,() -> leaderboard(p,true,0));show(p,h);
    }
    public void history(Player p,UUID target,String type,int requestedPage){
        View view=runtime.view();List<Payment> history=view.history(target,type).stream()
                .filter(pay -> GiftIdentityPolicy.visibleToViewer(
                        pay.gift() && !runtime.settings().showGiftIdentities(),
                        pay.buyer(),pay.recipient(),p.getUniqueId()))
                .toList();
        Holder h=base(p,type.equals("sent")?"Gifts Sent":type.equals("received")?"Gifts Received":"Support History");
        int max=Math.max(0,(history.size()-1)/GRID.length),page=Math.max(0,Math.min(max,requestedPage));
        button(h,4,Material.BOOK,"Public-safe History",NamedTextColor.AQUA,null,"Test records only. No emails, IPs or payment IDs.");
        for(int i=0;i<GRID.length && page*GRID.length+i<history.size();i++){
            Payment pay=history.get(page*GRID.length+i);Person buyer=view.people().get(pay.buyer()),recipient=view.people().get(pay.recipient());
            boolean privateGift=pay.gift() && !runtime.settings().showGiftIdentities();
            GiftIdentityPolicy visibility=GiftIdentityPolicy.forHistory(privateGift,buyer.uuid(),recipient.uuid(),p.getUniqueId(),type);
            String from=visibility.showBuyer()?buyer.name():"Anonymous supporter";
            String to=visibility.showRecipient()?recipient.name():"Private recipient";
            UUID other=type.equals("received")?buyer.uuid():recipient.uuid();
            Runnable open=visibility.linkOther()?() -> profile(p,other):null;
            button(h,GRID[i],pay.status()==Status.PAID?Material.PAPER:Material.RED_DYE,pay.product().display()+" - "+date(pay.paidAt()),NamedTextColor.WHITE,open,
                    "Type: "+pay.kind(),"Paid by: "+from,"Received by: "+to,"Support: "+amount(pay.cents()),"Status: "+pay.status(),open==null?"Gift identity is private.":"Click to view the other player's profile.");
        }
        if(history.isEmpty())button(h,22,Material.PAPER,"No history yet",NamedTextColor.GRAY,null);
        paging(p,h,page,max,n -> history(p,target,type,n),() -> profile(p,target));show(p,h);
    }
    public void awards(Player p,UUID filter,int requestedPage){
        View view=runtime.view();List<Award> awards=view.awards().stream().filter(a -> filter==null||a.winner().equals(filter))
                .sorted(Comparator.comparingLong(Award::awardedAt).reversed()).toList();
        Holder h=base(p,"Glorious Hall");int max=Math.max(0,(awards.size()-1)/GRID.length),page=Math.max(0,Math.min(max,requestedPage));
        button(h,4,Material.GOLDEN_HELMET,"Permanent Recognition",NamedTextColor.GOLD,null,"Monthly winners retain Glorious permanently.","Later winners do not remove earlier awards.","Manual test awards are labelled separately.");
        for(int i=0;i<GRID.length && page*GRID.length+i<awards.size();i++){
            Award a=awards.get(page*GRID.length+i);Person person=view.people().get(a.winner());
            head(p,h,GRID[i],person,person.name()+" - "+a.month(),NamedTextColor.GOLD,() -> profile(p,person.uuid()),a.manual()?"Manual test award":"#1 monthly donor",a.manual()?"Not counted as a monthly victory":"Winning support: "+amount(a.cents()),"Glorious remains permanent.");
        }
        if(awards.isEmpty())button(h,22,Material.PAPER,"No winners yet",NamedTextColor.GRAY,null,"The monthly leader must meet the configured minimum.","Monthly minimum: "+runtime.officialConfig().get().currencySymbol()+runtime.officialConfig().glorious().minimumAmount());
        paging(p,h,page,max,n -> awards(p,filter,n),() -> {if(filter==null)leaderboard(p,true,0);else profile(p,filter);});show(p,h);
    }
    private void paging(Player p,Holder h,int page,int max,Consumer<Integer> open,Runnable back){
        button(h,46,Material.ARROW,"Back",NamedTextColor.YELLOW,back);
        if(page>0)button(h,45,Material.ARROW,"Previous",NamedTextColor.YELLOW,() -> open.accept(page-1));
        button(h,48,Material.PAPER,"Page "+(page+1)+" / "+(max+1),NamedTextColor.GRAY,null);
        if(page<max)button(h,53,Material.ARROW,"Next",NamedTextColor.YELLOW,() -> open.accept(page+1));
    }
    public void testing(Player p){
        if(!p.hasPermission("enthusiadonors.test")){runtime.reply(p,"Testing permission required.");return;}
        TestRuntime.Targets targets=runtime.targets(p);Holder h=base(p,"EnthusiaDonor Testing");
        head(p,h,2,targets.buyer(),"Buyer: "+targets.buyer().name(),NamedTextColor.AQUA,() -> picker(p,true,0),"Click to choose who pays.");
        head(p,h,6,targets.recipient(),"Recipient: "+targets.recipient().name(),NamedTextColor.GREEN,() -> picker(p,false,0),"This player's skin appears on gifts.");
        button(h,4,Material.SHIELD,"ISOLATED SANDBOX",NamedTextColor.GOLD,null,"Test actions never write Tebex, production DB, R2, or LuckPerms.","Public displays separately read official Tebex payments.");
        action(p,h,10,Material.GOLD_INGOT,"Avid Purchase","purchase-avid");action(p,h,12,Material.AMETHYST_SHARD,"Devotee Subscription","purchase-devotee");
        action(p,h,14,Material.CHEST,"Gift Avid","gift-avid");action(p,h,16,Material.ENDER_CHEST,"Gift Devotee","gift-devotee");
        action(p,h,19,Material.CLOCK,"Devotee Renewal","renewal");action(p,h,20,Material.REDSTONE_TORCH,"Cancel Renewal","cancel");action(p,h,21,Material.DEAD_BUSH,"Expire Devotee","expire");
        action(p,h,23,Material.RED_DYE,"Refund Latest Target Payment","refund");action(p,h,24,Material.FERMENTED_SPIDER_EYE,"Chargeback Latest Target Payment","chargeback");action(p,h,25,Material.REPEATER,"Replay Last Event (Duplicate Test)","replay");
        action(p,h,28,Material.GOLDEN_HELMET,"Permanent Glorious Test","glorious");action(p,h,30,Material.SUNFLOWER,"Close Test Month","monthend");action(p,h,32,Material.PLAYER_HEAD,"Populate 25 Donors","populate");action(p,h,34,Material.PAINTING,"Recipient Skin Preview","skin");
        button(h,39,Material.BOOK,"Audit Log",NamedTextColor.AQUA,() -> runtime.showLogs(p));
        action(p,h,41,Material.PAPER,"Send Discord Gift Preview","discord");
        button(h,42,Material.BOOKSHELF,"LuckPerms Dry-Run Mapping",NamedTextColor.LIGHT_PURPLE,
                () -> {p.closeInventory();runtime.luckPermsReference(p);},
                "Reference only — this JAR performs zero LuckPerms writes.",
                "Avid: avid / avid-founder",
                "Devotee: devotee / devotee-founder",
                "Glorious: glorious / glorious-founders");
        button(h,43,Material.NAME_TAG,"Recipient Profile",NamedTextColor.AQUA,() -> profile(p,targets.recipient().uuid()));
        button(h,45,Material.ARROW,"Official Donor Menu",NamedTextColor.YELLOW,() -> officialLeaderboard(p,true,0));
        button(h,47,Material.TNT,"Failure Injection",NamedTextColor.RED,() -> failures(p));
        button(h,48,Material.COMPASS,"Audience: "+runtime.audienceName(p),NamedTextColor.GRAY,null,"Default: only the testing operator sees broadcasts.","Server: enable config, then /edonors test audience server confirm","Network: enable config, then /edonors test audience network confirm","Reset with /edonors test audience self");
        button(h,50,Material.LAVA_BUCKET,"Reset Sandbox",NamedTextColor.RED,() -> {p.closeInventory();runtime.reply(p,"To back up and clear ONLY sandbox data: /edonors test reset confirm");});
        button(h,53,Material.KNOWLEDGE_BOOK,"Commands & Help",NamedTextColor.YELLOW,() -> {p.closeInventory();runtime.help(p);});show(p,h);
    }
    private void action(Player p,Holder h,int slot,Material type,String title,String action){
        button(h,slot,type,title,NamedTextColor.YELLOW,() -> {p.closeInventory();runtime.action(p,action);},"Simulated event; no real purchase or live rank change.");
    }
    private void picker(Player p,boolean buyer,int requestedPage){
        Map<UUID,Person> choices=new LinkedHashMap<>(runtime.view().people());Bukkit.getOnlinePlayers().forEach(o -> choices.put(o.getUniqueId(),runtime.person(o)));
        List<Person> people=choices.values().stream().sorted(Comparator.comparing(Person::name,String.CASE_INSENSITIVE_ORDER)).toList();
        int max=Math.max(0,(people.size()-1)/GRID.length),page=Math.max(0,Math.min(max,requestedPage));Holder h=base(p,buyer?"Choose Buyer":"Choose Recipient");
        button(h,4,Material.NAME_TAG,"Known / Online Players",NamedTextColor.AQUA,null,"To use an offline name:","/edonors test target <buyer> <recipient>");
        for(int i=0;i<GRID.length && page*GRID.length+i<people.size();i++){
            Person person=people.get(page*GRID.length+i);head(p,h,GRID[i],person,person.name(),NamedTextColor.WHITE,() -> {runtime.target(p,buyer,person);testing(p);});
        }
        paging(p,h,page,max,n -> picker(p,buyer,n),() -> testing(p));show(p,h);
    }
    private void failures(Player p){
        Holder h=base(p,"Failure Injection");
        button(h,10,Material.PAINTING,"Skin Lookup Failure",NamedTextColor.RED,() -> {runtime.skins().injectFailure();runtime.reply(p,"Next skin request uses the neutral fallback.");p.closeInventory();});
        button(h,12,Material.REDSTONE_BLOCK,"Database Write Failure",NamedTextColor.RED,() -> {runtime.engine().injectDatabaseFailure();runtime.reply(p,"Next ledger edit fails BEFORE committing or announcing.");p.closeInventory();});
        button(h,14,Material.PAPER,"Discord Timeout",NamedTextColor.RED,() -> {runtime.discord().injectFailure();runtime.reply(p,"Next enabled Discord send simulates a timeout, with no actual request.");p.closeInventory();});
        button(h,16,Material.BARRIER,"Tebex / LuckPerms",NamedTextColor.GRAY,null,"Official Tebex reads are enabled for public displays.","Test actions never write Tebex or LuckPerms.");
        button(h,46,Material.ARROW,"Back",NamedTextColor.YELLOW,() -> testing(p));show(p,h);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Holder h))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||!p.getUniqueId().equals(h.viewer)||e.getRawSlot()<0||e.getRawSlot()>=h.inventory.getSize())return;
        if(e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT)return;
        Runnable action=h.actions.get(e.getRawSlot());if(action!=null)runtime.mainNext(() -> {if(p.isOnline()&&p.getOpenInventory().getTopInventory().getHolder()==h)action.run();});
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
}
