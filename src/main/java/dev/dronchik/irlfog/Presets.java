package dev.dronchik.irlfog;

import dev.dronchik.irlfog.FogRegion.Kind;
import dev.dronchik.irlfog.FogRegion.Motion;
import dev.dronchik.irlfog.FogRegion.Shape;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Built-in looks are parameter tables over the one engine (no per-look code). User presets are emitter JSON files in
 * config/irl-fog/presets; applying any preset replaces the look and motion but keeps placement, name, group,
 * attachment and keyframes.
 */
public final class Presets {
    public record Preset(String id, String name, String category, Consumer<FogRegion> fn) {}

    public static final Map<String, Preset> BUILTIN = new LinkedHashMap<>();
    public static final String[] CATEGORIES = {"Обычный дым", "Дым от огня", "Природа", "Кино", "Фантастика"};
    private static final Set<String> KEEP = Set.of("name", "enabled", "center", "group", "tracks", "attach", "attachUuid",
            "attachName", "offset", "loopAnim", "loopStart", "loopLength", "version", "preset", "type");

    private static void add(String cat, String id, String name, Consumer<FogRegion> fn) {
        BUILTIN.put(id, new Preset(id, name, cat, fn));
    }

    // compact setters for the tables below
    private static void col(FogRegion r, float red, float g, float b, float a) { r.color = new float[]{red, g, b, a}; }
    private static void end(FogRegion r, float red, float g, float b) { r.colorEnd = new float[]{red, g, b}; }
    private static void puffs(FogRegion r, float rate, float life, float rMin, float rMax, float grow) {
        r.kind = Kind.PUFFS; r.rate = rate; r.life = life; r.sizeMin = rMin; r.sizeMax = rMax; r.grow = grow;
    }
    private static void move(FogRegion r, float speed, float buoyancy, float rise, float turbulence) {
        r.speed = speed; r.buoyancy = buoyancy; r.rise = rise; r.turbulence = turbulence;
    }
    private static void area(FogRegion r, Shape s, float x, float y, float z) { r.shape = s; r.size = new float[]{x, y, z}; }
    private static void field(FogRegion r, float x, float y, float z, float density, float falloff, float noise) {
        r.kind = Kind.FIELD; r.shape = Shape.BOX; r.size = new float[]{x, y, z}; r.density = density; r.heightFalloff = falloff;
        r.noiseScale = noise; r.lightResponse = 1; r.speed = 0.2f; r.dir = new float[]{1, 0, 0}; r.turbulence = 0.4f;
    }
    private static void fire(FogRegion r, float glow) { r.fireGlow = glow; r.temperature = 1; }

    static {
        String A = CATEGORIES[0], B = CATEGORIES[1], C = CATEGORIES[2], D = CATEGORIES[3], E = CATEGORIES[4];
        add(A, "light_white", "Лёгкий белый дым", r -> { col(r, .92f, .92f, .94f, .6f); end(r, .95f, .95f, .96f); r.density = .8f; puffs(r, 6, 8, .5f, .8f, 3.5f); move(r, .8f, 1f, .4f, .4f); });
        add(A, "gray", "Серый дым", r -> { col(r, .55f, .55f, .56f, .85f); end(r, .62f, .62f, .63f); r.density = 1.2f; puffs(r, 8, 8, .5f, .8f, 3.5f); move(r, 1f, 1.2f, .4f, .45f); });
        add(A, "thick_black", "Густой чёрный дым", r -> { col(r, .07f, .065f, .06f, 1); end(r, .16f, .15f, .15f); r.density = 2.6f; r.absorption = 1.6f; r.scattering = .5f; puffs(r, 10, 10, .7f, 1.1f, 4); move(r, 1.6f, 2f, .5f, .6f); r.contrast = 1.3f; });
        add(A, "slow_rise", "Медленно поднимающийся", r -> { col(r, .7f, .7f, .72f, .7f); end(r, .78f, .78f, .8f); puffs(r, 4, 14, .5f, .7f, 3); move(r, .3f, .3f, .25f, .2f); r.animSpeed = .5f; r.motion = Motion.CALM; });
        add(A, "fast_rise", "Быстро поднимающийся", r -> { col(r, .5f, .5f, .5f, .85f); puffs(r, 14, 4, .4f, .6f, 3); move(r, 4, 3, 1.5f, .6f); });
        add(A, "ground_spread", "Расстилается по поверхности", r -> { col(r, .78f, .78f, .8f, .8f); area(r, Shape.DISC, 1.5f, .2f, 1.5f); puffs(r, 8, 12, .7f, 1.1f, 4); move(r, .4f, -.4f, 0, .3f); r.gravity = .2f; r.pressure = 1.2f; r.surfaceSpread = 1; r.temperature = .5f; });
        add(A, "billowing", "Сильно клубящийся", r -> { col(r, .42f, .41f, .4f, .95f); puffs(r, 12, 8, .6f, 1f, 4); move(r, 1.5f, 2, .5f, 1.5f); r.curlScale = .5f; r.vorticity = .5f; r.distortion = 1.1f; r.contrast = 1.4f; r.motion = Motion.TURBULENT; });
        add(A, "dissolving", "Растворяется в воздухе", r -> { col(r, .8f, .8f, .82f, .7f); r.dissipation = .35f; r.fadeOut = .7f; r.edgeSoftness = .6f; r.density = .9f; puffs(r, 6, 9, .5f, .8f, 4.5f); move(r, .7f, .8f, .3f, .5f); });

        add(B, "campfire_small", "Небольшой костёр", r -> { col(r, .32f, .31f, .3f, .85f); end(r, .55f, .55f, .56f); area(r, Shape.DISC, .8f, .2f, .8f); puffs(r, 6, 7, .25f, .4f, 5); move(r, 1.2f, 2, .5f, .5f); r.cooling = .5f; fire(r, .6f); });
        add(B, "campfire_large", "Большой костёр", r -> { col(r, .25f, .24f, .23f, .9f); end(r, .5f, .5f, .5f); area(r, Shape.DISC, 2, .5f, 2); puffs(r, 14, 9, .6f, 1f, 5); move(r, 2, 3, .7f, .7f); r.density = 1.5f; fire(r, .8f); });
        add(B, "building_fire", "Пожар в здании", r -> { col(r, .12f, .11f, .1f, 1); end(r, .3f, .29f, .28f); area(r, Shape.BOX, 6, 1, 6); puffs(r, 20, 14, 1f, 1.6f, 6); move(r, 2.5f, 3, 1, 1); r.density = 2.2f; r.maxParticles = 400; fire(r, 1); });
        add(B, "burning_wood", "Горящее дерево", r -> { col(r, .36f, .34f, .31f, .8f); end(r, .52f, .5f, .48f); area(r, Shape.BOX, 1.5f, .6f, 1.5f); puffs(r, 9, 9, .4f, .6f, 4.5f); move(r, 1.4f, 2.2f, .5f, .6f); fire(r, .5f); });
        add(B, "coal", "Горящий уголь", r -> { col(r, .15f, .14f, .13f, .9f); end(r, .3f, .3f, .3f); area(r, Shape.BOX, 1.5f, .3f, 1.5f); puffs(r, 5, 8, .35f, .5f, 4); move(r, .7f, 1.5f, .4f, .4f); r.density = 1.8f; fire(r, .45f); r.fireColor = new float[]{1, .3f, .08f}; });
        add(B, "torch", "Факел", r -> { col(r, .4f, .39f, .38f, .55f); area(r, Shape.POINT, .1f, .1f, .1f); puffs(r, 4, 3, .08f, .12f, 6); move(r, .8f, 1.2f, .3f, .4f); r.density = .7f; fire(r, .35f); });
        add(B, "candle", "Свеча", r -> { col(r, .5f, .5f, .52f, .45f); area(r, Shape.POINT, .02f, .02f, .02f); puffs(r, 3, 2.5f, .03f, .05f, 5); move(r, .4f, .8f, .2f, .25f); r.density = .55f; r.vorticity = .3f; r.curlScale = 1.2f; fire(r, .2f); });
        add(B, "big_flame", "Густой дым большого пламени", r -> { col(r, .1f, .095f, .09f, 1); end(r, .26f, .25f, .25f); area(r, Shape.DISC, 5, .5f, 5); puffs(r, 30, 12, 1.2f, 1.8f, 5); move(r, 3, 4, 1.2f, 1.2f); r.density = 2.4f; r.maxParticles = 500; fire(r, 1.2f); r.motion = Motion.TURBULENT; });
        add(B, "toxic_black", "Чёрный токсичный дым", r -> { col(r, .06f, .07f, .04f, 1); end(r, .18f, .2f, .1f); puffs(r, 12, 11, .7f, 1f, 4.5f); move(r, 1.5f, 2, .5f, .8f); r.density = 2.8f; fire(r, .35f); r.fireColor = new float[]{.6f, .85f, .2f}; });
        add(B, "smoldering", "Тлеющие угли", r -> { col(r, .6f, .6f, .62f, .5f); area(r, Shape.BOX, 1.5f, .2f, 1.5f); puffs(r, 3, 10, .2f, .35f, 4); move(r, .3f, .6f, .2f, .3f); r.cooling = 1; fire(r, .25f); r.emission = .1f; r.emissionColor = new float[]{1, .25f, .05f}; r.motion = Motion.CALM; });

        add(C, "morning_fog", "Утренний туман", r -> { field(r, 64, 6, 64, .35f, .35f, .6f); col(r, .86f, .88f, .92f, 1); r.contrast = .6f; });
        add(C, "forest_fog", "Лесной туман", r -> { field(r, 48, 12, 48, .45f, .2f, .8f); col(r, .78f, .82f, .8f, 1); r.contrast = .9f; });
        add(C, "water_fog", "Туман над водой", r -> { field(r, 48, 3, 48, .5f, .8f, .7f); col(r, .85f, .9f, .95f, 1); r.speed = .1f; });
        add(C, "cold_ground", "Холодный туман у земли", r -> { field(r, 32, 2.5f, 32, .7f, 1.4f, .9f); col(r, .82f, .86f, .92f, 1); r.contrast = 1.1f; r.lightTint = new float[]{.9f, .95f, 1.1f}; });
        add(C, "swamp", "Густой болотный туман", r -> { field(r, 40, 6, 40, .9f, .45f, .8f); col(r, .55f, .6f, .45f, 1); r.contrast = 1; r.speed = .08f; });
        add(C, "between_trees", "Туман между деревьями", r -> { field(r, 40, 16, 40, .3f, .1f, 1.1f); col(r, .8f, .82f, .85f, 1); r.contrast = 1.4f; });
        add(C, "cave", "Туман в пещере", r -> { field(r, 24, 10, 24, .6f, 0, 1); col(r, .5f, .52f, .55f, 1); r.speed = .05f; });
        add(C, "steam_water", "Пар от горячей воды", r -> { col(r, .95f, .95f, .96f, .5f); area(r, Shape.BOX, 3, .1f, 3); puffs(r, 10, 3, .2f, .4f, 4); move(r, .6f, 1.5f, .4f, .5f); r.density = .6f; r.dissipation = .5f; r.fadeOut = .6f; });
        add(C, "geyser", "Пар от гейзера", r -> { col(r, .96f, .96f, .97f, .8f); area(r, Shape.POINT, .3f, .3f, .3f); puffs(r, 40, 4, .3f, .5f, 6); move(r, 12, 1, .5f, .8f); r.pressure = 1; r.drag = .6f; r.dissipation = .25f; r.maxParticles = 300; });
        add(C, "volcanic", "Вулканический дым", r -> { col(r, .2f, .18f, .17f, 1); end(r, .36f, .34f, .33f); area(r, Shape.DISC, 8, 1, 8); puffs(r, 18, 20, 2f, 3f, 5); move(r, 5, 2, 1.5f, 1); r.density = 2.5f; r.maxParticles = 500; r.renderDistance = 320; fire(r, .7f); });
        add(C, "ash_cloud", "Пепловое облако", r -> { col(r, .35f, .33f, .32f, .95f); area(r, Shape.SPHERE, 16, 6, 16); puffs(r, 6, 25, 3f, 4f, 3); move(r, .5f, .2f, .1f, .6f); r.windStrength = 2; r.density = 1.4f; r.renderDistance = 320; });
        add(C, "wind_dust", "Пыль от ветра", r -> { col(r, .72f, .62f, .45f, .6f); end(r, .78f, .7f, .55f); area(r, Shape.BOX, 12, 1, 12); puffs(r, 16, 5, .6f, 1f, 3); move(r, 4, 0, 0, .8f); r.dir = new float[]{1, 0, 0}; r.windStrength = 3; r.gravity = .05f; r.temperature = 0; r.density = .6f; r.fromBottom = true; });
        add(C, "sand_haze", "Песчаная дымка", r -> { field(r, 64, 10, 64, .3f, .15f, .7f); col(r, .85f, .75f, .55f, 1); r.speed = 1.2f; r.turbulence = .6f; });
        add(C, "dust_clouds", "Поднимающиеся облака пыли", r -> { col(r, .6f, .52f, .4f, .8f); area(r, Shape.DISC, 4, .2f, 4); puffs(r, 16, 6, .5f, .8f, 3.5f); move(r, 1.5f, .3f, 0, .6f); r.pressure = 1.5f; r.gravity = .3f; r.temperature = .5f; });
        add(C, "dirty_smog", "Мусорный смог", r -> { field(r, 32, 8, 32, .9f, .2f, 1); col(r, .47f, .42f, .33f, 1); r.contrast = 1.2f; r.turbulence = 1; r.speed = .3f; });

        add(D, "explosion", "Дым после взрыва", r -> { col(r, .18f, .16f, .15f, 1); end(r, .45f, .44f, .43f); area(r, Shape.SPHERE, 1, 1, 1); puffs(r, 0, 12, .8f, 1.4f, 4); move(r, 0, 2, .3f, .8f); r.burst = true; r.burstCount = 60; r.pressure = 9; r.drag = 1.6f; r.cooling = .4f; r.expandCurve = 3; r.density = 2; fire(r, 1.2f); });
        add(D, "shock_ring", "Ударная волна с кольцом", r -> { col(r, .6f, .58f, .55f, .9f); area(r, Shape.RING, 1, .1f, 1); puffs(r, 0, 5, .6f, .9f, 3); move(r, 0, .3f, 0, .5f); r.burst = true; r.burstCount = 70; r.pressure = 12; r.drag = 2; r.expandCurve = 3; r.density = .9f; r.collide = false; });
        add(D, "mushroom", "Грибовидное облако", r -> { col(r, .3f, .27f, .25f, 1); end(r, .55f, .52f, .5f); area(r, Shape.SPHERE, 2, 2, 2); puffs(r, 0, 25, 1.2f, 2f, 4); move(r, 14, 4, 1, .6f); r.burst = true; r.burstCount = 120; r.pressure = 2.5f; r.drag = .8f; r.cooling = .3f; r.vorticity = 1.2f; r.collide = false; r.density = 2; r.maxParticles = 400; r.renderDistance = 400; fire(r, 1.5f); });
        add(D, "gunshot", "Дым от выстрела", r -> { col(r, .8f, .8f, .8f, .8f); area(r, Shape.POINT, .05f, .05f, .05f); puffs(r, 0, 2.5f, .1f, .15f, 5); move(r, 6, .3f, .1f, .4f); r.dir = new float[]{1, 0, 0}; r.burst = true; r.burstCount = 10; r.drag = 4; r.pressure = 1.5f; r.density = .8f; r.collide = false; });
        add(D, "engine", "Дым от двигателя", r -> { col(r, .25f, .25f, .26f, .9f); area(r, Shape.POINT, .2f, .2f, .2f); puffs(r, 25, 3, .15f, .25f, 5); move(r, 3, .5f, .2f, .8f); r.dir = new float[]{-1, 0, 0}; r.density = 1.2f; });
        add(D, "trail", "След за объектом", r -> { col(r, .6f, .6f, .62f, .8f); area(r, Shape.POINT, .2f, .2f, .2f); puffs(r, 30, 4, .25f, .4f, 3); move(r, .2f, .2f, .1f, .4f); r.density = .9f; r.temperature = .3f; });
        add(D, "smoke_screen", "Дымовая завеса", r -> { col(r, .9f, .9f, .9f, 1); area(r, Shape.BOX, 10, 2, 2); puffs(r, 30, 25, 1f, 1.5f, 3); move(r, .5f, .1f, 0, .5f); r.pressure = 2; r.gravity = .05f; r.drag = 1.2f; r.density = 1.5f; r.maxParticles = 600; });
        add(D, "huge_cloud", "Огромное облако дыма", r -> { col(r, .5f, .5f, .5f, 1); area(r, Shape.SPHERE, 12, 8, 12); puffs(r, 10, 30, 3f, 5f, 2); move(r, .2f, .1f, 0, .6f); r.density = 1.4f; r.maxParticles = 400; r.renderDistance = 320; });
        add(D, "room_fill", "Заполняет комнату", r -> { col(r, .45f, .44f, .43f, .9f); area(r, Shape.POINT, .3f, .3f, .3f); puffs(r, 15, 40, .8f, 1.2f, 3); move(r, .5f, .8f, .3f, .4f); r.surfaceSpread = 1; r.density = 1; r.maxParticles = 600; r.dissipation = .01f; });
        add(D, "door_leak", "Выходит из двери", r -> { col(r, .55f, .55f, .56f, .85f); area(r, Shape.BOX, 1, 2, .1f); puffs(r, 8, 8, .3f, .5f, 4); move(r, .8f, .4f, .2f, .4f); r.dir = new float[]{0, 0, 1}; r.density = .8f; r.fromBottom = false; });
        add(D, "crack_leak", "Выходит из щелей", r -> { col(r, .9f, .9f, .92f, .6f); area(r, Shape.LINE, 2, .05f, .05f); puffs(r, 10, 4, .1f, .2f, 5); move(r, .8f, 1, .3f, .5f); r.density = .7f; r.fromBottom = false; });
        add(D, "smoke_vortex", "Дымовой вихрь", r -> { col(r, .5f, .5f, .52f, .9f); area(r, Shape.DISC, 4, .3f, 4); puffs(r, 14, 8, .5f, .8f, 3); move(r, .5f, 1.5f, .5f, .5f); r.vorticity = 3; r.curlScale = .3f; r.density = 1.1f; });
        add(D, "spinning_column", "Вращающийся столб", r -> { col(r, .2f, .2f, .21f, 1); area(r, Shape.DISC, 1.5f, .3f, 1.5f); puffs(r, 16, 7, .4f, .6f, 2.5f); move(r, .5f, 2.5f, 1, .5f); r.vorticity = 4; r.distortion = 1.2f; r.density = 1.6f; });

        add(E, "mystic_black", "Чёрный мистический дым", r -> { col(r, .03f, .02f, .05f, 1); end(r, .08f, .04f, .12f); puffs(r, 10, 6, .4f, .6f, 3); move(r, .4f, .5f, .2f, .9f); r.motion = Motion.MAGICAL; r.vorticity = .5f; r.absorption = 2; r.scattering = .3f; r.density = 2.2f; r.emission = .06f; r.emissionColor = new float[]{.5f, .1f, .9f}; });
        add(E, "magic_purple", "Фиолетовый магический", r -> { col(r, .55f, .2f, .85f, .9f); end(r, .3f, .1f, .6f); puffs(r, 12, 5, .3f, .5f, 3); move(r, .6f, .6f, .3f, .7f); r.motion = Motion.MAGICAL; r.emission = .6f; r.emissionColor = new float[]{.7f, .3f, 1}; r.lightResponse = .5f; r.density = 1; });
        add(E, "dark_aura", "Тёмная дымовая аура", r -> { col(r, .05f, .03f, .07f, 1); area(r, Shape.SPHERE, 1, 2, 1); puffs(r, 30, 2.5f, .3f, .5f, 2.5f); move(r, .3f, .4f, .6f, .6f); r.followSource = true; r.density = 1.6f; r.collide = false; r.motion = Motion.MAGICAL; });
        add(E, "character_aura", "Дым вокруг персонажа", r -> { col(r, .6f, .62f, .7f, .8f); area(r, Shape.RING, 2.4f, .2f, 2.4f); puffs(r, 20, 3, .3f, .5f, 2); move(r, .2f, .3f, .2f, .4f); r.followSource = true; r.vorticity = 2; r.density = .7f; r.collide = false; });
        add(E, "anti_gravity", "Против гравитации (течёт вниз)", r -> { col(r, .35f, .45f, .7f, .9f); puffs(r, 10, 8, .4f, .6f, 3); move(r, .5f, -2, -.3f, .5f); r.dir = new float[]{0, -1, 0}; r.fromBottom = false; r.surfaceSpread = 1; r.emission = .2f; r.emissionColor = new float[]{.3f, .5f, 1}; });
        add(E, "portal", "Вращающийся портал", r -> { col(r, .4f, .15f, .7f, .9f); area(r, Shape.RING, 4, .1f, 4); r.rotation = new float[]{90, 0, 0}; puffs(r, 40, 3, .3f, .5f, 1.5f); move(r, 0, 0, 0, .3f); r.pressure = -.6f; r.vorticity = 4; r.drag = 2; r.temperature = 0; r.followSource = true; r.collide = false; r.emission = .8f; r.emissionColor = new float[]{.6f, .2f, 1}; r.density = 1; r.maxParticles = 300; });
        add(E, "attracted", "Притягивается к объекту", r -> { col(r, .1f, .05f, .15f, 1); area(r, Shape.SPHERE, 10, 10, 10); puffs(r, 25, 4, .3f, .5f, .6f); move(r, 0, 0, 0, .3f); r.pressure = -3; r.drag = .5f; r.temperature = 0; r.collide = false; r.density = 1.2f; r.fromBottom = false; });
        add(E, "wave", "Распространяется волной", r -> { col(r, .5f, .6f, .8f, .9f); area(r, Shape.RING, 1, .1f, 1); puffs(r, 0, 4, .4f, .6f, 3); move(r, 0, -.2f, 0, .4f); r.burst = true; r.burstCount = 40; r.burstInterval = 2; r.pressure = 6; r.drag = 1.2f; r.gravity = .2f; r.emission = .2f; r.emissionColor = new float[]{.4f, .6f, 1}; });
        add(E, "creeping", "Ползёт по земле", r -> { col(r, .08f, .1f, .06f, 1); area(r, Shape.DISC, 3, .2f, 3); puffs(r, 12, 12, .5f, .8f, 3); move(r, .8f, -.6f, 0, .5f); r.dir = new float[]{1, 0, 0}; r.gravity = .3f; r.surfaceSpread = 1; r.temperature = .5f; r.density = 1.8f; });
        add(E, "funnel", "Формирует воронку", r -> { col(r, .3f, .3f, .35f, .95f); area(r, Shape.RING, 6, .2f, 6); puffs(r, 30, 5, .4f, .6f, .6f); move(r, 0, 0, 2, .4f); r.pressure = -1; r.vorticity = 5; r.temperature = 0; r.collide = false; r.density = 1.2f; r.maxParticles = 300; });
        add(E, "glowing", "Светящийся магический", r -> { col(r, .4f, .8f, .9f, .85f); puffs(r, 10, 5, .3f, .5f, 3); move(r, .6f, .5f, .3f, .6f); r.emission = 1.2f; r.emissionColor = new float[]{.3f, .9f, 1}; r.lightResponse = .3f; r.density = .8f; r.motion = Motion.MAGICAL; });
        add(E, "color_shift", "Дым с изменяющимся цветом", r -> { col(r, 1, .3f, .3f, .9f); end(r, 1, .3f, .3f); puffs(r, 10, 6, .4f, .6f, 3); move(r, .8f, .8f, .3f, .6f); r.hueShift = 60; r.emission = .5f; r.emissionColor = new float[]{1, .4f, .4f}; r.density = 1; });
    }

    /** Resets r's look/motion to the built-in or user preset id; placement and animation stay. */
    public static boolean apply(FogRegion r, String id) {
        FogRegion t = template(id);
        if (t == null) return false;
        copyParams(t, r);
        r.preset = id;
        return true;
    }

    public static FogRegion create(String id) {
        FogRegion r = new FogRegion();
        if (apply(r, id)) r.name = displayName(id);
        return r;
    }

    public static String displayName(String id) {
        Preset p = BUILTIN.get(id);
        return p != null ? p.name : id.startsWith("user:") ? id.substring(5) : id;
    }

    /** Fresh emitter with the preset applied, used for "reset" defaults. */
    public static FogRegion template(String id) {
        if (id == null || id.isEmpty()) return new FogRegion();
        Preset p = BUILTIN.get(id);
        if (p != null) {
            FogRegion t = new FogRegion();
            p.fn.accept(t);
            return t;
        }
        if (id.startsWith("user:")) return FogStore.read(userDir().resolve(id.substring(5) + ".json"), FogRegion.class);
        return null;
    }

    static void copyParams(FogRegion from, FogRegion to) {
        try {
            for (Field f : FogRegion.class.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isTransient(m) || KEEP.contains(f.getName())) continue;
                Object v = f.get(from);
                if (v instanceof float[] a) v = a.clone();
                f.set(to, v);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ user presets

    public static Path userDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("irl-fog").resolve("presets");
    }

    public static List<String> userPresets() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(userDir())) return out;
        try (Stream<Path> s = Files.list(userDir())) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted()
                    .forEach(n -> out.add(n.substring(0, n.length() - 5)));
        } catch (Exception e) {
            FogStore.LOG.error("Не удалось прочитать пресеты", e);
        }
        return out;
    }

    public static String safeName(String s) {
        String n = s.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.isEmpty() ? "preset" : n;
    }

    public static void saveUser(String name, FogRegion r) throws Exception {
        Files.createDirectories(userDir());
        FogRegion c = FogStore.GSON.fromJson(FogStore.GSON.toJson(r), FogRegion.class);
        c.tracks.clear();
        c.attach = FogRegion.Attach.WORLD;
        c.attachUuid = c.attachName = "";
        try (Writer w = Files.newBufferedWriter(userDir().resolve(safeName(name) + ".json"))) {
            FogStore.GSON.toJson(c, w);
        }
    }

    public static void renameUser(String from, String to) throws Exception {
        Files.move(userDir().resolve(from + ".json"), userDir().resolve(safeName(to) + ".json"));
    }

    public static void deleteUser(String name) throws Exception {
        Files.deleteIfExists(userDir().resolve(name + ".json"));
    }

    public static void copyUser(String name) throws Exception {
        String n = name + " (копия)";
        int i = 2;
        while (Files.exists(userDir().resolve(n + ".json"))) n = name + " (копия " + i++ + ")";
        Files.copy(userDir().resolve(name + ".json"), userDir().resolve(n + ".json"));
    }

    public static String exportJson(String id) {
        FogRegion t = template(id);
        return t == null ? "" : FogStore.GSON.toJson(t);
    }

    /** Imports a preset from JSON text (one emitter object). Returns the new user preset name. */
    public static String importJson(String json, String name) throws Exception {
        FogRegion r;
        try (Reader rd = new java.io.StringReader(json)) {
            r = FogStore.GSON.fromJson(rd, FogRegion.class);
        }
        if (r == null) throw new IllegalArgumentException("пустой JSON");
        r.migrate();
        String n = safeName(name.isBlank() ? r.name : name);
        saveUser(n, r);
        return n;
    }

    private Presets() {}
}
