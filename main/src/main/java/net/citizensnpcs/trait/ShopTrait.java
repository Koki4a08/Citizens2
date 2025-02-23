package net.citizensnpcs.trait;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.StoredShops;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.ClickHandler;
import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InputMenus.Choice;
import net.citizensnpcs.api.gui.InventoryMenu;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuPattern;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.gui.MenuPattern;
import net.citizensnpcs.api.gui.MenuSlot;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.Persistable;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.trait.shop.CommandAction;
import net.citizensnpcs.trait.shop.CommandAction.CommandActionGUI;
import net.citizensnpcs.trait.shop.ExperienceAction;
import net.citizensnpcs.trait.shop.ExperienceAction.ExperienceActionGUI;
import net.citizensnpcs.trait.shop.ItemAction;
import net.citizensnpcs.trait.shop.ItemAction.ItemActionGUI;
import net.citizensnpcs.trait.shop.MoneyAction;
import net.citizensnpcs.trait.shop.MoneyAction.MoneyActionGUI;
import net.citizensnpcs.trait.shop.NPCShopAction;
import net.citizensnpcs.trait.shop.NPCShopAction.GUI;
import net.citizensnpcs.trait.shop.NPCShopAction.Transaction;
import net.citizensnpcs.trait.shop.PermissionAction;
import net.citizensnpcs.trait.shop.PermissionAction.PermissionActionGUI;
import net.citizensnpcs.util.Util;

/**
 * Shop trait for NPC GUI shops.
 */
@TraitName("shop")
public class ShopTrait extends Trait {
    @Persist
    private String rightClickShop;
    private StoredShops shops;

    public ShopTrait() {
        super("shop");
    }

    public ShopTrait(StoredShops shops) {
        this();
        this.shops = shops;
    }

    public NPCShop getDefaultShop() {
        return shops.npcShops.computeIfAbsent(npc.getUniqueId().toString(), NPCShop::new);
    }

    public NPCShop getShop(String name) {
        return shops.globalShops.computeIfAbsent(name, NPCShop::new);
    }

    @Override
    public void onRemove() {
        Messaging.debug("Removing", npc, "default shop due to onRemove");
        shops.deleteShop(getDefaultShop());
    }

    public void onRightClick(Player player) {
        if (rightClickShop == null || rightClickShop.isEmpty())
            return;
        if (!Setting.SHOP_GLOBAL_VIEW_PERMISSION.asString().isEmpty()
                && !player.hasPermission(Setting.SHOP_GLOBAL_VIEW_PERMISSION.asString()))
            return;

        NPCShop shop = shops.globalShops.getOrDefault(rightClickShop, getDefaultShop());
        shop.display(player);
    }

    public static class NPCShop {
        @Persist(value = "")
        private String name;
        @Persist(reify = true)
        private final List<NPCShopPage> pages = Lists.newArrayList();
        @Persist
        private String title;
        @Persist
        private ShopType type = ShopType.COMMAND;
        @Persist
        private String viewPermission;

        private NPCShop() {
        }

        public NPCShop(String name) {
            this.name = name;
        }

        public boolean canEdit(NPC npc, Player sender) {
            return sender.hasPermission("citizens.admin") || sender.hasPermission("citizens.npc.shop.edit")
                    || sender.hasPermission("citizens.npc.shop.edit." + getName())
                    || npc.getOrAddTrait(Owner.class).isOwnedBy(sender);
        }

        public void display(Player sender) {
            if (viewPermission != null && !sender.hasPermission(viewPermission))
                return;
            if (!Setting.SHOP_GLOBAL_VIEW_PERMISSION.asString().isEmpty()
                    && !sender.hasPermission(Setting.SHOP_GLOBAL_VIEW_PERMISSION.asString()))
                return;
            if (pages.size() == 0) {
                Messaging.sendError(sender, "Empty shop");
                return;
            }
            InventoryMenu.createSelfRegistered(new NPCShopViewer(this, sender)).present(sender);
        }

        public void displayEditor(ShopTrait trait, Player sender) {
            InventoryMenu.createSelfRegistered(new NPCShopSettings(trait, this)).present(sender);
        }

        public String getName() {
            return name == null ? "" : name;
        }

        public NPCShopPage getOrCreatePage(int page) {
            while (pages.size() <= page) {
                pages.add(new NPCShopPage(page));
            }
            return pages.get(page);
        }

        public String getRequiredPermission() {
            return viewPermission;
        }

        public void removePage(int index) {
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i).index == index) {
                    pages.remove(i--);
                    index = -1;
                } else if (index == -1) {
                    pages.get(i).index--;
                }
            }
        }

        public void setPermission(String permission) {
            this.viewPermission = permission;
            if (viewPermission != null && viewPermission.isEmpty()) {
                viewPermission = null;
            }
        }
    }

    @Menu(title = "NPC Shop Contents Editor", type = InventoryType.CHEST, dimensions = { 5, 9 })
    public static class NPCShopContentsEditor extends InventoryMenuPage {
        private NPCShopItem copying;
        private MenuContext ctx;
        private int page = 0;
        private final NPCShop shop;

        public NPCShopContentsEditor(NPCShop shop) {
            this.shop = shop;
        }

        public void changePage(int newPage) {
            this.page = newPage;
            ctx.setTitle("NPC Shop Contents Editor Page " + newPage);
            NPCShopPage shopPage = shop.getOrCreatePage(page);
            for (int i = 0; i < ctx.getInventory().getSize(); i++) {
                InventoryMenuSlot slot = ctx.getSlot(i);
                slot.clear();

                if (shopPage.getItem(i) != null) {
                    slot.setItemStack(shopPage.getItem(i).getDisplayItem(null));
                }

                final int idx = i;
                slot.setClickHandler(evt -> {
                    NPCShopItem display = shopPage.getItem(idx);
                    if (display != null && evt.isShiftClick() && evt.getCursorNonNull().getType() == Material.AIR
                            && display.display != null) {
                        copying = display.clone();
                        evt.setCursor(display.getDisplayItem(null));
                        evt.setCancelled(true);
                        return;
                    }

                    if (display == null) {
                        if (copying != null && evt.getCursorNonNull().getType() != Material.AIR
                                && evt.getCursorNonNull().equals(copying.getDisplayItem(null))) {
                            shopPage.setItem(idx, copying);
                            copying = null;
                            return;
                        }

                        display = new NPCShopItem();
                        if (evt.getCursor() != null) {
                            display.display = evt.getCursor().clone();
                        }
                    }

                    ctx.clearSlots();
                    ctx.getMenu().transition(new NPCShopItemEditor(display, modified -> {
                        if (modified == null) {
                            shopPage.removeItem(idx);
                        } else {
                            shopPage.setItem(idx, modified);
                        }
                    }));
                });
            }

            InventoryMenuSlot prev = ctx.getSlot(4 * 9 + 3);
            InventoryMenuSlot edit = ctx.getSlot(4 * 9 + 4);
            InventoryMenuSlot next = ctx.getSlot(4 * 9 + 5);
            if (page > 0) {
                prev.setItemStack(new ItemStack(Material.FEATHER, 1), "Previous page (" + (page) + ")");
                Consumer<CitizensInventoryClickEvent> prevItemEditor = prev.getClickHandlers().get(0);
                prev.setClickHandler(evt -> {
                    if (evt.isShiftClick()) {
                        prevItemEditor.accept(evt);
                        return;
                    }
                    evt.setCancelled(true);
                    changePage(page - 1);
                });
            }

            next.setItemStack(new ItemStack(Material.FEATHER, 1),
                    page + 1 >= shop.pages.size() ? "New page" : "Next page (" + (page + 1) + ")");
            Consumer<CitizensInventoryClickEvent> nextItemEditor = next.getClickHandlers().get(0);
            next.setClickHandler(evt -> {
                if (evt.isShiftClick()) {
                    nextItemEditor.accept(evt);
                    return;
                }
                evt.setCancelled(true);
                changePage(page + 1);
            });

            Consumer<CitizensInventoryClickEvent> editPageItem = edit.getClickHandlers().get(0);
            edit.setItemStack(new ItemStack(Material.BOOK), "Edit page");
            edit.setClickHandler(evt -> {
                if (evt.isShiftClick()) {
                    editPageItem.accept(evt);
                    return;
                }
                ctx.getMenu().transition(new NPCShopPageSettings(shop.getOrCreatePage(page)));
            });
        }

        @Override
        public void initialise(MenuContext ctx) {
            this.ctx = ctx;
            if (ctx.data().containsKey("removePage")) {
                int index = (int) ctx.data().remove("removePage");
                shop.removePage(index);
                page = Math.max(page - 1, 0);
            }
            changePage(page);
        }
    }

    public static class NPCShopItem implements Cloneable, Persistable {
        @Persist
        private String clickToConfirmMessage;
        @Persist
        private final List<NPCShopAction> cost = Lists.newArrayList();
        @Persist
        private String costMessage;
        @Persist
        private ItemStack display;
        @Persist
        private boolean maxRepeatsOnShiftClick;
        @Persist
        private final List<NPCShopAction> result = Lists.newArrayList();
        @Persist
        private String resultMessage;

        public List<Transaction> apply(List<NPCShopAction> actions, Function<NPCShopAction, Transaction> func) {
            List<Transaction> pending = Lists.newArrayList();
            for (NPCShopAction action : actions) {
                Transaction take = func.apply(action);
                if (!take.isPossible()) {
                    pending.forEach(a -> a.rollback());
                    return null;
                } else {
                    take.run();
                    pending.add(take);
                }
            }
            return pending;
        }

        private void changeAction(List<NPCShopAction> source, Function<NPCShopAction, Boolean> filter,
                NPCShopAction delta) {
            for (int i = 0; i < source.size(); i++) {
                if (filter.apply(source.get(i))) {
                    if (delta == null) {
                        source.remove(i);
                    } else {
                        source.set(i, delta);
                    }
                    return;
                }
            }
            if (delta != null) {
                source.add(delta);
            }
        }

        public void changeCost(Function<NPCShopAction, Boolean> filter, NPCShopAction cost) {
            changeAction(this.cost, filter, cost);
        }

        public void changeResult(Function<NPCShopAction, Boolean> filter, NPCShopAction result) {
            changeAction(this.result, filter, result);
        }

        @Override
        public NPCShopItem clone() {
            try {
                return (NPCShopItem) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new Error(e);
            }
        }

        public ItemStack getDisplayItem(Player player) {
            if (display == null)
                return null;
            ItemStack stack = display.clone();
            ItemMeta meta = stack.getItemMeta();
            if (meta.hasDisplayName()) {
                meta.setDisplayName(placeholders(meta.getDisplayName(), player));
            }
            if (!meta.hasLore()) {
                List<String> lore = Lists.newArrayList();
                cost.forEach(a -> lore.add(a.describe()));
                result.forEach(a -> lore.add(a.describe()));
            }
            if (meta.hasLore()) {
                meta.setLore(Lists.transform(meta.getLore(), line -> placeholders(line, player)));
            }
            stack.setItemMeta(meta);
            return stack;
        }

        @Override
        public void load(DataKey key) {
            if (key.keyExists("message")) {
                resultMessage = key.getString("message");
                key.removeKey("message");
            }
            if (key.keyExists("clickMessage")) {
                resultMessage = key.getString("clickMessage");
                key.removeKey("clickMessage");
            }
        }

        public void onClick(NPCShop shop, InventoryClickEvent event, boolean secondClick) {
            if (clickToConfirmMessage != null && !secondClick) {
                Messaging.sendColorless(event.getWhoClicked(),
                        placeholders(clickToConfirmMessage, (Player) event.getWhoClicked()));
                return;
            }
            int max = Integer.MAX_VALUE;
            if (maxRepeatsOnShiftClick && event.isShiftClick()) {
                for (NPCShopAction action : cost) {
                    int r = action.getMaxRepeats(event.getWhoClicked());
                    if (r != -1) {
                        max = Math.min(max, r);
                    }
                }
                if (max == 0)
                    return;
            }
            final int repeats = max == Integer.MAX_VALUE ? 1 : max;
            List<Transaction> take = apply(cost, action -> action.take(event.getWhoClicked(), repeats));
            if (take == null) {
                if (costMessage != null) {
                    Messaging.sendColorless(event.getWhoClicked(),
                            placeholders(costMessage, (Player) event.getWhoClicked()));
                }
                return;
            }
            if (apply(result, action -> action.grant(event.getWhoClicked(), repeats)) == null) {
                take.forEach(a -> a.rollback());
                return;
            }
            if (resultMessage != null) {
                Messaging.sendColorless(event.getWhoClicked(),
                        placeholders(resultMessage, (Player) event.getWhoClicked()));
            }
        }

        private String placeholders(String string, Player player) {
            string = Placeholders.replace(string, player);
            StringBuffer sb = new StringBuffer();
            Matcher matcher = PLACEHOLDER_REGEX.matcher(string);
            while (matcher.find()) {
                matcher.appendReplacement(sb, Joiner.on(", ").join(
                        Iterables.transform(matcher.group(1).equals("cost") ? cost : result, NPCShopAction::describe)));
            }
            matcher.appendTail(sb);
            return sb.toString();
        }

        @Override
        public void save(DataKey key) {
        }

        private static final Pattern PLACEHOLDER_REGEX = Pattern.compile("<(cost|result)>", Pattern.CASE_INSENSITIVE);
    }

    @Menu(title = "NPC Shop Item Editor", type = InventoryType.CHEST, dimensions = { 6, 9 })
    public static class NPCShopItemEditor extends InventoryMenuPage {
        @MenuPattern(
                offset = { 0, 6 },
                slots = { @MenuSlot(pat = 'x', material = Material.AIR) },
                value = "xxx\nxxx\nxxx")
        private InventoryMenuPattern actionItems;
        private NPCShopItem base;
        private final Consumer<NPCShopItem> callback;
        @MenuPattern(
                offset = { 0, 0 },
                slots = { @MenuSlot(pat = 'x', material = Material.AIR) },
                value = "xxx\nxxx\nxxx")
        private InventoryMenuPattern costItems;
        private MenuContext ctx;
        private final NPCShopItem modified;

        public NPCShopItemEditor(NPCShopItem item, Consumer<NPCShopItem> consumer) {
            this.base = item;
            this.modified = base.clone();
            this.callback = consumer;
        }

        @Override
        public void initialise(MenuContext ctx) {
            this.ctx = ctx;
            if (modified.display != null) {
                ctx.getSlot(9 * 4 + 4).setItemStack(modified.getDisplayItem(null));
            }
            ctx.getSlot(9 * 3 + 3).setItemStack(new ItemStack(Util.getFallbackMaterial("OAK_SIGN", "SIGN")),
                    "Set message to send on successful click, currently:\n",
                    modified.resultMessage == null ? "Unset" : modified.resultMessage);
            ctx.getSlot(9 * 3 + 3).setClickHandler(
                    e -> ctx.getMenu().transition(InputMenus.stringSetter(() -> modified.resultMessage, s -> {
                        modified.resultMessage = s;
                        ctx.getSlot(9 * 3 + 3).setDescription(modified.resultMessage);
                    })));

            ctx.getSlot(9 * 3 + 6).setItemStack(new ItemStack(Util.getFallbackMaterial("BARRIER", "FIRE")),
                    "Set message to send on unsuccessful click, currently:\n",
                    modified.costMessage == null ? "Unset" : modified.costMessage);
            ctx.getSlot(9 * 3 + 6).setClickHandler(
                    e -> ctx.getMenu().transition(InputMenus.stringSetter(() -> modified.costMessage, s -> {
                        modified.costMessage = s;
                        ctx.getSlot(9 * 3 + 6).setDescription(modified.costMessage);
                    })));

            ctx.getSlot(9 * 3 + 5).setItemStack(new ItemStack(Util.getFallbackMaterial("FEATHER", "SIGN")),
                    "Set click to confirm message.",
                    "For example, 'click again to buy this item'\nYou can use <cost> or <result> placeholders.\nCurrently:\n"
                            + (modified.clickToConfirmMessage == null ? "Unset" : modified.clickToConfirmMessage));
            ctx.getSlot(9 * 3 + 5).setClickHandler(
                    e -> ctx.getMenu().transition(InputMenus.stringSetter(() -> modified.clickToConfirmMessage, s -> {
                        modified.clickToConfirmMessage = s;
                        ctx.getSlot(9 * 3 + 5).setDescription(modified.clickToConfirmMessage);
                    })));

            ctx.getSlot(9 * 3 + 4).setItemStack(new ItemStack(Material.REDSTONE),
                    "Sell as many times as possible on shift click\n", "Currently: " + modified.maxRepeatsOnShiftClick);
            ctx.getSlot(9 * 3 + 4).setClickHandler(
                    InputMenus.toggler(res -> modified.maxRepeatsOnShiftClick = res, modified.maxRepeatsOnShiftClick));
            int pos = 0;
            for (GUI template : NPCShopAction.getGUIs()) {
                if (template.createMenuItem(null) == null)
                    continue;

                NPCShopAction oldCost = modified.cost.stream().filter(template::manages).findFirst().orElse(null);
                costItems.getSlots().get(pos)
                        .setItemStack(Util.editTitle(template.createMenuItem(oldCost), title -> title + " Cost"));
                costItems.getSlots().get(pos).setClickHandler(event -> ctx.getMenu().transition(
                        template.createEditor(oldCost, cost -> modified.changeCost(template::manages, cost))));

                NPCShopAction oldResult = modified.result.stream().filter(template::manages).findFirst().orElse(null);
                actionItems.getSlots().get(pos)
                        .setItemStack(Util.editTitle(template.createMenuItem(oldResult), title -> title + " Result"));
                actionItems.getSlots().get(pos).setClickHandler(event -> ctx.getMenu().transition(
                        template.createEditor(oldResult, result -> modified.changeResult(template::manages, result))));

                pos++;
            }
        }

        @MenuSlot(slot = { 5, 3 }, material = Material.REDSTONE_BLOCK, amount = 1, title = "<7>Cancel")
        public void onCancel(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            ctx.getMenu().transitionBack();
        }

        @Override
        public void onClose(HumanEntity who) {
            if (base != null && base.display == null) {
                base = null;
            }
            callback.accept(base);
        }

        @MenuSlot(slot = { 4, 5 }, material = Material.BOOK, amount = 1, title = "<f>Set description")
        public void onEditDescription(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            event.setCancelled(true);
            if (modified.display == null)
                return;
            ctx.getMenu()
                    .transition(InputMenus.stringSetter(() -> modified.display.getItemMeta().hasLore()
                            ? Joiner.on("<br>").skipNulls().join(modified.display.getItemMeta().getLore())
                            : "", description -> {
                                ItemMeta meta = modified.display.getItemMeta();
                                meta.setLore(Lists.newArrayList(
                                        Splitter.on("<br>").split(Messaging.parseComponents(description))));
                                modified.display.setItemMeta(meta);
                            }));
        }

        @MenuSlot(slot = { 4, 3 }, material = Material.NAME_TAG, amount = 1, title = "<f>Set name")
        public void onEditName(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            event.setCancelled(true);
            if (modified.display == null)
                return;
            ctx.getMenu().transition(InputMenus.stringSetter(modified.display.getItemMeta()::getDisplayName, name -> {
                ItemMeta meta = modified.display.getItemMeta();
                meta.setDisplayName(ChatColor.RESET + Messaging.parseComponents(name));
                modified.display.setItemMeta(meta);
            }));
        }

        @ClickHandler(slot = { 4, 4 })
        public void onModifyDisplayItem(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            event.setCancelled(true);
            if (event.getCursor() != null) {
                event.setCurrentItem(event.getCursor());
                modified.display = event.getCursor().clone();
            } else {
                event.setCurrentItem(null);
                modified.display = null;
            }
        }

        @MenuSlot(slot = { 5, 4 }, material = Material.TNT, amount = 1, title = "<c>Remove")
        public void onRemove(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            base = null;
            ctx.getMenu().transitionBack();
        }

        @MenuSlot(slot = { 5, 5 }, material = Material.EMERALD_BLOCK, amount = 1, title = "<a>Save")
        public void onSave(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            base = modified;
            ctx.getMenu().transitionBack();
        }
    }

    public static class NPCShopPage {
        @Persist("$key")
        private int index;
        @Persist(keyType = Integer.class, reify = true)
        private final Map<Integer, NPCShopItem> items = Maps.newHashMap();
        @Persist
        private String title;

        private NPCShopPage() {
        }

        public NPCShopPage(int page) {
            this.index = page;
        }

        public NPCShopItem getItem(int idx) {
            return items.get(idx);
        }

        public void removeItem(int idx) {
            items.remove(idx);
        }

        public void setItem(int idx, NPCShopItem modified) {
            items.put(idx, modified);
        }
    }

    @Menu(title = "NPC Shop Page Editor", type = InventoryType.CHEST, dimensions = { 5, 9 })
    public static class NPCShopPageSettings extends InventoryMenuPage {
        private MenuContext ctx;
        private final NPCShopPage page;

        public NPCShopPageSettings(NPCShopPage page) {
            this.page = page;
        }

        @MenuSlot(slot = { 0, 4 }, material = Material.FEATHER, amount = 1)
        public void editPageTitle(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            ctx.getMenu().transition(InputMenus.stringSetter(() -> page.title,
                    newTitle -> page.title = newTitle.isEmpty() ? null : newTitle));
        }

        @Override
        public void initialise(MenuContext ctx) {
            this.ctx = ctx;
            ctx.getSlot(4).setDescription("Set page title<br>Currently: " + page.title);
        }

        @MenuSlot(slot = { 4, 4 }, material = Material.TNT, amount = 1, title = "<c>Remove page")
        public void removePage(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            ctx.data().put("removePage", page.index);
            ctx.getMenu().transitionBack();
        }
    }

    @Menu(title = "NPC Shop Editor", type = InventoryType.CHEST, dimensions = { 1, 9 })
    public static class NPCShopSettings extends InventoryMenuPage {
        private MenuContext ctx;
        private final NPCShop shop;
        private final ShopTrait trait;

        public NPCShopSettings(ShopTrait trait, NPCShop shop) {
            this.trait = trait;
            this.shop = shop;
        }

        @Override
        public void initialise(MenuContext ctx) {
            this.ctx = ctx;
            ctx.getSlot(2).setDescription("<f>Edit shop view permission<br>" + shop.getRequiredPermission());
            ctx.getSlot(6).setDescription("<f>Edit shop title<br>" + shop.title);
            if (trait != null) {
                ctx.getSlot(8).setDescription(
                        "<f>Show shop on right click<br>" + shop.getName().equals(trait.rightClickShop));
            }
        }

        @MenuSlot(slot = { 0, 4 }, material = Material.FEATHER, amount = 1, title = "<f>Edit shop items")
        public void onEditItems(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            ctx.getMenu().transition(new NPCShopContentsEditor(shop));
        }

        @MenuSlot(slot = { 0, 2 }, compatMaterial = { "OAK_SIGN", "SIGN" }, amount = 1)
        public void onPermissionChange(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            ctx.getMenu().transition(InputMenus.stringSetter(shop::getRequiredPermission, shop::setPermission));
        }

        @MenuSlot(slot = { 0, 6 }, material = Material.NAME_TAG, amount = 1)
        public void onSetTitle(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            ctx.getMenu().transition(InputMenus.stringSetter(() -> shop.title, newTitle -> shop.title = newTitle));
        }

        @MenuSlot(slot = { 0, 0 }, material = Material.BOOK, amount = 1, title = "<f>Edit shop type")
        public void onShopTypeChange(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            ctx.getMenu().transition(InputMenus.<ShopType> picker("Edit shop type",
                    chosen -> shop.type = chosen.getValue(),
                    Choice.<ShopType> of(ShopType.BUY, Material.DIAMOND, "Players buy items",
                            shop.type == ShopType.BUY),
                    Choice.of(ShopType.SELL, Material.EMERALD, "Players sell items", shop.type == ShopType.SELL),
                    Choice.of(ShopType.COMMAND, Util.getFallbackMaterial("ENDER_EYE", "ENDER_PEARL"),
                            "Clicks trigger commands only", shop.type == ShopType.COMMAND)));
        }

        @MenuSlot(slot = { 0, 8 }, compatMaterial = { "COMMAND_BLOCK", "COMMAND" }, amount = 1)
        public void onToggleRightClick(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            event.setCancelled(true);
            if (trait == null)
                return;
            if (shop.getName().equals(trait.rightClickShop)) {
                trait.rightClickShop = null;
            } else {
                trait.rightClickShop = shop.name;
            }
            ctx.getSlot(8)
                    .setDescription("<f>Show shop on right click<br>" + (shop.getName().equals(trait.rightClickShop)));
        }
    }

    @Menu(title = "Shop", type = InventoryType.CHEST, dimensions = { 5, 9 })
    public static class NPCShopViewer extends InventoryMenuPage {
        private MenuContext ctx;
        private int currentPage = 0;
        private NPCShopItem lastClickedItem;
        private final Player player;
        private final NPCShop shop;

        public NPCShopViewer(NPCShop shop, Player player) {
            this.shop = shop;
            this.player = player;
        }

        public void changePage(int newPage) {
            this.currentPage = newPage;
            NPCShopPage page = shop.pages.get(currentPage);
            if (page.title != null && !page.title.isEmpty()) {
                Bukkit.getScheduler().runTaskLater(CitizensAPI.getPlugin(), () -> {
                    ctx.setTitle(Placeholders.replace(page.title, player));
                }, 1);
            }
            for (int i = 0; i < ctx.getInventory().getSize(); i++) {
                ctx.getSlot(i).clear();
                NPCShopItem item = page.getItem(i);
                if (item == null)
                    continue;

                ctx.getSlot(i).setItemStack(item.getDisplayItem(player));
                ctx.getSlot(i).setClickHandler(evt -> {
                    evt.setCancelled(true);
                    item.onClick(shop, evt, lastClickedItem == item);
                    lastClickedItem = item;
                });
            }

            InventoryMenuSlot prev = ctx.getSlot(4 * 9 + 3);
            InventoryMenuSlot next = ctx.getSlot(4 * 9 + 5);
            if (currentPage > 0) {
                prev.clear();
                prev.setItemStack(new ItemStack(Material.FEATHER, 1), "Previous page (" + (currentPage) + ")");
                prev.setClickHandler(evt -> {
                    evt.setCancelled(true);
                    changePage(currentPage - 1);
                });
            }

            if (currentPage + 1 < shop.pages.size()) {
                next.clear();
                next.setItemStack(new ItemStack(Material.FEATHER, 1), "Next page (" + (currentPage + 1) + ")");
                next.setClickHandler(evt -> {
                    evt.setCancelled(true);
                    changePage(currentPage + 1);
                });
            }
        }

        @Override
        public Inventory createInventory(String title) {
            return Bukkit.createInventory(null, 45,
                    shop.title == null || shop.title.isEmpty() ? "Shop" : Placeholders.replace(shop.title, player));
        }

        @Override
        public void initialise(MenuContext ctx) {
            this.ctx = ctx;
            changePage(currentPage);
        }
    }

    public enum ShopType {
        BUY,
        COMMAND,
        SELL;
    }

    static {
        NPCShopAction.register(ItemAction.class, "items", new ItemActionGUI());
        NPCShopAction.register(PermissionAction.class, "permissions", new PermissionActionGUI());
        NPCShopAction.register(MoneyAction.class, "money", new MoneyActionGUI());
        NPCShopAction.register(CommandAction.class, "command", new CommandActionGUI());
        NPCShopAction.register(ExperienceAction.class, "experience", new ExperienceActionGUI());
    }
}