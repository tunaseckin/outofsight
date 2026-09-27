package io.github.tunaseckin.outofsight;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads Paper's own anti-cheat settings and says what they leave open.
 *
 * <p>Ore X-ray and netherite finders are answered by Paper's anti-xray, not by
 * this plugin, and it ships switched off. Its default {@code hidden-blocks} list
 * also leaves out ancient debris, spawners, barrels and trapped chests, so a
 * server that did switch it on can still be scanned for netherite. Nothing here
 * changes a setting; it only reports, because Paper's config belongs to the
 * admin and a plugin rewriting it would surprise everyone.
 */
public final class ConfigAdvisor {

    /** Blocks worth hiding in every dimension. */
    private static final List<String> EVERYWHERE = List.of(
            "chest", "trapped_chest", "barrel", "ender_chest", "spawner");

    /** Blocks worth hiding per dimension, on top of {@link #EVERYWHERE}. */
    private static final List<String> OVERWORLD = List.of("diamond_ore", "deepslate_diamond_ore");
    private static final List<String> NETHER = List.of("ancient_debris");

    private final Plugin plugin;

    public ConfigAdvisor(Plugin plugin) {
        this.plugin = plugin;
    }

    /** One line per finding, empty when nothing needs attention. */
    public List<String> check(boolean shieldEnabled) {
        List<String> findings = new ArrayList<>();
        File configDir = new File(plugin.getServer().getPluginsFolder().getParentFile(), "config");
        YamlConfiguration defaults = load(new File(configDir, "paper-world-defaults.yml"));

        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() == World.Environment.THE_END) {
                continue; // Nothing buried there worth an X-ray.
            }
            YamlConfiguration own = load(new File(world.getWorldFolder(), "paper-world.yml"));
            String name = world.getName();

            if (!bool(own, defaults, "anticheat.anti-xray.enabled", false)) {
                findings.add(name + ": Paper anti-xray is off, so X-ray and netherite finders see"
                        + " every ore. Set anticheat.anti-xray.enabled: true in"
                        + " config/paper-world-defaults.yml.");
                continue;
            }

            Set<String> hidden = new HashSet<>();
            for (String block : list(own, defaults, "anticheat.anti-xray.hidden-blocks")) {
                hidden.add(block.toLowerCase(Locale.ROOT).replace("minecraft:", ""));
            }
            if (hidden.isEmpty()) {
                // Unset in both files: Paper's built-in list, which has chests
                // and ores but none of the rest.
                hidden.addAll(List.of("chest", "ender_chest", "diamond_ore", "deepslate_diamond_ore"));
            }
            List<String> wanted = new ArrayList<>(EVERYWHERE);
            wanted.addAll(world.getEnvironment() == World.Environment.NETHER ? NETHER : OVERWORLD);
            List<String> missing = new ArrayList<>();
            for (String block : wanted) {
                if (!hidden.contains(block)) {
                    missing.add(block);
                }
            }
            if (!missing.isEmpty()) {
                findings.add(name + ": anti-xray hidden-blocks is missing " + String.join(", ", missing)
                        + ". These leak to X-ray" + (missing.contains("ancient_debris")
                        ? ", and without ancient_debris a netherite finder works as if anti-xray were off"
                        : "") + ".");
            }
        }

        if (!bool(null, defaults, "feature-seeds.generate-random-seeds-for-all", false)) {
            findings.add("feature-seeds.generate-random-seeds-for-all is off. Anyone who cracks the"
                    + " world seed can predict ores and features in new chunks. Turning it on affects"
                    + " only chunks generated from then on. Keep the seed itself private too.");
        }
        if (!shieldEnabled) {
            findings.add("The OutOfSight shield is off, so storage ESP and stash finders see every"
                    + " container. Try shield.test-mode first, then set shield.enabled: true.");
        }
        return findings;
    }

    private static YamlConfiguration load(File file) {
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    /** A per-world file may hold the literal {@code default}, which defers to the defaults file. */
    private static boolean isSet(YamlConfiguration config, String path) {
        return config != null && config.isSet(path)
                && !"default".equalsIgnoreCase(String.valueOf(config.get(path)));
    }

    private static boolean bool(YamlConfiguration own, YamlConfiguration defaults, String path,
                                boolean fallback) {
        if (isSet(own, path)) {
            return own.getBoolean(path);
        }
        return isSet(defaults, path) ? defaults.getBoolean(path) : fallback;
    }

    private static List<String> list(YamlConfiguration own, YamlConfiguration defaults, String path) {
        if (isSet(own, path)) {
            return own.getStringList(path);
        }
        return isSet(defaults, path) ? defaults.getStringList(path) : List.of();
    }
}
