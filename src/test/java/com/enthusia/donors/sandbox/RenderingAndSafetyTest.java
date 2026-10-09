package com.enthusia.donors.sandbox;
import com.enthusia.donors.sandbox.domain.Domain.*;
import com.enthusia.donors.sandbox.ui.*;
import com.enthusia.donors.sandbox.skin.FacePixels;
import com.enthusia.donors.sandbox.discord.TestDiscord;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RenderingAndSafetyTest {
 Person buyer=Person.synthetic("Gift_Giver"),recipient=Person.synthetic("Recipient");
 TestSettings config(boolean gifts,String store){return new TestSettings(ZoneId.of("America/Chicago"),store,true,4,60,true,gifts,2000,500,30,false,"","Enthusia [TEST]",false,false,Map.of(),"<green>$$ Donation Broadcast</green>","<dark_gray>[TEST]</dark_gray>",TestSettings.DEFAULT_DIVIDER,TestSettings.DEFAULT_DIVIDER,"Donate to support the server","",TestSettings.DEFAULT_RANK_FORMATS);}
 Broadcasts.Notice notice(String type){return new Broadcasts.Notice("private-test-id",type,buyer,recipient,Product.AVID,"2026-09");}
 String plain(String type){return PlainTextComponentSerializer.plainText().serialize(Broadcasts.render(notice(type),FacePixels.fallback(),config(true,"")));}
 @Test void giftIncludesGiverAndRecipient(){String text=plain("gift");assertTrue(text.contains("Gift_Giver"));assertTrue(text.contains("Recipient"));assertTrue(text.contains("just gifted"));}
 @Test void rendersExactly64NotBountiesStyleFacePixels(){assertEquals(64,plain("gift").chars().filter(c -> c=='\u2588').count());}
 @Test void notBountiesStyleUsesEightSeparateFaceRows(){List<net.kyori.adventure.text.Component> lines=Broadcasts.renderLines(notice("gift"),FacePixels.fallback(),config(true,""));assertEquals(11,lines.size());for(int i=2;i<10;i++)assertEquals(8,PlainTextComponentSerializer.plainText().serialize(lines.get(i)).chars().filter(c -> c=='\u2588').count());}
 @Test void notBountiesStyleDoesNotForceUniformFont(){String json=GsonComponentSerializer.gson().serialize(Broadcasts.render(notice("gift"),FacePixels.fallback(),config(true,"")));assertFalse(json.contains("minecraft:uniform"));}
 @Test void testLabelCannotBeOmittedByTemplate(){assertTrue(plain("gift").contains("[TEST]"));}
 @Test void missingStoreExplained(){assertTrue(plain("purchase").contains("Store link not configured"));}
 @Test void referenceStyleCallToActionIsPresent(){String text=plain("purchase");assertTrue(text.contains("Donate to support the server"));assertTrue(text.contains("at Store link not configured"));}
 @Test void rankGradientFormatsArePreserved(){String json=GsonComponentSerializer.gson().serialize(Broadcasts.render(notice("purchase"),FacePixels.fallback(),config(true,"")));assertTrue(json.contains("#00C4FF"));assertTrue(json.contains("#17CCFF"));assertTrue(json.contains("#72EAFF"));}
 @Test void devoteePerCharacterGradientIsPreserved(){Broadcasts.Notice devotee=new Broadcasts.Notice("id","subscription",buyer,recipient,Product.DEVOTEE,"2026-09");String json=GsonComponentSerializer.gson().serialize(Broadcasts.render(devotee,FacePixels.fallback(),config(true,"")));assertTrue(json.contains("#0007FF"));assertTrue(json.contains("#070CFF"));assertTrue(json.contains("#3632FF"));}
 @Test void gloriousUsesConfigurableRedGradient(){String json=GsonComponentSerializer.gson().serialize(Broadcasts.render(notice("glorious"),FacePixels.fallback(),config(true,"")));assertTrue(json.contains("#FF110A"));assertTrue(json.contains("#C70000"));}
 @Test void broadcastHeaderAndMarkerAreIndependentConfig(){TestSettings c=config(true,"");c=new TestSettings(c.zone(),c.storeUrl(),c.skinNetwork(),c.skinTimeout(),c.cacheMinutes(),c.showAmounts(),c.showGiftIdentities(),c.avidCents(),c.devoteeCents(),c.termDays(),c.discordEnabled(),c.discordUrl(),c.discordUsername(),c.serverBroadcasts(),c.networkBroadcasts(),c.templates(),"<yellow>Support event</yellow>","<red>[SANDBOX]</red>","<gray>TOP</gray>","<gray>BOTTOM</gray>","Fund the server","Open shop",Map.of("avid","<#123456>[Supporter]</#123456>"));String text=PlainTextComponentSerializer.plainText().serialize(Broadcasts.render(notice("purchase"),FacePixels.fallback(),c));assertTrue(text.contains("Support event [SANDBOX]"));assertTrue(text.contains("Fund the server"));assertTrue(text.contains("Open shop"));assertTrue(text.contains("TOP"));assertTrue(text.contains("BOTTOM"));}
 @Test void malformedPlaceholderSyntaxRemainsVisibleWithoutBreakingBroadcast(){TestSettings c=config(true,"");c=new TestSettings(c.zone(),c.storeUrl(),c.skinNetwork(),c.skinTimeout(),c.cacheMinutes(),c.showAmounts(),c.showGiftIdentities(),c.avidCents(),c.devoteeCents(),c.termDays(),c.discordEnabled(),c.discordUrl(),c.discordUsername(),c.serverBroadcasts(),c.networkBroadcasts(),Map.of("purchase",List.of("<buyer <rank")),c.broadcastHeader(),c.broadcastMarker(),c.topDivider(),c.bottomDivider(),c.storeCta(),c.storeDisplayText(),c.rankFormats());String text=PlainTextComponentSerializer.plainText().serialize(Broadcasts.render(notice("purchase"),FacePixels.fallback(),c));assertTrue(text.contains("<buyer <rank"));}
 @Test void proxyNetworkMessageTargetsEveryPlayerWithRichComponent(){var component=net.kyori.adventure.text.Component.text("[TEST] Network gift").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/donors profile"));try{DataInputStream input=new DataInputStream(new ByteArrayInputStream(ProxyChatBroadcast.encodeMessageRaw(component)));assertEquals("MessageRaw",input.readUTF());assertEquals("ALL",input.readUTF());var decoded=GsonComponentSerializer.gson().deserialize(input.readUTF());assertEquals("[TEST] Network gift",PlainTextComponentSerializer.plainText().serialize(decoded));assertTrue(GsonComponentSerializer.gson().serialize(decoded).contains("run_command"));assertEquals(-1,input.read());}catch(IOException ex){fail(ex);}}
 @Test void storeClickUsesValidatedUrl(){String rendered=PlainTextComponentSerializer.plainText().serialize(Broadcasts.render(notice("purchase"),FacePixels.fallback(),config(true,"https://example.org/store")));String json=GsonComponentSerializer.gson().serialize(Broadcasts.render(notice("purchase"),FacePixels.fallback(),config(true,"https://example.org/store")));assertTrue(rendered.contains("at https://example.org/store"));assertTrue(json.contains("open_url"));assertTrue(json.contains("https://example.org/store"));}
 @Test void skinPixelCountMustBeCorrect(){assertThrows(IllegalArgumentException.class,()->Broadcasts.render(notice("gift"),new int[8],config(true,"")));}
 @Test void hiddenGifterAbsentFromBroadcast(){String text=PlainTextComponentSerializer.plainText().serialize(Broadcasts.render(notice("gift"),FacePixels.fallback(),config(false,"")));assertFalse(text.contains("Gift_Giver"));assertTrue(text.contains("A supporter"));assertTrue(text.contains("Recipient"));}
 @Test void discordDisablesMentions(){JsonObject json=JsonParser.parseString(TestDiscord.payload(notice("gift"),config(true,""))).getAsJsonObject();assertEquals(0,json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());}
 @Test void discordDoesNotExposeEventIds(){String json=TestDiscord.payload(notice("gift"),config(true,""));assertFalse(json.contains("private-test-id"));assertFalse(json.contains(buyer.uuid().toString()));}
 @Test void discordEscapesNameUnderscores(){String text=JsonParser.parseString(TestDiscord.payload(notice("gift"),config(true,""))).getAsJsonObject().get("content").getAsString();assertTrue(text.contains("Gift\\_Giver"));}
 @Test void discordAnonymousGift(){String text=TestDiscord.payload(notice("gift"),config(false,""));assertFalse(text.contains("Gift_Giver"));assertTrue(text.contains("A supporter"));}
 @Test void subscriptionTemplate(){assertTrue(plain("subscription").contains("just subscribed"));}
 @Test void renewalTemplate(){assertTrue(plain("renewal").contains("renewed their"));}
 @Test void gloriousTemplateIsPermanent(){String text=plain("glorious");assertTrue(text.contains("Permanently awarded"));assertTrue(text.contains("[Glorious]"));}
 @Test void faceUsesFrontNotBack(){BufferedImage img=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);for(int y=8;y<16;y++)for(int x=8;x<16;x++)img.setRGB(x,y,0xff123456);assertEquals(0x123456,FacePixels.extract(img)[0]);}
 @Test void opaqueHatCoversFace(){BufferedImage img=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);img.setRGB(8,8,0xff123456);img.setRGB(40,8,0xffabcdef);assertEquals(0xabcdef,FacePixels.extract(img)[0]);}
 @Test void transparentHatKeepsFace(){assertEquals(0x123456,FacePixels.blend(0xff123456,0x00abcdef));}
 @Test void partialHatAlphaBlended(){assertEquals(0x808080,FacePixels.blend(0xff000000,0x80ffffff));}
 @Test void legacySkinSupported(){assertEquals(64,FacePixels.extract(new BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB)).length);}
 @Test void hdSkinSupported(){assertEquals(64,FacePixels.extract(new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB)).length);}
 @Test void invalidSkinRejected(){assertThrows(IllegalArgumentException.class,()->FacePixels.extract(new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB)));}
 @Test void overlyLargeSkinRejected(){assertThrows(IllegalArgumentException.class,()->FacePixels.extract(new BufferedImage(2048,2048,BufferedImage.TYPE_INT_ARGB)));}
 @Test void fallbackHasRightDimensions(){assertEquals(64,FacePixels.fallback().length);}
 @Test void faceArrayIsNewEachTime(){int[] a=FacePixels.fallback(),b=FacePixels.fallback();a[0]=0;assertNotEquals(a[0],b[0]);}
 @Test void podiumSlotsUniqueAndInBounds(){int[] slots=Menus.leaderboardSlots();assertEquals(10,slots.length);assertEquals(10,Arrays.stream(slots).distinct().count());assertTrue(Arrays.stream(slots).allMatch(i->i>=9&&i<45));}
 @Test void paginationSlotsUniqueAndInBounds(){int[] slots=Menus.contentSlots();assertEquals(slots.length,Arrays.stream(slots).distinct().count());assertTrue(Arrays.stream(slots).allMatch(i->i>=9&&i<45));}
 @Test void menuArraysDefensivelyCopied(){int old=Menus.leaderboardSlots()[0];int[] copy=Menus.leaderboardSlots();copy[0]=99;assertEquals(old,Menus.leaderboardSlots()[0]);}
 @ParameterizedTest @CsvSource({"0,0","20,2000","5.00,500","0.01,1","15.92,1592","1000000,100000000"}) void exactMoney(String text,long expected){assertEquals(expected,TestSettings.money(text));}
 @ParameterizedTest @ValueSource(strings={"-1","abc","NaN","Infinity","1.001","1000001","999999999999999999999"}) void invalidMoney(String text){assertThrows(IllegalArgumentException.class,()->TestSettings.money(text));}
 @ParameterizedTest @ValueSource(strings={"http://discord.com/api/webhooks/123/secret","https://evil.example/api/webhooks/123/secret","https://discord.com.evil.example/api/webhooks/123/secret","https://discord.com@evil.example/api/webhooks/123/secret","https://discord.com/api/webhooks/123/secret?wait=true","https://discord.com:443/api/webhooks/123/secret","https://discord.com/api/webhooks/123/secret#fragment","https://discord.com/other"}) void badWebhookUrlsRejectedAndRedacted(String url){Exception ex=assertThrows(IllegalArgumentException.class,()->TestSettings.requireHttps(url,true));assertFalse(ex.getMessage().contains("secret"));}
 @Test void validWebhookUrlAccepted(){assertEquals("discord.com",TestSettings.requireHttps("https://discord.com/api/webhooks/123456789/ABC_secret",true).getHost());}
 @Test void directUserInfoInStoreRejected(){assertThrows(IllegalArgumentException.class,()->TestSettings.requireHttps("https://name:password@example.org",false));}
 @Test void invalidNamesCannotInjectFormatting(){assertThrows(IllegalArgumentException.class,()->Person.synthetic("<click:run_command>"));}
 @Test void onlyTwoPurchasedProductsExist(){assertEquals(2,Product.values().length);assertEquals(Product.DEVOTEE,Product.parse("Devotee"));assertThrows(IllegalArgumentException.class,()->Product.parse("pur"));}
}
