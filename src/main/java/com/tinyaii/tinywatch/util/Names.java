package com.tinyaii.tinywatch.util;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 材质中文名。
 *
 * <p>Bukkit 只给 {@code Material.name()}（英文常量名），服务端拿不到客户端的中文语言文件，
 * 所以要自己维护一张表。没收录的回退成「去下划线 + 首字母大写」的英文，
 * 保证永远不会显示成 {@code DIAMOND_PICKAXE} 这种原始常量名。
 */
public final class Names {

    private static final Map<String, String> CN = new HashMap<String, String>();

    private Names() {
    }

    private static void put(String key, String cn) {
        CN.put(key, cn);
    }

    static {
        // ---- 容器 / 工作台 ----
        put("CHEST", "箱子");
        put("TRAPPED_CHEST", "陷阱箱");
        put("ENDER_CHEST", "末影箱");
        put("BARREL", "木桶");
        put("FURNACE", "熔炉");
        put("BLAST_FURNACE", "高炉");
        put("SMOKER", "烟熏炉");
        put("HOPPER", "漏斗");
        put("DISPENSER", "发射器");
        put("DROPPER", "投掷器");
        put("BREWING_STAND", "酿造台");
        put("ENCHANTING_TABLE", "附魔台");
        put("ANVIL", "铁砧");
        put("CHIPPED_ANVIL", "开裂的铁砧");
        put("DAMAGED_ANVIL", "损坏的铁砧");
        put("BEACON", "信标");
        put("CRAFTING_TABLE", "工作台");
        put("WORKBENCH", "工作台");
        put("SHULKER_BOX", "潜影盒");
        put("MERCHANT", "村民交易");

        // ---- 单字物品（组合规则拼不出来的）----
        put("DIAMOND", "钻石");
        put("EMERALD", "绿宝石");
        put("COAL", "煤炭");
        put("CHARCOAL", "木炭");
        put("BREAD", "面包");
        put("APPLE", "苹果");
        put("STICK", "木棍");
        put("STRING", "线");
        put("PAPER", "纸");
        put("BOOK", "书");
        put("TORCH", "火把");
        put("ARROW", "箭");
        put("BOW", "弓");
        put("SHIELD", "盾牌");
        put("WHEAT", "小麦");
        put("SUGAR", "糖");
        put("EGG", "鸡蛋");
        put("BUCKET", "桶");
        put("ELYTRA", "鞘翅");
        put("TOTEM_OF_UNDYING", "不死图腾");
        put("COOKED_BEEF", "熟牛肉");
        put("BEEF", "生牛肉");
        put("PORKCHOP", "生猪排");
        put("COOKED_PORKCHOP", "熟猪排");
        put("CHICKEN", "生鸡肉");
        put("COOKED_CHICKEN", "熟鸡肉");
        put("SLIME_BALL", "黏液球");
        put("GUNPOWDER", "火药");
        put("GLOWSTONE_DUST", "萤石粉");
        put("EXPERIENCE_BOTTLE", "附魔之瓶");

        // ---- 常见方块 ----
        put("STONE", "石头");
        put("COBBLESTONE", "圆石");
        put("DIRT", "泥土");
        put("GRASS_BLOCK", "草方块");
        put("GRASS", "草");
        put("SAND", "沙子");
        put("GRAVEL", "砂砾");
        put("CLAY", "黏土块");
        put("NETHERRACK", "下界岩");
        put("END_STONE", "末地石");
        put("OBSIDIAN", "黑曜石");
        put("BEDROCK", "基岩");

        // ---- 原木 / 木板（1.13 起是 OAK_LOG 这种新命名）----
        put("OAK_LOG", "橡木原木");
        put("SPRUCE_LOG", "云杉原木");
        put("BIRCH_LOG", "白桦原木");
        put("JUNGLE_LOG", "丛林原木");
        put("ACACIA_LOG", "金合欢原木");
        put("DARK_OAK_LOG", "深色橡木原木");
        put("LOG", "原木");
        put("OAK_PLANKS", "橡木木板");
        put("SPRUCE_PLANKS", "云杉木板");
        put("BIRCH_PLANKS", "白桦木板");
        put("JUNGLE_PLANKS", "丛林木板");
        put("ACACIA_PLANKS", "金合欢木板");
        put("DARK_OAK_PLANKS", "深色橡木木板");
        put("WOOD", "木板");
        put("OAK_LEAVES", "橡木树叶");
        put("LEAVES", "树叶");

        // ---- 矿石 ----
        put("COAL_ORE", "煤矿石");
        put("IRON_ORE", "铁矿石");
        put("GOLD_ORE", "金矿石");
        put("DIAMOND_ORE", "钻石矿石");
        put("EMERALD_ORE", "绿宝石矿石");
        put("REDSTONE_ORE", "红石矿石");
        put("LAPIS_ORE", "青金石矿石");
        put("COAL_BLOCK", "煤炭块");
        put("IRON_BLOCK", "铁块");
        put("GOLD_BLOCK", "金块");
        put("DIAMOND_BLOCK", "钻石块");
        put("EMERALD_BLOCK", "绿宝石块");

        // ---- 功能方块 ----
        put("SPAWNER", "刷怪笼");
        put("MOB_SPAWNER", "刷怪笼");
        put("TNT", "TNT");
        put("TORCH", "火把");
        put("GLOWSTONE", "荧石");
        put("CAMPFIRE", "营火");
        put("LADDER", "梯子");
        put("SCAFFOLDING", "脚手架");
        put("FARMLAND", "耕地");
        put("WHEAT", "小麦");
        put("CARROTS", "胡萝卜");
        put("POTATOES", "马铃薯");
        put("BEETROOTS", "甜菜根");
        put("SUGAR_CANE", "甘蔗");
        put("NETHER_PORTAL", "下界传送门");
        put("END_PORTAL_FRAME", "末地传送门框架");
    }

    /** 方块 / 物品的中文名。 */
    public static String of(Material m) {
        if (m == null) {
            return "未知";
        }
        return of(m.name());
    }

    public static String of(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return "未知";
        }
        String key = rawName.toUpperCase(Locale.ROOT);
        String cn = CN.get(key);
        if (cn != null) {
            return cn;
        }
        String combo = compose(key);
        if (combo != null) {
            return combo;
        }
        return prettify(key);
    }

    /** 材质前缀 → 中文（钻石、铁、下界合金…）。 */
    private static final String[][] PREFIX = {
            {"NETHERITE", "下界合金"}, {"DIAMOND", "钻石"}, {"GOLDEN", "金"},
            {"GOLD", "金"}, {"IRON", "铁"}, {"COPPER", "铜"}, {"STONE", "石"},
            {"WOODEN", "木"}, {"LEATHER", "皮革"}, {"CHAINMAIL", "锁链"},
            {"AMETHYST", "紫水晶"}, {"EMERALD", "绿宝石"}, {"LAPIS", "青金石"},
            {"REDSTONE", "红石"}, {"QUARTZ", "下界石英"}, {"COAL", "煤炭"},
            {"NETHER", "下界"}, {"BAMBOO", "竹"}, {"CHERRY", "樱花"},
            {"CRIMSON", "绯红"}, {"WARPED", "诡异"}, {"MANGROVE", "红树"},
            {"OAK", "橡木"}, {"SPRUCE", "云杉"}, {"BIRCH", "白桦"}, {"JUNGLE", "丛林"}
    };

    /** 类型后缀 → 中文（镐、剑、锭、矿石…）。 */
    private static final String[][] SUFFIX = {
            {"PICKAXE", "镐"}, {"SHOVEL", "锹"}, {"SWORD", "剑"}, {"AXE", "斧"},
            {"HOE", "锄"}, {"HELMET", "头盔"}, {"CHESTPLATE", "胸甲"},
            {"LEGGINGS", "护腿"}, {"BOOTS", "靴子"}, {"INGOT", "锭"},
            {"ORE", "矿石"}, {"NUGGET", "粒"}, {"SCRAP", "碎片"}, {"SHARD", "碎片"},
            {"BLOCK", "块"}, {"PLANKS", "木板"}, {"SLAB", "台阶"}, {"STAIRS", "楼梯"},
            {"FENCE_GATE", "栅栏门"}, {"FENCE", "栅栏"}, {"DOOR", "门"},
            {"TRAPDOOR", "活板门"}, {"BUTTON", "按钮"}, {"PRESSURE_PLATE", "压力板"},
            {"SIGN", "告示牌"}, {"BOAT", "船"}, {"APPLE", "苹果"}, {"CARROT", "胡萝卜"},
            {"POTATO", "马铃薯"}, {"SEEDS", "种子"}, {"SPAWN_EGG", "刷怪蛋"}
    };

    /**
     * 组合出中文名：{@code DIAMOND_PICKAXE → 钻石镐}。
     *
     * <p>只处理「材质_类型」和「类型_材质」两种最常见的形式，
     * 认不出来就返回 null，交回英文兜底 —— 宁可显示英文，也别乱翻成错的名字。
     */
    private static String compose(String key) {
        int us = key.indexOf('_');
        if (us <= 0 || us >= key.length() - 1) {
            return null;
        }
        String first = key.substring(0, us);
        String rest = key.substring(us + 1);
        String p = find(PREFIX, first);
        String s = find(SUFFIX, rest);
        if (p != null && s != null) {
            return p + s;
        }
        // 反过来：IRON_ORE 这种「前缀_后缀」已覆盖；PORKCHOP_? 之类走下面的双段拼
        String p2 = find(PREFIX, rest);
        String s2 = find(SUFFIX, first);
        if (p2 != null && s2 != null) {
            return p2 + s2;
        }
        // 两端都是材料：RAW_IRON / IRON_BARS 这类，至少把材质翻出来
        if (p != null && rest.indexOf('_') < 0) {
            String rs = find(SUFFIX, rest);
            if (rs != null) {
                return p + rs;
            }
        }
        return null;
    }

    private static String find(String[][] table, String key) {
        for (int i = 0; i < table.length; i++) {
            if (table[i][0].equals(key)) {
                return table[i][1];
            }
        }
        return null;
    }

    /**
     * 没收录的材质：把 DIAMOND_PICKAXE 变成 Diamond Pickaxe。
     *
     * <p>注意这里<b>不</b>显示成原始常量名 —— 服主看 DIAMOND_PICKAXE
     * 比看 Diamond Pickaxe 更费劲，英文单词至少能读。
     */
    private static String prettify(String key) {
        // 去掉 1.13 之前遗留的 LEGACY_ 前缀
        if (key.startsWith("LEGACY_")) {
            key = key.substring(7);
        }
        StringBuilder sb = new StringBuilder(key.length());
        boolean upperNext = true;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch == '_') {
                sb.append(' ');
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(ch));
                upperNext = false;
            } else {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString().trim();
    }

    /** 操作类型的中文标签。 */
    public static String actionLabel(String type) {
        if ("BREAK".equals(type)) return "破坏";
        if ("PLACE".equals(type)) return "放置";
        if ("CONTAINER".equals(type)) return "打开";
        if ("CONTAINER_CLICK".equals(type)) return "取放";
        if ("COMMAND".equals(type)) return "命令";
        if ("DEATH".equals(type)) return "死亡";
        if ("JOIN".equals(type)) return "进服";
        if ("QUIT".equals(type)) return "退服";
        return type;
    }
}
