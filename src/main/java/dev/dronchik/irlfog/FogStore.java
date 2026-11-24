package dev.dronchik.irlfog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-world file config/irl-fog/&lt;sp-folder|mp-ip&gt;.json. 1.0.x wrote a bare array of zones; 2.0 writes
 * {version, regions, fields}. An old file is converted on load and backed up once as &lt;key&gt;.v1.json.
 */
public final class FogStore {
    public static final Logger LOG = LoggerFactory.getLogger("irlfog");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final List<FogRegion> REGIONS = new ArrayList<>();
    public static final List<ForceField> FIELDS = new ArrayList<>();
    public static Settings settings = new Settings();
    public static String worldKey;
    public static boolean dirty;

    public enum Profile { LOW, MEDIUM, HIGH, ULTRA, CINEMATIC, CUSTOM }
    public enum TimeSource { SCENE, REAL }

    public static class Settings {
        // 1.0.x
        public boolean enabled = true;
        /** max distance from the camera at which smoke is simulated and drawn */
        public float spawnDistance = 160;
        /** puff count multiplier */
        public float quality = 1;
        public boolean showBoxes = true;
        // 2.0
        public Profile profile = Profile.HIGH;
        public int customSteps = 12, customFieldSteps = 32, customShadowSteps = 1, customOctaves = 3;
        public float customResolution = 0.5f;
        public boolean adaptive = true;
        public float budgetMs = 3f;
        public boolean lod = true;
        public float lodNear = 32, lodFar = 96;
        /** simulation substep in seconds (smaller = smoother, slower) */
        public float simStep = 0.05f;
        /** far emitters are simulated every N frames */
        public int farUpdateEvery = 3;
        public TimeSource timeSource = TimeSource.SCENE;
        public float[] wind = {1, 0, 0.3f};
        public float windStrength = 0.3f;
        public boolean blockLight = true, sunLight = true, irlLights = true;
        public boolean showDebug = false, showFields = true;
    }

    private static class WorldFile {
        int version = 2;
        List<FogRegion> regions = new ArrayList<>();
        List<ForceField> fields = new ArrayList<>();
    }

    private static Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("irl-fog"); }

    public static void loadSettings() {
        Settings s = read(dir().resolve("settings.json"), Settings.class);
        if (s != null) settings = s;
        if (settings.wind == null || settings.wind.length < 3) settings.wind = new float[]{1, 0, 0.3f};
        if (settings.profile == null) settings.profile = Profile.HIGH;
        if (settings.timeSource == null) settings.timeSource = TimeSource.SCENE;
    }

    public static void load(Minecraft mc) {
        clear();
        worldKey = worldKey(mc);
        if (worldKey == null) return;
        Path p = dir().resolve(worldKey + ".json");
        if (Files.isRegularFile(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                parse(JsonParser.parseReader(r), p);
            } catch (Exception e) {
                LOG.error("Не удалось прочитать {}", p, e);
            }
        }
        dirty = false;
    }

    /** Fills REGIONS / FIELDS from either file format. Package-visible for the migration self-check. */
    public static void parse(JsonElement root, Path source) throws Exception {
        if (root == null || root.isJsonNull()) return;
        if (root.isJsonArray()) {
            List<FogRegion> old = GSON.fromJson(root, new TypeToken<List<FogRegion>>() {}.getType());
            if (old != null) for (FogRegion r : old) {
                if (r == null) continue;
                r.version = 0; // the 2.0 constructor default is 2; old files never had the field
                r.migrate();
                REGIONS.add(r);
            }
            if (source != null) {
                Path backup = source.resolveSibling(source.getFileName().toString().replace(".json", ".v1.json"));
                if (!Files.exists(backup)) Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
                LOG.info("IRL Fog: зоны 1.0 из {} перенесены в формат 2.0 (копия: {})", source.getFileName(), backup.getFileName());
                dirty = true;
            }
            return;
        }
        WorldFile f = GSON.fromJson(root, WorldFile.class);
        if (f == null) return;
        if (f.regions != null) f.regions.stream().filter(Objects::nonNull).forEach(r -> {
            r.migrate();
            REGIONS.add(r);
        });
        if (f.fields != null) f.fields.stream().filter(Objects::nonNull).forEach(FIELDS::add);
    }

    public static void clear() {
        REGIONS.forEach(r -> r.removed = true);
        SmokeSim.releaseAll();
        REGIONS.clear();
        FIELDS.clear();
        worldKey = null;
    }

    public static void save() {
        try {
            Files.createDirectories(dir());
            write(dir().resolve("settings.json"), settings);
            if (worldKey != null) {
                WorldFile f = new WorldFile();
                f.regions = REGIONS;
                f.fields = FIELDS;
                write(dir().resolve(worldKey + ".json"), f);
            }
            dirty = false;
        } catch (Exception e) {
            LOG.error("Не удалось сохранить туман", e);
        }
    }

    private static void write(Path p, Object o) throws Exception {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) { GSON.toJson(o, w); }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static <T> T read(Path p, java.lang.reflect.Type t) {
        if (!Files.isRegularFile(p)) return null;
        try (Reader r = Files.newBufferedReader(p)) {
            return GSON.fromJson(r, t);
        } catch (Exception e) {
            LOG.error("Не удалось прочитать {}", p, e);
            return null;
        }
    }

    // тот же ключ мира, что у IRL Redactor: sp-<папка мира> / mp-<адрес>
    private static String worldKey(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server != null) return "sp-" + sanitize(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
        ServerData info = mc.getCurrentServer();
        return info != null && info.ip != null && !info.ip.isEmpty() ? "mp-" + sanitize(info.ip) : null;
    }

    private static String sanitize(String s) { return s.replaceAll("[^a-zA-Z0-9-_]", "_"); }
}
