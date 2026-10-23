package dev.openintel;

import dev.openintel.tracker.LocalRelayStore;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class OfflineIntelTest {
    private record Hit(String location, long time) { }

    public static void main(String[] args) throws Exception {
        verifySourceOwnership();
        verifyMultipleRelays();
        verifyEvidenceTime();
        verifySnitchIntake();
        verifyResetWiring();
        verifyProducerOrigins();
        verifyActualStores();
        verifyFeedOwnership();
        System.out.println("Offline intel ownership tests passed");
    }

    private static void verifySourceOwnership() {
        var snitches = new LocalRelayStore<String, Hit>((local, relay) -> local.time >= relay.time ? local : relay);
        var local = new Hit("locally observed coords", 100);
        var remote = new Hit("newer relay-only coords", 200);
        snitches.putLocal("Friend123", local);
        snitches.putRelay("Friend123", remote);
        snitches.putRelay("RemoteOnly", remote);
        check(snitches.values().size() == 2 && snitches.values().contains(remote), "Newest hit is displayed without duplicate player markers");
        snitches.clearRelay();
        check(snitches.values().equals(java.util.List.of(local)), "Relay reset restores only actual local coordinates, never remote data");
        snitches.putRelay("Friend123", new Hit("echo", 100));
        check(snitches.values().equals(java.util.List.of(local)), "Equal-time echo cannot replace local provenance");
        snitches.clearRelay();
        check(snitches.removeIf(hit -> hit.time < 150) && snitches.values().isEmpty(), "Local hits still expire normally offline");
        snitches.putLocal("a", local);
        snitches.putRelay("b", remote);
        check(snitches.removeIf(hit -> true) && snitches.values().isEmpty(), "Expiration checks both sources");
        snitches.putLocal("Friend123", local);
        snitches.putRelay("Friend123", remote);
        snitches.removeIf(hit -> hit.time < 150);
        snitches.clearRelay();
        check(snitches.values().isEmpty(), "Expired hidden local hits cannot reappear after relay reset");
        snitches.putLocal("a", local);
        snitches.putRelay("b", remote);
        snitches.clear();
        check(snitches.values().isEmpty(), "Minecraft disconnect clears both sources");
        var pings = new LocalRelayStore<String, Hit>((own, received) -> own);
        pings.putLocal("my-ping", local);
        pings.putRelay("my-ping", remote);
        pings.putRelay("their-ping", remote);
        pings.clearRelay();
        check(pings.containsKey("my-ping") && !pings.containsKey("their-ping")
                && pings.values().equals(java.util.List.of(local)), "Own pings survive relay reset without retaining others' pings");
    }

    private static void verifyEvidenceTime() {
        var message = new com.google.gson.JsonObject();
        message.addProperty("t", 1000);
        check(dev.openintel.tracker.RelayEvidence.timestamp(message, 3000) == 1000, "Original evidence timestamp survives replay");
        check(dev.openintel.tracker.RelayEvidence.expiry(message, 3000, 500) == 1500, "Old ping does not receive a new lifetime");
        message.addProperty("ttl", 200);
        check(dev.openintel.tracker.RelayEvidence.expiry(message, 4000, 500) == 1200, "Explicit ttl starts at event time");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 5000}) {
            message.addProperty("t", invalid);
            check(dev.openintel.tracker.RelayEvidence.timestamp(message, 3000) == 3000, "Invalid or future timestamp uses receipt time");
        }
        message.remove("t");
        check(dev.openintel.tracker.RelayEvidence.expiry(message, 3000, 500) == 3200, "Legacy ping retains receipt-time fallback");
    }

    private static void verifyMultipleRelays() {
        var store = new LocalRelayStore<String, Hit>((a, b) -> a.time >= b.time ? a : b);
        Hit a = new Hit("relay a", 10), b = new Hit("relay b", 20);
        store.putRelay("a", "same-id", a);
        store.putRelay("b", "same-id", b);
        check(store.values().equals(java.util.List.of(b)), "Newest source wins duplicate ids");
        store.clearRelay("b");
        check(store.values().equals(java.util.List.of(a)), "Removing newest relay reveals surviving provenance");
        store.putRelay("b", "same-id", b);
        store.removeRelayIf("b", hit -> true);
        check(store.values().equals(java.util.List.of(a)), "Where replacement only removes its own source");
        dev.openintel.net.RelaySource.run("outer", () -> {
            check(dev.openintel.net.RelaySource.current().equals("outer"), "Source scope entered");
            try { dev.openintel.net.RelaySource.run("inner", () -> { throw new IllegalStateException(); }); }
            catch (IllegalStateException expected) { }
            check(dev.openintel.net.RelaySource.current().equals("outer"), "Failed nested scope restores source");
            store.putRelay("scoped", b);
        });
        check(dev.openintel.net.RelaySource.current().equals(dev.openintel.net.RelaySource.LEGACY), "Scope cannot leak");
        store.clearRelay("outer");
        check(!store.containsKey("scoped"), "Implicit puts retain scoped provenance");
    }

    private static void verifySnitchIntake() throws Exception {
        var intake = method(readClass("dev/openintel/SnitchRelay"), "onGameMessage");
        int index = 0, local = -1, forward = -1;
        for (var instruction : intake.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("addSnitchHit")) local = index;
            if (instruction instanceof FieldInsnNode field && field.name.equals("snitchRelay")) forward = index;
            index++;
        }
        check(local >= 0 && forward > local, "Forwarding toggle is checked only after local snitch detection");
        var add = method(readClass("dev/openintel/tracker/Tracker"), "addSnitchHit");
        check(!calls(add, "dev/openintel/allegiance/AllegianceManager", "of"), "Local friend hits are not discarded by relay allegiance");
        check(calls(add, "dev/openintel/tracker/LocalRelayStore", "putLocal"), "Local hit records trusted local provenance");
        var dedupe = SnitchRelay.class.getDeclaredMethod("dedupe", String.class);
        dedupe.setAccessible(true);
        check((boolean) dedupe.invoke(null, "offline-test-alert"), "First local alert is accepted");
        check(!(boolean) dedupe.invoke(null, "offline-test-alert"), "Same-session duplicate is suppressed");
        SnitchRelay.reset();
        check((boolean) dedupe.invoke(null, "offline-test-alert"), "New Minecraft session can detect the same alert again");
        SnitchRelay.reset();
    }

    private static void verifyResetWiring() throws Exception {
        var client = readClass("dev/openintel/OpenIntelClient");
        var reconnect = method(client, "reconnectRelay");
        for (String owner : new String[]{"dev/openintel/tracker/Tracker", "dev/openintel/ping/PingManager", "dev/openintel/render/EventFeed"}) {
            check(calls(reconnect, owner, "clearRelay") && !calls(reconnect, owner, "clear"), "Reconnect clears only relay-owned state: " + owner);
        }
        var tracker = readClass("dev/openintel/tracker/Tracker");
        var inbound = tracker.methods.stream().filter(m -> m.name.equals("handleMessage")
                && m.desc.startsWith("(Lcom/google/gson/JsonObject;")).findFirst().orElseThrow();
        check(calls(inbound, "dev/openintel/tracker/Tracker", "clearRelay"), "Intel reset uses relay-only tracker cleanup");
        var sourceClear = tracker.methods.stream().filter(m -> m.name.equals("clearRelay")
                && m.desc.equals("(Ljava/lang/String;)V")).findFirst().orElseThrow();
        check(calls(sourceClear, "dev/openintel/ping/PingManager", "clearRelay"), "Intel reset preserves local pings");
        check(calls(sourceClear, "dev/openintel/render/EventFeed", "clearRelay"), "Intel reset preserves local feed entries");
        check(client.methods.stream().anyMatch(m -> calls(m, "dev/openintel/tracker/Tracker", "clear")
                && calls(m, "dev/openintel/ping/PingManager", "clear")
                && calls(m, "dev/openintel/xaero/RelicMapScanner", "clear")),
                "Level disconnect retains full cleanup");
    }

    private static void verifyProducerOrigins() throws Exception {
        var tracker = readClass("dev/openintel/tracker/Tracker");
        check(calls(method(tracker, "applySnitch"), "dev/openintel/tracker/LocalRelayStore", "putRelay"), "Inbound snitches never acquire local provenance");
        check(calls(method(tracker, "tick"), "dev/openintel/tracker/LocalRelayStore", "removeIf"), "Tracker expiry covers hidden local and relay hits");
        for (var method : tracker.methods) {
            check(!calls(method, "dev/openintel/render/EventFeed", "add"), "Tracker-generated notifications are relay-owned");
        }
        var ping = readClass("dev/openintel/ping/PingManager");
        for (String name : new String[]{"send", "sendShared", "receive"}) {
            boolean found = false;
            for (var instruction : method(ping, name).instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("dev/openintel/ping/PingManager") && call.name.equals("add")) {
                    check(call.getPrevious().getOpcode() == (name.equals("receive") ? Opcodes.ICONST_0 : Opcodes.ICONST_1),
                            "Ping provenance is set by the entry point, not the reported sender: " + name);
                    found = true;
                }
            }
            check(found, "Ping producer stores its origin: " + name);
        }
        check(calls(method(ping, "sendShared"), "dev/openintel/net/RelayClient", "isAuthenticated"), "Shared API still requires relay authentication");
        check(calls(method(readClass("dev/openintel/SnitchRelay"), "onGameMessage"),
                "dev/openintel/api/internal/ApiBridge", "relaySnitch"), "Forwarding-off path still publishes the local snitch event");
    }

    @SuppressWarnings("unchecked")
    private static void verifyActualStores() throws Exception {
        var tracker = new dev.openintel.tracker.Tracker();
        var field = tracker.getClass().getDeclaredField("snitchHits");
        field.setAccessible(true);
        var hits = (LocalRelayStore<String, Object>) field.get(tracker);
        var constructor = dev.openintel.tracker.Tracker.SnitchHit.class.getDeclaredConstructor(
                String.class, String.class, String.class, double.class, double.class, double.class, String.class, long.class);
        constructor.setAccessible(true);
        Object local = constructor.newInstance("Local", "Friend123", "self", 1d, 64d, 2d, "minecraft:overworld", 100L);
        Object remote = constructor.newInstance("Remote", "Friend123", "teammate", 99d, 64d, 99d, "minecraft:overworld", 200L);
        hits.putLocal("Friend123", local);
        hits.putRelay("Friend123", remote);
        var reset = tracker.getClass().getDeclaredMethod("clearData", boolean.class);
        reset.setAccessible(true);
        reset.invoke(tracker, false);
        check(tracker.snitchHits().iterator().next() == local, "Actual tracker restores local data through relay-only reset");
        reset.invoke(tracker, true);
        check(!tracker.snitchHits().iterator().hasNext(), "Actual tracker clears local data on world reset");
        var pingClass = dev.openintel.ping.PingManager.class;
        var pingsField = pingClass.getDeclaredField("pings");
        pingsField.setAccessible(true);
        var pings = (LocalRelayStore<String, Object>) pingsField.get(null);
        var pingConstructor = dev.openintel.ping.PingManager.Ping.class.getDeclaredConstructor(String.class, String.class, int.class, String.class);
        pingConstructor.setAccessible(true);
        Object ownPing = pingConstructor.newInstance("own", "Local", -1, "self");
        Object otherPing = pingConstructor.newInstance("other", "Remote", -1, "self");
        pings.putLocal("own", ownPing);
        pings.putRelay("other", otherPing);
        var clearPings = pingClass.getDeclaredMethod("clearData", boolean.class);
        clearPings.setAccessible(true);
        clearPings.invoke(null, false);
        check(pings.values().equals(java.util.List.of(ownPing)), "Only truly local pings survive, even when remote sender equals self");
        clearPings.invoke(null, true);
        check(pings.values().isEmpty(), "Actual ping manager clears all data on world reset");
    }

    private static void verifyFeedOwnership() throws Exception {
        var feed = Class.forName("dev.openintel.render.EventFeed");
        var entry = Class.forName("dev.openintel.render.EventFeed$Entry");
        var constructor = entry.getDeclaredConstructor(String.class, int.class, long.class, boolean.class);
        constructor.setAccessible(true);
        var entriesField = feed.getDeclaredField("entries");
        entriesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var entries = (java.util.Deque<Object>) entriesField.get(null);
        var reset = feed.getDeclaredMethod("clearData", boolean.class);
        reset.setAccessible(true);
        Object local = constructor.newInstance("local snitch", -1, 1L, false);
        Object remote = constructor.newInstance("relay-only notice", -1, 1L, true);
        entries.add(local);
        entries.add(remote);
        var inRenderField = feed.getDeclaredField("inRender");
        var deadField = feed.getDeclaredField("deadNotified");
        inRenderField.setAccessible(true);
        deadField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var inRender = (java.util.Set<String>) inRenderField.get(null);
        @SuppressWarnings("unchecked")
        var dead = (java.util.Set<String>) deadField.get(null);
        inRender.add("LocalPlayer");
        dead.add("LocalPlayer");
        reset.invoke(null, false);
        check(entries.size() == 1 && entries.peek() == local, "Relay feed entries clear while local entries stay");
        check(inRender.contains("LocalPlayer") && dead.contains("LocalPlayer"), "Relay reset preserves local event deduplication");
        reset.invoke(null, true);
        check(entries.isEmpty() && inRender.isEmpty() && dead.isEmpty(), "Full world cleanup clears remaining local feed and dedupe state");
        Object mirrored = constructor.newInstance("bridged ping", -1, 1000L, true);
        entries.add(mirrored);
        var coalesce = feed.getDeclaredMethod("coalesceRelay", String.class, int.class, long.class, String.class);
        coalesce.setAccessible(true);
        check((boolean) coalesce.invoke(null, "bridged ping", -1, 2000L, "second"), "Bridged feed copy coalesces");
        check(!(boolean) coalesce.invoke(null, "bridged ping", -1, 7000L, "second"), "Separate later alerts remain eligible");
        var clearSource = feed.getDeclaredMethod("clearRelayData", String.class);
        clearSource.setAccessible(true);
        clearSource.invoke(null, dev.openintel.net.RelaySource.LEGACY);
        check(entries.size() == 1, "Clearing first relay preserves coalesced source ownership");
        clearSource.invoke(null, "second");
        check(entries.isEmpty(), "Coalesced line clears after final source disappears");
    }

    private static ClassNode readClass(String name) throws Exception {
        var node = new ClassNode();
        try (var input = OfflineIntelTest.class.getResourceAsStream("/" + name + ".class")) {
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        }
        return false;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
