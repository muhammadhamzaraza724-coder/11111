package com.armormaking;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Press K: (1) buy armor + wanted enchanted books from nearby villagers,
 * (2) craft missing iron tools / spear at a nearby crafting table,
 * (3) combine books onto gear at a nearby anvil. Repeats until nothing changes.
 */
public class ArmorMakingMod implements ClientModInitializer {
    private static final int DELAY = 3;        // ticks between actions
    private static final int MAX_CYCLES = 5;
    private static final int MAX_ANVIL_JOBS = 40;

    private enum Stage { TRADE, CRAFT, ANVIL }
    private enum Phase { OPEN, WAIT_MENU, WORK }
    private record Job(int gear, int book, String tag) {}

    private KeyMapping toggleKey;
    private boolean running;
    private Stage stage = Stage.TRADE;
    private Phase phase = Phase.OPEN;
    private int wait, timeout, tries, cycle, anvilJobs;
    private boolean progressed;
    private final Deque<Runnable> queue = new ArrayDeque<>();

    // trade state
    private final Set<Integer> visited = new HashSet<>();
    private List<MerchantOffer> offers = List.of();
    private int tIdx;

    // anvil state
    private final Set<String> failed = new HashSet<>();

    @Override
    public void onInitializeClient() {
        toggleKey = Compat.registerKey("key.armormaking.toggle", Compat.KEY_K);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ================= main loop =================
    private void onTick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            if (running) stop("Stopped.");
            else start();
        }
        if (!running) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            running = false;
            return;
        }
        if (wait > 0) { wait--; return; }
        if (!queue.isEmpty()) {
            queue.poll().run();
            wait = DELAY;
            return;
        }
        switch (stage) {
            case TRADE -> tradeTick(mc);
            case CRAFT -> craftTick(mc);
            case ANVIL -> anvilTick(mc);
        }
    }

    private void start() {
        running = true;
        cycle = 0;
        progressed = false;
        failed.clear();
        anvilJobs = 0;
        stage = Stage.TRADE;
        beginStage();
        Compat.chat("Started. Press K again to stop.");
    }

    private void stop(String msg) {
        running = false;
        queue.clear();
        Compat.closeMenuAsync();
        Compat.chat(msg);
    }

    private void beginStage() {
        phase = Phase.OPEN;
        timeout = 0;
        tries = 0;
        visited.clear();
    }

    private void nextStage() {
        Compat.closeMenuAsync();
        queue.clear();
        Stage[] all = Stage.values();
        if (stage.ordinal() + 1 < all.length) {
            stage = all[stage.ordinal() + 1];
        } else {
            cycle++;
            if (!progressed || cycle >= MAX_CYCLES) {
                stop("Done.");
                return;
            }
            progressed = false;
            stage = Stage.TRADE;
        }
        beginStage();
        wait = DELAY * 4;
    }

    // ================= stage 1: villagers =================
    private void tradeTick(Minecraft mc) {
        switch (phase) {
            case OPEN -> {
                Integer id = null;
                for (int cand : Compat.merchantIds()) {
                    if (!visited.contains(cand)) { id = cand; break; }
                }
                if (id == null) {
                    if (visited.isEmpty()) Compat.chat("No villager within reach - skipping trading.");
                    nextStage();
                    return;
                }
                visited.add(id);
                Compat.useEntity(id);
                phase = Phase.WAIT_MENU;
                timeout = 0;
            }
            case WAIT_MENU -> {
                if (mc.player.containerMenu instanceof MerchantMenu m) {
                    offers = Compat.offers(m);
                    tIdx = 0;
                    tries = 0;
                    phase = Phase.WORK;
                } else if (++timeout > 30) {
                    phase = Phase.OPEN; // skip this villager
                }
            }
            case WORK -> {
                if (!(mc.player.containerMenu instanceof MerchantMenu m)) {
                    phase = Phase.OPEN;
                    return;
                }
                int before = tIdx;
                while (tIdx < offers.size() && !wantTrade(offers.get(tIdx))) tIdx++;
                if (tIdx != before) tries = 0;
                if (tIdx >= offers.size()) {
                    Compat.closeMenuAsync();
                    phase = Phase.OPEN;
                    wait = DELAY * 3;
                    return;
                }
                if (++tries > 3) { tIdx++; tries = 0; return; }
                MerchantOffer offer = offers.get(tIdx);
                Compat.selectTrade(m, tIdx);
                queue.add(() -> {
                    if (mc.player.containerMenu instanceof MerchantMenu mm && !mm.getSlot(2).getItem().isEmpty()) {
                        ItemStack got = mm.getSlot(2).getItem();
                        Compat.click(mm, 2, 0, ClickType.QUICK_MOVE);
                        progressed = true;
                        Compat.chat("Bought: " + got.getHoverName().getString());
                    }
                });
            }
        }
    }

    private boolean wantTrade(MerchantOffer o) {
        if (Compat.offerSoldOut(o)) return false;
        if (!Compat.hasStack(Compat.offerCostA(o)) || !Compat.hasStack(Compat.offerCostB(o))) return false;
        ItemStack r = Compat.offerResult(o);
        String p = Compat.path(r);
        if (isArmor(p)) {
            return (p.startsWith("iron_") || p.startsWith("diamond_") || p.startsWith("netherite_"))
                    && Compat.countSame(r) == 0;
        }
        for (Compat.Want w : Compat.allWants()) {
            if (Compat.bookLevel(r, w.key()) >= w.level() && !haveBook(w)) return true;
        }
        return false;
    }

    private static boolean isArmor(String p) {
        return p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings") || p.endsWith("_boots");
    }

    private static boolean haveBook(Compat.Want w) {
        Inventory inv = Compat.mc().player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (Compat.bookLevel(inv.getItem(i), w.key()) >= w.level()) return true;
        }
        return false;
    }

    // ================= stage 2: crafting =================
    private void craftTick(Minecraft mc) {
        switch (phase) {
            case OPEN -> {
                if (pickRecipe() == null) { nextStage(); return; }
                BlockPos pos = Compat.findBlock(Compat::isCraftingTable);
                if (pos == null) {
                    Compat.chat("No crafting table within reach - skipping crafting.");
                    nextStage();
                    return;
                }
                Compat.useBlock(pos);
                phase = Phase.WAIT_MENU;
                timeout = 0;
            }
            case WAIT_MENU -> {
                if (mc.player.containerMenu instanceof CraftingMenu) {
                    phase = Phase.WORK;
                } else if (++timeout > 30) {
                    Compat.chat("Could not open the crafting table.");
                    nextStage();
                }
            }
            case WORK -> {
                if (!(mc.player.containerMenu instanceof CraftingMenu m)) { nextStage(); return; }
                Compat.Recipe r = pickRecipe();
                if (r == null || ++tries > 12) { nextStage(); return; }
                queueCraft(m, r);
            }
        }
    }

    private Compat.Recipe pickRecipe() {
        for (Compat.Recipe r : Compat.recipes()) {
            if (Compat.countSame(new ItemStack(r.result())) > 0) continue;
            Map<Item, Integer> need = new HashMap<>();
            for (Item it : r.grid()) {
                if (it != null) need.merge(it, 1, Integer::sum);
            }
            boolean ok = true;
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                if (Compat.countSame(new ItemStack(e.getKey())) < e.getValue()) { ok = false; break; }
            }
            if (ok) return r;
        }
        return null;
    }

    /** CraftingMenu slots: 0 = result, 1-9 = grid, 10-45 = player inventory. */
    private void queueCraft(CraftingMenu m, Compat.Recipe r) {
        for (int cell = 0; cell < 9; cell++) {
            Item need = r.grid()[cell];
            if (need == null) continue;
            int g = 1 + cell;
            int[] src = {-1};
            queue.add(() -> {
                src[0] = findSlot(m, 10, 46, need);
                if (src[0] >= 0) Compat.click(m, src[0], 0, ClickType.PICKUP);   // pick up stack
            });
            queue.add(() -> { if (src[0] >= 0) Compat.click(m, g, 1, ClickType.PICKUP); });   // place ONE
            queue.add(() -> { if (src[0] >= 0) Compat.click(m, src[0], 0, ClickType.PICKUP); }); // put rest back
        }
        queue.add(() -> {});  // let the server send the result
        queue.add(() -> {
            if (!m.getSlot(0).getItem().isEmpty()) {
                Compat.click(m, 0, 0, ClickType.QUICK_MOVE);
                progressed = true;
                Compat.chat("Crafted: " + Compat.name(r.result()));
            }
        });
        for (int g = 1; g <= 9; g++) {                                           // clear leftovers
            int slot = g;
            queue.add(() -> {
                if (!m.getSlot(slot).getItem().isEmpty()) Compat.click(m, slot, 0, ClickType.QUICK_MOVE);
            });
        }
    }

    private static int findSlot(AbstractContainerMenu m, int from, int to, Item item) {
        for (int i = from; i < to; i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }

    // ================= stage 3: anvil =================
    private void anvilTick(Minecraft mc) {
        switch (phase) {
            case OPEN -> {
                BlockPos pos = Compat.findBlock(Compat::isAnvil);
                if (pos == null) {
                    Compat.chat("No anvil within reach - skipping enchanting.");
                    nextStage();
                    return;
                }
                Compat.useBlock(pos);
                phase = Phase.WAIT_MENU;
                timeout = 0;
            }
            case WAIT_MENU -> {
                if (mc.player.containerMenu instanceof AnvilMenu) {
                    phase = Phase.WORK;
                } else if (++timeout > 30) {
                    Compat.chat("Could not open the anvil.");
                    nextStage();
                }
            }
            case WORK -> {
                if (!(mc.player.containerMenu instanceof AnvilMenu m)) { nextStage(); return; }
                Job job = findAnvilJob(m);
                if (job == null || ++anvilJobs > MAX_ANVIL_JOBS) { nextStage(); return; }
                queueAnvil(m, job);
            }
        }
    }

    /** AnvilMenu slots: 0/1 = inputs, 2 = result, 3-38 = player inventory. */
    private Job findAnvilJob(AnvilMenu m) {
        for (int gs = 3; gs < 39; gs++) {
            ItemStack gear = m.getSlot(gs).getItem();
            if (gear.isEmpty()) continue;
            String p = Compat.path(gear);
            for (Compat.Want w : Compat.wants(p)) {
                int have = Compat.gearLevel(gear, w.key());
                if (have >= w.level()) continue;
                String tag = p + ":" + w.name();
                if (failed.contains(tag)) continue;
                int bookSlot = -1, best = 0;
                for (int s = 3; s < 39; s++) {
                    int lvl = Compat.bookLevel(m.getSlot(s).getItem(), w.key());
                    if (lvl > best && lvl > have) { best = lvl; bookSlot = s; }
                }
                if (bookSlot >= 0) return new Job(gs, bookSlot, tag);
            }
        }
        return null;
    }

    private void queueAnvil(AnvilMenu m, Job job) {
        queue.add(() -> Compat.click(m, job.gear(), 0, ClickType.PICKUP));
        queue.add(() -> Compat.click(m, 0, 0, ClickType.PICKUP));
        queue.add(() -> Compat.click(m, job.book(), 0, ClickType.PICKUP));
        queue.add(() -> Compat.click(m, 1, 0, ClickType.PICKUP));
        queue.add(() -> {});  // wait for the server to compute the result
        queue.add(() -> {
            ItemStack out = m.getSlot(2).getItem();
            if (out.isEmpty()) {
                failed.add(job.tag());
                Compat.chat("Cannot combine (" + job.tag() + ") - skipped.");
            } else if (!Compat.canAfford(Compat.anvilCost(m))) {
                failed.add(job.tag());
                Compat.chat("Not enough XP levels for " + job.tag() + " (needs " + Compat.anvilCost(m) + ").");
            } else {
                Compat.click(m, 2, 0, ClickType.QUICK_MOVE);
                progressed = true;
                Compat.chat("Enchanted: " + job.tag());
            }
        });
        queue.add(() -> { if (!m.getSlot(0).getItem().isEmpty()) Compat.click(m, 0, 0, ClickType.QUICK_MOVE); });
        queue.add(() -> { if (!m.getSlot(1).getItem().isEmpty()) Compat.click(m, 1, 0, ClickType.QUICK_MOVE); });
    }
}
