package dev.dronchik.irlfog.gametest;

import com.google.gson.JsonParser;
import dev.dronchik.irlfog.FogRegion;
import dev.dronchik.irlfog.FogStore;
import dev.dronchik.irlfog.Keyframes;
import dev.dronchik.irlfog.Presets;
import dev.dronchik.irlfog.SmokeRenderer;
import dev.dronchik.irlfog.SmokeSim;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;

/**
 * IRLFOG_TEST=look: smoke presets in a flat world (day, night, behind a wall, inside fog, explosion, room).
 * IRLFOG_TEST=perf: ms/frame at 1080p for a mixed scene per quality profile vs no smoke.
 * Both also run the migration and keyframe self-checks.
 */
public class SmokeGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        String mode = System.getenv("IRLFOG_TEST");
        if (mode == null) return;
        selfChecks();
        Properties props = new Properties();
        props.setProperty("spawn-protection", "0");
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props);
             TestServerConnection conn = server.connect()) {
            conn.getClientWorld().waitForChunksRender();
            server.runCommand("gamemode spectator @a");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            // a wall at z=14 (half the smoke row behind it), a closed room to the east
            if (!mode.equals("shots")) {
                server.runCommand("fill -12 -60 14 0 -55 14 stone_bricks");
                server.runCommand("fill 14 -60 2 22 -55 10 stone_bricks hollow");
                server.runCommand("fill 15 -60 3 21 -60 9 air");
            }
            ctx.waitTicks(20);
            if (mode.equals("perf")) perf(ctx, server);
            else if (mode.equals("iris")) iris(ctx, server);
            else if (mode.equals("gui")) gui(ctx, server);
            else if (mode.equals("shots")) shots(ctx, server);
            else look(ctx, server);
        }
    }

    private static void look(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        ctx.runOnClient(mc -> {
            FogStore.REGIONS.clear();
            put("campfire_small", -6, -60, 10);
            put("thick_black", -2, -60, 10);
            put("light_white", 2, -60, 10);
            put("magic_purple", 6, -60, 10);
            put("smoldering", -9, -60, 13);
            SmokeSim.requestSeek();
        });
        server.runCommand("tp @a 0.5 -58 -3 0 8");
        ctx.waitTicks(60);
        ctx.takeScreenshot("irlfog_row_day");
        server.runCommand("tp @a -4 -58 3 0 -10");
        ctx.waitTicks(10);
        ctx.takeScreenshot("irlfog_row_close");
        server.runCommand("tp @a 0.5 -57 24 180 10");
        ctx.waitTicks(10);
        ctx.takeScreenshot("irlfog_behind_wall");
        server.runCommand("time set 18000");
        server.runCommand("setblock -6 -60 9 campfire");
        server.runCommand("tp @a 0.5 -58 -3 0 8");
        ctx.waitTicks(20);
        ctx.takeScreenshot("irlfog_row_night");

        server.runCommand("time set 6000");
        ctx.runOnClient(mc -> {
            FogStore.REGIONS.clear();
            FogRegion f = put("morning_fog", 0, -58, 16);
            f.size = new float[]{48, 6, 48};
            SmokeSim.requestSeek();
        });
        server.runCommand("tp @a 0.5 -55 -20 0 10");
        ctx.waitTicks(20);
        ctx.takeScreenshot("irlfog_fog_outside");
        server.runCommand("tp @a 0.5 -59 10 0 0");
        ctx.waitTicks(10);
        ctx.takeScreenshot("irlfog_fog_inside");
        ctx.runOnClient(mc -> System.out.println("[IRLFOG] fog: volumes=" + SmokeRenderer.statVolumes + " fields=" + SmokeSim.statFields
                + " reason='" + FogStore.REGIONS.get(0).cullReason + "' kind=" + FogStore.REGIONS.get(0).kind + " center=" + java.util.Arrays.toString(FogStore.REGIONS.get(0).center)
                + " density=" + FogStore.REGIONS.get(0).density));

        ctx.runOnClient(mc -> {
            FogStore.REGIONS.clear();
            put("explosion", 0, -59, 12);
        });
        server.runCommand("tp @a 0.5 -57 -6 0 5");
        ctx.waitTicks(24);
        ctx.takeScreenshot("irlfog_explosion_1s");
        ctx.runOnClient(mc -> { FogRegion e = FogStore.REGIONS.get(0); System.out.println("[IRLFOG] explosion alive=" + e.puffs.size() + " t=" + SmokeSim.time
                + " valid=" + e.srcValid + " was=" + e.wasEnabled + " reason='" + e.cullReason + "' volumes=" + SmokeRenderer.statVolumes); });
        ctx.waitTicks(80);
        ctx.takeScreenshot("irlfog_explosion_5s");

        ctx.runOnClient(mc -> {
            FogStore.REGIONS.clear();
            put("room_fill", 18, -59.5f, 6);
            put("building_fire", -6, -60, 20).size = new float[]{4, 1, 4};
            SmokeSim.requestSeek();
        });
        server.runCommand("fill 14 -59 6 14 -58 6 air");
        server.runCommand("tp @a 10 -57 6 -90 10");
        ctx.waitTicks(60);
        ctx.takeScreenshot("irlfog_room_door");
        ctx.runOnClient(mc -> System.out.println("[IRLFOG] room alive=" + FogStore.REGIONS.get(0).puffs.size() + " t=" + SmokeSim.time));

        // quality ends at the same view
        ctx.runOnClient(mc -> {
            FogStore.REGIONS.clear();
            put("billowing", 0, -60, 8);
            SmokeSim.requestSeek();
        });
        server.runCommand("tp @a 0.5 -57.5 1 0 10");
        for (FogStore.Profile p : new FogStore.Profile[]{FogStore.Profile.LOW, FogStore.Profile.CINEMATIC}) {
            ctx.runOnClient(mc -> { FogStore.settings.profile = p; FogStore.settings.adaptive = false; });
            ctx.waitTicks(20);
            ctx.takeScreenshot("irlfog_quality_" + p.name().toLowerCase(Locale.ROOT));
        }
        ctx.runOnClient(mc -> System.out.println("[IRLFOG] render error: '" + SmokeRenderer.lastError + "' draws=" + SmokeRenderer.statDraws
                + " volumes=" + SmokeRenderer.statVolumes));
    }

    /** IRLFOG_TEST=gui: the editor inside IRL Redactor's window (J), every tab of a selected source. */
    private static void gui(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        server.runCommand("tp @a 0.5 -58 -3 0 8");
        ctx.runOnClient(mc -> {
            FogStore.REGIONS.clear();
            FogRegion r = put("campfire_large", 0, -60, 8);
            dev.dronchik.irlfog.Keyframes.setKey(r, dev.dronchik.irlfog.Keyframes.Param.DENSITY, 0, 1, dev.dronchik.irlfog.Keyframes.Ease.LINEAR);
            dev.dronchik.irlfog.Keyframes.setKey(r, dev.dronchik.irlfog.Keyframes.Param.DENSITY, 5, 2, dev.dronchik.irlfog.Keyframes.Ease.CUSTOM);
            FogStore.FIELDS.add(dev.dronchik.irlfog.ForceField.of(dev.dronchik.irlfog.ForceField.Kind.VORTEX));
            FogStore.settings.showDebug = true;
            dev.dronchik.irlfog.FogPanel.selectForTest(r);
            try {
                Class<?> c = Class.forName("org.qualet.irlredactor.editor.LightEditorScreen");
                mc.setScreen((net.minecraft.client.gui.screens.Screen) c.getConstructor().newInstance());
                c.getMethod("setVisible", boolean.class).invoke(null, true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(40);
        String[] names = {"general", "appearance", "physics", "lighting", "animation", "fields", "presets", "quality"};
        for (int i = 0; i < names.length; i++) {
            int t = i;
            ctx.runOnClient(mc -> dev.dronchik.irlfog.FogPanel.testTab = t);
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> dev.dronchik.irlfog.FogPanel.testTab = -1);
            ctx.waitTicks(5);
            ctx.takeScreenshot("irlfog_gui_" + names[i]);
        }
        ctx.runOnClient(mc -> System.out.println("[IRLFOG] gui done, panel frames=" + dev.dronchik.irlfog.FogPanel.frames + ", render error: '" + SmokeRenderer.lastError + "'"));
    }

    /** IRLFOG_TEST=shots (same args as iris): 1920x1080 showcase shots for the mod page, no HUD. */
    private static void shots(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        loadIris(ctx);
        ctx.runOnClient(mc -> { mc.options.hideGui = true; FogStore.settings.profile = FogStore.Profile.ULTRA; FogStore.settings.adaptive = false; });
        // scenery: trees, a cabin, a campfire
        String[] trees = {"fancy_oak -16 -60 30", "oak -9 -60 36", "birch -3 -60 40", "fancy_oak 5 -60 42", "spruce 18 -60 38",
                "oak -20 -60 20", "birch 22 -60 24", "spruce -12 -60 46", "fancy_oak 12 -60 50", "oak 0 -60 52"};
        for (String t : trees) server.runCommand("place feature minecraft:" + t);
        server.runCommand("fill 8 -60 22 14 -57 28 spruce_planks hollow");
        server.runCommand("fill 7 -56 21 15 -56 29 dark_oak_planks");
        server.runCommand("fill 11 -60 22 11 -59 22 air");
        server.runCommand("setblock 4 -61 18 netherrack");
        server.runCommand("setblock 4 -60 18 fire");

        scene(ctx, server, "800", "0.5 -58.6 -8 0 -2", 80, "01_morning_fog", () -> {
            FogRegion f = put("morning_fog", 0, -60, 34);
            f.size = new float[]{80, 5, 70};
            f.density = .7f;
        });
        scene(ctx, server, "12300", "-6 -58 4 -35 -14", 120, "02_campfire_sunset", () -> put("campfire_large", 4, -60, 18));
        scene(ctx, server, "7000", "-6 -57 2 -36 -10", 140, "03_burning_cabin", () -> put("building_fire", 11, -56, 25));
        scene(ctx, server, "6000", "0.5 -57 -4 0 -6", 0, null, () -> put("explosion", 0, -59, 22));
        ctx.waitTicks(22);
        shot(ctx, "04_explosion");
        scene(ctx, server, "13200", "-8 -58 6 0 -8", 100, "05_magic", () -> {
            put("portal", -8, -56, 22);
            put("magic_purple", -12, -60, 21);
            put("glowing", -4, -60, 21);
        });
        scene(ctx, server, "2000", "0.5 -57.5 -4 0 -4", 100, "06_smoke_types", () -> {
            put("thick_black", -9, -60, 16);
            put("gray", -3, -60, 16);
            put("light_white", 3, -60, 16);
            put("toxic_black", 9, -60, 16);
        });
        ctx.runOnClient(mc -> System.out.println("[IRLFOG] shots done, render error: '" + SmokeRenderer.lastError + "'"));
    }

    private static void scene(ClientGameTestContext ctx, TestDedicatedServerContext server, String time, String tp, int wait, String name, Runnable setup) {
        server.runCommand("time set " + time);
        server.runCommand("tp @a " + tp);
        ctx.runOnClient(mc -> { FogStore.REGIONS.clear(); setup.run(); SmokeSim.requestSeek(); });
        if (name == null) return;
        ctx.waitTicks(wait);
        shot(ctx, name);
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        ctx.takeScreenshot(net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions.of("irlfog_shot_" + name).withSize(1920, 1080));
    }

    private static void loadIris(ClientGameTestContext ctx) {
        java.nio.file.Path src = java.nio.file.Path.of(System.getenv("IRLFOG_PACK"));
        ctx.runOnClient(mc -> {
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                java.nio.file.Path dir = (java.nio.file.Path) iris.getMethod("getShaderpacksDirectory").invoke(null);
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.copy(src, dir.resolve(src.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Object cfg = iris.getMethod("getIrisConfig").invoke(null);
                cfg.getClass().getMethod("setShaderPackName", String.class).invoke(cfg, src.getFileName().toString());
                cfg.getClass().getMethod("setShadersEnabled", boolean.class).invoke(cfg, true);
                cfg.getClass().getMethod("save").invoke(cfg);
                iris.getMethod("reload").invoke(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** IRLFOG_TEST=iris IRLFOG_PACK=<zip> -PdevModsDir=<mods with iris+sodium> -PdevLibsDir=<their libs>. */
    private static void iris(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        loadIris(ctx);
        ctx.runOnClient(mc -> {
            mc.options.hideGui = true;
            FogStore.REGIONS.clear();
            put("campfire_small", -6, -60, 10);
            put("thick_black", -2, -60, 10);
            put("light_white", 2, -60, 10);
            put("magic_purple", 6, -60, 10);
            put("morning_fog", 0, -58, 30).size = new float[]{40, 5, 24};
            SmokeSim.requestSeek();
        });
        server.runCommand("tp @a 0.5 -58 -3 0 8");
        ctx.waitTicks(160);
        ctx.takeScreenshot("irlfog_iris_day");
        server.runCommand("tp @a 0.5 -57 24 180 10");
        ctx.waitTicks(20);
        ctx.takeScreenshot("irlfog_iris_behind_wall");
        server.runCommand("time set 18000");
        server.runCommand("setblock -6 -60 9 campfire");
        server.runCommand("tp @a 0.5 -58 -3 0 8");
        ctx.waitTicks(40);
        ctx.takeScreenshot("irlfog_iris_night");
        measure(ctx, "iris HIGH");
        ctx.runOnClient(mc -> System.out.println("[IRLFOG] iris render error: '" + SmokeRenderer.lastError + "' volumes=" + SmokeRenderer.statVolumes));
    }

    private static void perf(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        server.runCommand("tp @a 0.5 -57 -4 0 8");
        ctx.runOnClient(mc -> FogStore.REGIONS.clear());
        ctx.waitTicks(10);
        measure(ctx, "no smoke");
        ctx.runOnClient(mc -> {
            String[] ids = {"campfire_small", "campfire_large", "thick_black", "light_white", "gray", "billowing", "steam_water", "engine", "magic_purple", "smoldering"};
            for (int i = 0; i < ids.length; i++) put(ids[i], -9 + (i % 5) * 4.5f, -60, 8 + (i / 5) * 6);
            put("morning_fog", 0, -58, 14).size = new float[]{48, 6, 48};
            FogStore.settings.adaptive = false;
            SmokeSim.requestSeek();
        });
        ctx.waitTicks(80);
        for (FogStore.Profile p : new FogStore.Profile[]{FogStore.Profile.LOW, FogStore.Profile.MEDIUM, FogStore.Profile.HIGH, FogStore.Profile.ULTRA, FogStore.Profile.CINEMATIC}) {
            ctx.runOnClient(mc -> FogStore.settings.profile = p);
            measure(ctx, "11 sources " + p);
        }
        ctx.runOnClient(mc -> { FogStore.settings.profile = FogStore.Profile.ULTRA; FogStore.settings.adaptive = true; FogStore.settings.budgetMs = 2; });
        for (int i = 0; i < 4; i++) measure(ctx, "adaptive ULTRA, budget 2ms #" + i);
        ctx.runOnClient(mc -> System.out.println("[IRLFOG-PERF] adaptive level " + dev.dronchik.irlfog.SmokePerf.level + " puffs " + SmokeSim.statPuffs));
        ctx.takeScreenshot("irlfog_perf_view");
    }

    private static void measure(ClientGameTestContext ctx, String what) {
        ctx.waitTicks(10);
        double[] r = ctx.computeOnClient(mc -> {
            var win = mc.getWindow();
            int pw = win.getWidth(), ph = win.getHeight();
            win.setWidth(1920);
            win.setHeight(1080);
            mc.getMainRenderTarget().resize(1920, 1080);
            for (int i = 0; i < 10; i++) mc.gameRenderer.render(mc.getDeltaTracker(), true);
            org.lwjgl.opengl.GL11.glFinish();
            long t0 = System.nanoTime();
            double gpu = 0;
            for (int i = 0; i < 120; i++) {
                mc.gameRenderer.render(mc.getDeltaTracker(), true);
                gpu += dev.dronchik.irlfog.SmokePerf.gpuMsRaw;
            }
            org.lwjgl.opengl.GL11.glFinish();
            double total = (System.nanoTime() - t0) / 1e6 / 120;
            win.setWidth(pw);
            win.setHeight(ph);
            mc.getMainRenderTarget().resize(pw, ph);
            return new double[]{total, gpu / 120, SmokeSim.statPuffs, SmokeSim.simMs};
        });
        System.out.println(String.format(Locale.ROOT, "[IRLFOG-PERF] %-32s %6.2f ms/frame (%4.0f fps)  smoke GPU %5.2f ms  sim %4.2f ms  puffs %.0f",
                what, r[0], 1000 / r[0], r[1], r[3], r[2]));
    }

    private static FogRegion put(String preset, float x, float y, float z) {
        FogRegion r = Presets.create(preset);
        r.center = new float[]{x, y + (r.kind == FogRegion.Kind.FIELD ? r.size[1] / 2 : 0.1f), z};
        FogStore.REGIONS.add(r);
        return r;
    }

    private static void selfChecks() {
        Keyframes.main(new String[0]);
        // a 1.0.3 world file: bare array, one zone of each old type
        String old = "[{\"name\":\"Туман\",\"enabled\":true,\"type\":\"NATURAL\",\"center\":[1,64,2],\"size\":[16,6,16],\"color\":[0.85,0.88,0.92,0.25],"
                + "\"colorEnd\":[0.8,0.83,0.88],\"sizeMax\":8,\"rate\":40},"
                + "{\"name\":\"Смог\",\"type\":\"DIRTY\",\"center\":[0,0,0],\"size\":[8,4,8],\"color\":[0.47,0.42,0.33,0.35]},"
                + "{\"name\":\"Дым\",\"type\":\"SMOKE\",\"center\":[5,70,5],\"size\":[2,2,2],\"color\":[0.22,0.21,0.2,0.8],\"sizeMin\":1.2,\"sizeMax\":2.5,\"rate\":30,\"life\":6}]";
        try {
            FogStore.REGIONS.clear();
            FogStore.parse(JsonParser.parseString(old), null);
            check(FogStore.REGIONS.size() == 3, "3 zones migrated");
            FogRegion a = FogStore.REGIONS.get(0), c = FogStore.REGIONS.get(2);
            check(a.kind == FogRegion.Kind.FIELD && a.name.equals("Туман") && a.center[2] == 2 && a.size[0] == 16, "natural -> field, placement kept");
            check(c.kind == FogRegion.Kind.PUFFS && c.life == 6 && c.center[1] == 70 && Math.abs(c.sizeMax - 2.5f * 0.45f) < 1e-4, "smoke -> puffs");
            check(c.version == 2 && c.puffs != null && c.tracks != null, "runtime fields restored");
            String saved = new com.google.gson.Gson().toJson(java.util.Map.of("version", 2, "regions", FogStore.REGIONS, "fields", FogStore.FIELDS));
            FogStore.REGIONS.clear();
            FogStore.parse(JsonParser.parseString(saved), null);
            check(FogStore.REGIONS.size() == 3 && FogStore.REGIONS.get(2).kind == FogRegion.Kind.PUFFS, "2.0 round trip");
            FogStore.REGIONS.clear();
            String real = System.getenv("IRLFOG_REAL_CONFIG");
            if (real != null) try (var files = java.nio.file.Files.list(java.nio.file.Path.of(real))) {
                for (java.nio.file.Path f : files.filter(x -> x.toString().endsWith(".json") && !x.getFileName().toString().equals("settings.json")).toList()) {
                    FogStore.REGIONS.clear();
                    FogStore.parse(JsonParser.parseString(java.nio.file.Files.readString(f)), null);
                    StringBuilder sb = new StringBuilder();
                    for (FogRegion r : FogStore.REGIONS) sb.append(r.name).append('=').append(r.kind).append(' ');
                    System.out.println("[IRLFOG] real " + f.getFileName() + ": " + FogStore.REGIONS.size() + " -> " + sb);
                }
                FogStore.REGIONS.clear();
            }
            System.out.println("[IRLFOG] migration OK, " + Presets.BUILTIN.size() + " built-in presets");
        } catch (Exception e) {
            throw new AssertionError("migration", e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
