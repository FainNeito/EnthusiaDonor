package com.enthusia.donors.sandbox.ui;

import com.enthusia.donors.sandbox.TestSettings;
import com.enthusia.donors.sandbox.domain.Domain.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.*;

public final class Broadcasts {
    private Broadcasts() { }
    public record Notice(String id,String type,Person buyer,Person recipient,Product product,String month) { }
    public static Notice purchase(Payment p,View view) {
        String type=p.gift()?"gift":switch(p.kind()){case RENEWAL->"renewal";case SUBSCRIPTION->"subscription";default->"purchase";};
        return new Notice(p.id(),type,view.people().get(p.buyer()),view.people().get(p.recipient()),p.product(),view.month());
    }
    public static Component render(Notice n,int[] pixels,TestSettings s) {
        return Component.join(JoinConfiguration.separator(Component.newline()), renderLines(n,pixels,s));
    }

    /**
     * Mirrors NotBounties' extended-broadcast head layout: eight separately rendered chat rows,
     * each containing eight default-font full-block glyphs colored from the 8x8 skin face.
     */
    public static List<Component> renderLines(Notice n,int[] pixels,TestSettings s) {
        if(pixels.length!=64) throw new IllegalArgumentException("Face must contain exactly 64 pixels.");
        boolean hidden=n.type.equals("gift") && !s.showGiftIdentities();
        Component buyer=hidden ? Component.text("A supporter",NamedTextColor.GREEN) : name(n.buyer);
        Component recipient=name(n.recipient);
        MiniMessage mm=MiniMessage.miniMessage();
        String rankKey=n.type.equals("glorious")?"glorious":n.product==Product.AVID?"avid":"devotee";
        Component rank=safeDeserialize(mm,s.rankFormats().getOrDefault(rankKey,TestSettings.DEFAULT_RANK_FORMATS.get(rankKey)));
        String storeLabel=s.storeUrl().isBlank()
                ? (s.storeDisplayText().isBlank()?"Store link not configured":s.storeDisplayText())
                : (s.storeDisplayText().isBlank()?s.storeUrl():s.storeDisplayText());
        Component store=Component.text(storeLabel,s.storeUrl().isBlank()?NamedTextColor.DARK_GRAY:NamedTextColor.GREEN);
        if(!s.storeUrl().isBlank()) store=store.decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(s.storeUrl())).hoverEvent(HoverEvent.showText(Component.text("Open the server store")));
        Component cta=safeDeserialize(mm,s.storeCta());
        List<String> lines=s.templates().getOrDefault(n.type,List.of());
        if(lines.isEmpty()) lines=defaults(n.type);
        Component top=safeDeserialize(mm,s.topDivider()),bottom=safeDeserialize(mm,s.bottomDivider());
        List<Component> out=new ArrayList<>();
        out.add(top);
        out.add(safeDeserialize(mm,s.broadcastHeader()).append(Component.text(" ")).append(safeDeserialize(mm,s.broadcastMarker())));
        for(int y=0;y<8;y++) {
            Component row=Component.empty();
            for(int x=0;x<8;x++) {
                // Intentionally use Minecraft's normal chat font, exactly like NotBounties' legacy
                // ChatColor + '█' renderer. Do not use minecraft:uniform and do not duplicate pixels.
                row=row.append(Component.text("\u2588",TextColor.color(pixels[y*8+x])));
            }
            Component message;
            String line=y<lines.size()?lines.get(y):"";
            try {
                message=mm.deserialize(line,
                        Placeholder.component("buyer",buyer),Placeholder.component("recipient",recipient),Placeholder.component("rank",rank),
                        Placeholder.component("month",Component.text(n.month,NamedTextColor.GOLD)),Placeholder.component("store",store),
                        Placeholder.component("cta",cta));
            } catch(RuntimeException malformedTemplate) {
                // A bad admin-edited MiniMessage line is shown literally; it must not break broadcasts.
                message=Component.text(line);
            }
            out.add(row.append(Component.text(" ")).append(message));
        }
        out.add(bottom);
        return List.copyOf(out);
    }
    private static Component safeDeserialize(MiniMessage mm,String input) {
        try { return mm.deserialize(input); }
        catch(RuntimeException malformedFormat) { return Component.text(input); }
    }
    private static Component name(Person p) {
        return Component.text(p.name(),NamedTextColor.GREEN)
                .hoverEvent(HoverEvent.showText(Component.text("View "+p.name()+"'s test donor profile")))
                .clickEvent(ClickEvent.runCommand("/donors profile "+p.uuid()));
    }
    public static List<String> defaults(String type) {
        return switch(type) {
            case "gift" -> List.of("","<buyer> <gray>just gifted</gray>","<rank> <gray>to</gray>","<recipient><gray>!</gray>","","<gray><cta></gray>","<gray>at</gray> <store>","");
            case "subscription" -> List.of("","<buyer> <gray>just subscribed</gray>","<gray>and received</gray> <rank><gray>!</gray>","","","<gray><cta></gray>","<gray>at</gray> <store>","");
            case "renewal" -> List.of("","<buyer> <gray>renewed their</gray>","<rank> <gray>subscription!</gray>","","","<gray><cta></gray>","<gray>at</gray> <store>","");
            case "glorious" -> List.of("","<recipient> <gray>finished</gray>","<gray>#1 for</gray> <month><gray>!</gray>","","<gray>Permanently awarded</gray> <rank><gray>!</gray>","<gray><cta></gray>","<gray>at</gray> <store>");
            default -> List.of("","<buyer> <gray>just donated</gray>","<gray>and received</gray> <rank><gray>!</gray>","","","<gray><cta></gray>","<gray>at</gray> <store>","");
        };
    }
    public static String discordText(Notice n,TestSettings s) {
        String buyer=n.type.equals("gift") && !s.showGiftIdentities()?"A supporter":n.buyer.name();
        buyer=markdown(buyer); String recipient=markdown(n.recipient.name());
        String sentence=switch(n.type) {
            case "gift" -> buyer+" just gifted **"+n.product.display()+"** to "+recipient+"!";
            case "subscription" -> buyer+" just subscribed and received **Devotee**!";
            case "renewal" -> buyer+" renewed their **Devotee** subscription!";
            case "glorious" -> recipient+" finished "+n.month+" as #1 Monthly Donator and permanently earned **Glorious**!";
            default -> buyer+" just purchased and received **"+n.product.display()+"**!";
        };
        return "**[TEST — simulated event]**\n"+sentence+"\n\nThank you for supporting Enthusia!"+(s.storeUrl().isBlank()?"":"\n"+s.storeUrl());
    }
    private static String markdown(String text) {return text.replace("\\","\\\\").replace("_","\\_").replace("*","\\*").replace("~","\\~").replace("`","\\`");}
}
