package com.tinyaii.tinywatch.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把旧版本的 config.yml 升级成新版结构。
 *
 * <p><b>为什么需要这个</b>：Bukkit 的 {@code saveDefaultConfig()} 只在文件不存在时写一次。
 * 用户从 1.0 升到 1.1，配置文件一个字节都不会变 —— 新加的 {@code alerts}、{@code tasks}、
 * {@code web.world} 这些段落他永远看不到，只能靠翻 README 才知道有这些开关。
 *
 * <p><b>为什么不是「补 key」而是「重写」</b>：{@code YamlConfiguration.save()}
 * 会把文件里所有注释都吃掉。与其生成一份没有注释的配置，不如拿 jar 里的模板当骨架，
 * 把用户改过的值填回去 —— 注释完整，新项自然出现，用户的值一个不丢。
 *
 * <p><b>安全措施（三条，缺一不可）</b>：
 * <ol>
 *   <li>动手前先把原文件备份成 config.yml.bak</li>
 *   <li>重建后的文本先自己解析一遍，解析失败就放弃，绝不写盘</li>
 *   <li>用户的键比模板多（说明手写过自定义项）就跳过，不冒这个险</li>
 * </ol>
 */
public final class ConfigMigrator {

    /** 形如 `  key: value` 或 `  key:` 的行。 */
    private static final Pattern KEY_LINE =
            Pattern.compile("^(\\s*)([A-Za-z0-9_\\-]+):(\\s*)(.*)$");

    /** 列表项 `  - foo`。 */
    private static final Pattern LIST_ITEM =
            Pattern.compile("^(\\s*)-\\s+(.*)$");

    private ConfigMigrator() {
    }

    /**
     * 检查并升级配置。
     *
     * @return 升级摘要（没升级返回 null）
     */
    public static String migrate(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return null;   // 首次启动，saveDefaultConfig 已经写好完整文件
        }
        String template = readTemplate(plugin, "config.yml");
        if (template == null || template.trim().isEmpty()) {
            return null;
        }

        YamlConfiguration defaults = new YamlConfiguration();
        YamlConfiguration user = new YamlConfiguration();
        try {
            defaults.loadFromString(template);
            user.loadFromString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            plugin.getLogger().warning("[配置升级] 读取失败，跳过：" + t);
            return null;
        }

        // 用户自己加的键（模板里没有）—— 说明他手写过东西，别动他的文件
        Set<String> extra = new LinkedHashSet<String>();
        for (String k : user.getKeys(true)) {
            if (!defaults.isSet(k)) {
                extra.add(k);
            }
        }
        if (!extra.isEmpty()) {
            plugin.getLogger().info("[配置升级] 配置文件里有 " + extra.size()
                    + " 个本插件不认识的项，为免覆盖不自动升级。新增项见 README「配置」。");
            return null;
        }

        // 找出用户缺的叶子项（section 不算）
        List<String> missing = new ArrayList<String>();
        for (String k : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(k)) {
                continue;
            }
            if (!user.isSet(k)) {
                missing.add(k);
            }
        }
        if (missing.isEmpty()) {
            return null;   // 已经是最新结构
        }

        String rebuilt = rebuild(template, user, defaults);
        if (rebuilt == null) {
            plugin.getLogger().warning("[配置升级] 重建失败，保持原文件不动。");
            return null;
        }

        // 写盘前自检：重建后的内容必须是合法 YAML，且键集合和模板一致
        try {
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(rebuilt);
            Set<String> checkKeys = new LinkedHashSet<String>(check.getKeys(true));
            checkKeys.removeAll(defaults.getKeys(true));
            if (!checkKeys.isEmpty()) {
                plugin.getLogger().warning("[配置升级] 重建结果校验不通过，保持原文件不动。");
                return null;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[配置升级] 重建结果不是合法 YAML，保持原文件不动：" + t);
            return null;
        }

        try {
            Files.copy(file.toPath(), new File(plugin.getDataFolder(), "config.yml.bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.write(file.toPath(), rebuilt.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            plugin.getLogger().warning("[配置升级] 写入失败，原文件未受影响：" + t);
            return null;
        }

        return "新增 " + missing.size() + " 项：" + summarize(missing);
    }

    /** 取 jar 里的默认配置文本。 */
    private static String readTemplate(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8192);
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按模板重建：注释和结构用模板的，值用用户的（用户没设过就用模板默认值）。
     *
     * <p>逐行扫，用缩进栈还原当前路径。所以只能处理「行首缩进 + key」这种常规写法 ——
     * 我们自己的模板就是这种，够用。
     */
    private static String rebuild(String template, YamlConfiguration user, YamlConfiguration defaults) {
        String[] lines = template.split("\n", -1);
        StringBuilder out = new StringBuilder(template.length() + 512);

        // 缩进栈：{缩进, key}
        List<int[]> indents = new ArrayList<int[]>();
        List<String> keys = new ArrayList<String>();

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            Matcher m = KEY_LINE.matcher(line);
            if (!m.matches()) {
                out.append(line).append('\n');
                i++;
                continue;
            }
            int indent = m.group(1).length();
            String key = m.group(2);
            String rest = m.group(4).trim();

            while (!indents.isEmpty() && indents.get(indents.size() - 1)[0] >= indent) {
                indents.remove(indents.size() - 1);
                keys.remove(keys.size() - 1);
            }
            StringBuilder path = new StringBuilder();
            for (String k : keys) {
                path.append(k).append('.');
            }
            path.append(key);
            String full = path.toString();

            // 列表项（模板里写成 "key:" 然后跟若干 "  - xxx"）
            int j = i + 1;
            boolean hasList = false;
            while (j < lines.length) {
                Matcher li = LIST_ITEM.matcher(lines[j]);
                if (!li.matches() || li.group(1).length() <= indent) {
                    break;
                }
                hasList = true;
                j++;
            }
            Object uv = user.isSet(full) ? user.get(full) : null;
            if (hasList && uv instanceof List) {
                out.append(m.group(1)).append(key).append(":\n");
                String pad = m.group(1) + "  ";
                for (Object item : (List<?>) uv) {
                    out.append(pad).append("- ").append(scalar(item)).append('\n');
                }
                i = j;
                continue;
            }
            if (hasList || rest.isEmpty()) {
                // 段/列表照抄，把 key 压栈
                out.append(line).append('\n');
                indents.add(new int[]{indent});
                keys.add(key);
                i++;
                continue;
            }

            // 叶子：用户设过就用用户的值
            // 列表要单独处理 —— 直接把 List.toString() 写进去会得到字符串 "[]"，
            // 类型就从列表变成字符串了（getStringList 会拿到空表，看起来能用，其实是错的）
            if (uv instanceof List) {
                List<?> l = (List<?>) uv;
                if (l.isEmpty()) {
                    out.append(m.group(1)).append(key).append(": []\n");
                } else {
                    out.append(m.group(1)).append(key).append(":\n");
                    String pad = m.group(1) + "  ";
                    for (Object item : l) {
                        out.append(pad).append("- ").append(scalar(item)).append('\n');
                    }
                }
            } else if (uv != null && !defaults.isConfigurationSection(full)) {
                out.append(m.group(1)).append(key).append(": ").append(scalar(uv)).append('\n');
            } else {
                out.append(line).append('\n');
            }
            i++;
        }
        return out.toString();
    }

    /** 一个值写回 YAML 文本。字符串统一加引号，避免 "1.0" 被读成数字这类坑。 */
    private static String scalar(Object v) {
        if (v == null) {
            return "\"\"";
        }
        if (v instanceof Boolean || v instanceof Number) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        if (s.isEmpty()) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 日志里别刷一屏：只列前 6 项。 */
    private static String summarize(List<String> missing) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(6, missing.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(missing.get(i));
        }
        if (missing.size() > n) {
            sb.append(" 等");
        }
        return sb.toString();
    }
}
