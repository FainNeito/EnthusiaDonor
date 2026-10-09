package com.enthusia.donors.sandbox.official.notifications;

import com.enthusia.donors.model.PaymentRecord;
import com.google.gson.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.*;
import java.util.*;

/** Confirmed payment thank-you; does not claim that a rank or gift entitlement was granted. */
public final class PaymentMessages {
    private PaymentMessages() { }
    public static List<Component> chat(PaymentRecord payment,int[] pixels,NotificationSettings settings) {
        if(pixels.length!=64)throw new IllegalArgumentException("Expected 8x8 face");
        Component name=Component.text(payment.playerName(),NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/donors profile "+payment.playerUuid()))
                .hoverEvent(HoverEvent.showText(Component.text("View "+payment.playerName()+"'s donor profile")));
        Component store=Component.text(settings.storeUrl().isBlank()?"Thank you for supporting Enthusia!":settings.storeUrl(),NamedTextColor.GREEN);
        if(!settings.storeUrl().isBlank())store=store.clickEvent(ClickEvent.openUrl(settings.storeUrl()));
        List<Component> text=List.of(Component.empty(),name.append(Component.text(" just donated",NamedTextColor.GRAY)),
                settings.showAmounts()?Component.text(amount(payment)+" to Enthusia!",NamedTextColor.GOLD):Component.text("to Enthusia!",NamedTextColor.GOLD),
                Component.empty(),Component.text("Thank you for your support!",NamedTextColor.GRAY),Component.empty(),store,Component.empty());
        Component divider=Component.text("────────────────────────────────────────",NamedTextColor.DARK_GRAY);
        List<Component> lines=new ArrayList<>();lines.add(divider);lines.add(Component.text("$$ Donation Broadcast",NamedTextColor.GOLD));
        for(int y=0;y<8;y++) {
            Component face=Component.empty();for(int x=0;x<8;x++)face=face.append(Component.text("█",TextColor.color(pixels[y*8+x])));
            lines.add(face.append(Component.text(" ")).append(text.get(y)));
        }
        lines.add(divider);return List.copyOf(lines);
    }
    public static String discord(PaymentRecord payment,NotificationSettings settings) {
        String name=payment.playerName().replace("\\","\\\\").replace("_","\\_").replace("*","\\*").replace("~","\\~").replace("`","\\`");
        String content="**"+name+"** just donated"+(settings.showAmounts()?" **"+amount(payment)+"**":"")+" to Enthusia!\nThank you for your support!"+(settings.storeUrl().isBlank()?"":"\n"+settings.storeUrl());
        if(content.length()>2000)throw new IllegalArgumentException("Donation message exceeds Discord limit");
        JsonObject json=new JsonObject();json.addProperty("content",content);json.addProperty("username","Enthusia Donations");
        JsonObject mentions=new JsonObject();mentions.add("parse",new JsonArray());json.add("allowed_mentions",mentions);return json.toString();
    }
    private static String amount(PaymentRecord p) {return p.amount().stripTrailingZeros().toPlainString()+" "+p.currency();}
}
