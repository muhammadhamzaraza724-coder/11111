package com.armormaking;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Every Minecraft call that is likely to change between versions lives here.
 * If the build fails with "cannot find symbol", the fix is almost always in this file only.
 */
final class Compat {
    private Compat() {}

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // ---------- keybind ----------
    static KeyMapping registerKey(String name, int glfwKey) {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("armormaking", "main"));
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, glfwKey, cat));
    }

    // ---------- chat / ui ----------
    static void chat(String msg) {
        Minecraft m = mc();
        m.execute(() -> {
            if (m.player != null) {
                m.player.displayClientMessage(Component.literal("\u00a7b[ArmorMaker] \u00a7f" + msg), false);
            }
        });
    }

    static void closeMenuAsync() {
        Minecraft m = mc();
        m.execute(() -> {
            if (m.player != null && m.player.containerMenu != m.player.inventoryMenu) {
                m.player.closeContainer();
            }
        });
    }

    // ---------- container clicks ----------
    static void click(AbstractContainerMenu menu, int slot, int button, ClickType type) {
        mc().gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc().player);
    }

    static void button(AbstractContainerMenu menu, int id) {
        mc().gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    // ---------- world interaction ----------
    static void useBlock(BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, hit);
    }

    static void useEntity(int id) {
        Entity e = mc().level.getEntity(id);
        if (e != null) {
            mc().gameMode.interact(mc().player, e, InteractionHand.MAIN_HAND);
        }
    }

    /** Closest matching block within normal reach (~4.5 blocks), or null. */
    static BlockPos findBlock(Predicate<BlockState> match) {
        Player p = mc().player;
        Vec3 eye = p.getEyePosition();
        BlockPos o = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(o.offset(-5, -4, -5), o.offset(5, 4, 5))) {
            if (!match.test(mc().level.getBlockState(pos))) continue;
            double d = Vec3.atCenterOf(pos).distanceToSqr(eye);
            if (d <= 20.0 && d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** Entity ids of villagers / wandering traders within the 3-block interaction reach, nearest first. */
    static List<Integer> merchantIds() {
        Player p = mc().player;
        Vec3 eye = p.getEyePosition();
        List<Entity> all = mc().level.getEntities((Entity) null, p.getBoundingBox().inflate(6.0), e -> e instanceof Merchant && e != p);
        List<Entity> near = new ArrayList<>();
        for (Entity e : all) {
            if (e.getBoundingBox().distanceToSqr(eye) <= 9.0) near.add(e);
        }
        near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        List<Integer> ids = new ArrayList<>();
        for (Entity e : near) ids.add(e.getId());
        return ids;
    }

    // ---------- trading ----------
    static void selectTrade(MerchantMenu m, int index) {
        m.setSelectionHint(index);
        m.tryMoveItems(index);
        mc().getConnection().send(new ServerboundSelectTradePacket(index));
    }

    // ---------- items ----------
    /** Enchant level if stack is an enchanted book holding exactly one enchantment == key, else 0. */
    static int bookLevel(ItemStack s, ResourceKey<Enchantment> key) {
        if (s.isEmpty() || !s.is(Items.ENCHANTED_BOOK)) return 0;
        ItemEnchantments e = s.get(DataComponents.STORED_ENCHANTMENTS);
        if (e == null || e.size() != 1) return 0;
        for (Holder<Enchantment> h : e.keySet()) {
            if (h.is(key)) return e.getLevel(h);
        }
        return 0;
    }

    static int countSame(ItemStack need) {
        Inventory inv = mc().player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItem(s, need)) n += s.getCount();
        }
        return n;
    }

    static boolean hasStack(ItemStack need) {
        return need.isEmpty() || countSame(need) >= need.getCount();
    }

    static String name(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    // ================= helpers used by ArmorMakingMod =================
    static final int KEY_K = 75; // GLFW_KEY_K

    /** Item id without namespace, e.g. "iron_helmet". */
    static String path(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    // ---------- villager offers ----------
    static List<MerchantOffer> offers(MerchantMenu m) {
        return new ArrayList<>(m.getOffers());
    }
    static ItemStack offerResult(MerchantOffer o) { return o.getResult(); }
    static ItemStack offerCostA(MerchantOffer o) { return o.getCostA(); }
    static ItemStack offerCostB(MerchantOffer o) { return o.getCostB(); }
    static boolean offerSoldOut(MerchantOffer o) { return o.isOutOfStock(); }

    // ---------- blocks / anvil ----------
    static boolean isCraftingTable(BlockState s) { return s.is(Blocks.CRAFTING_TABLE); }
    static boolean isAnvil(BlockState s) { return s.is(BlockTags.ANVIL); }
    static int anvilCost(AnvilMenu m) { return m.getCost(); }
    static boolean canAfford(int levels) {
        Player p = mc().player;
        return p.isCreative() || p.experienceLevel >= levels;
    }

    /** Level of an enchantment on a normal (non-book) item, 0 if none. */
    static int gearLevel(ItemStack s, ResourceKey<Enchantment> key) {
        if (s.isEmpty()) return 0;
        ItemEnchantments e = s.get(DataComponents.ENCHANTMENTS);
        if (e == null) return 0;
        for (Holder<Enchantment> h : e.keySet()) {
            if (h.is(key)) return e.getLevel(h);
        }
        return 0;
    }

    // ---------- what to enchant ----------
    record Want(String name, ResourceKey<Enchantment> key, int level) {}

    static List<Want> wants(String p) {
        List<Want> w = new ArrayList<>();
        if (p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings") || p.endsWith("_boots")) {
            w.add(new Want("protection", Enchantments.PROTECTION, 4));
        } else if (p.endsWith("_sword") || p.endsWith("_spear")) {
            w.add(new Want("sharpness", Enchantments.SHARPNESS, 5));
        } else if (p.endsWith("_pickaxe") || p.endsWith("_axe") || p.endsWith("_shovel") || p.endsWith("_hoe")) {
            w.add(new Want("efficiency", Enchantments.EFFICIENCY, 5));
        } else {
            return w; // not gear
        }
        w.add(new Want("unbreaking", Enchantments.UNBREAKING, 3));
        w.add(new Want("mending", Enchantments.MENDING, 1));
        return w;
    }

    /** Every enchanted book the trader step is allowed to buy. */
    static List<Want> allWants() {
        return List.of(
            new Want("protection", Enchantments.PROTECTION, 4),
            new Want("sharpness", Enchantments.SHARPNESS, 5),
            new Want("efficiency", Enchantments.EFFICIENCY, 5),
            new Want("unbreaking", Enchantments.UNBREAKING, 3),
            new Want("mending", Enchantments.MENDING, 1));
    }

    // ---------- crafting recipes (3x3, row-major, null = empty) ----------
    record Recipe(Item result, Item[] grid) {}

    static List<Recipe> recipes() {
        Item i = Items.IRON_INGOT, s = Items.STICK;
        return List.of(
            new Recipe(Items.IRON_SWORD,   new Item[]{null, i, null,  null, i, null,  null, s, null}),
            new Recipe(Items.IRON_PICKAXE, new Item[]{i, i, i,  null, s, null,  null, s, null}),
            new Recipe(Items.IRON_AXE,     new Item[]{i, i, null,  i, s, null,  null, s, null}),
            new Recipe(Items.IRON_SHOVEL,  new Item[]{null, i, null,  null, s, null,  null, s, null}),
            new Recipe(Items.IRON_HOE,     new Item[]{i, i, null,  null, s, null,  null, s, null}),
            // spear: 1 ingot + 2 sticks on a diagonal. Delete this line if your version's recipe differs.
            new Recipe(Items.IRON_SPEAR,   new Item[]{null, null, i,  null, s, null,  s, null, null}));
    }
}
