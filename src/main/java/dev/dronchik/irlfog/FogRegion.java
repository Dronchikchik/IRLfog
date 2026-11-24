package dev.dronchik.irlfog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix3f;

/**
 * One smoke source. PUFFS = simulated volumetric puffs born inside the zone; FIELD = one raymarched density box (fog).
 * Fields up to "fadeOut" are the 1.0.x format and keep their names so old world files load unchanged (see migrate()).
 */
public class FogRegion {
    /** 1.0.x zone types, kept only to migrate old files. */
    public enum Type { NATURAL, DIRTY, SMOKE }
    public enum Kind { PUFFS, FIELD }
    public enum Shape { BOX, SPHERE, POINT, DISC, RING, LINE }
    public enum Motion { CALM, NATURAL, TURBULENT, CHAOTIC, VIOLENT, MAGICAL, SLOW_MOTION }
    public enum Attach { WORLD, ENTITY }

    // ---- 1.0.x fields (same names and meaning where it still applies)
    public String name = "Дым";
    public boolean enabled = true;
    public Type type = Type.SMOKE;
    // ponytail: float-координаты точны до ~0.06 блока на 1e6; double, если понадобятся дальние земли
    public float[] center = {0, 64, 0};
    public float[] size = {2, 1, 2};
    public boolean fromBottom = true;
    public boolean confine = false;

    public float[] color = {0.5f, 0.5f, 0.5f, 0.8f};
    public float[] colorEnd = {0.6f, 0.6f, 0.6f};
    public float colorJitter = 0.04f;
    /** ignores world darkness (fullbright), as in 1.0.x */
    public boolean glow = false;

    public float[] dir = {0, 1, 0};
    public float speed = 1f;
    public float turbulence = 0.4f;
    public float rise = 0.3f;
    public float spin = 0.5f;

    public float rate = 8f;
    public int maxParticles = 160;
    public float sizeMin = 0.6f;
    public float sizeMax = 1.0f;
    public float grow = 3f;
    public float life = 7f;
    public float fadeIn = 0.08f;
    public float fadeOut = 0.45f;

    // ---- 2.0
    public int version = 2;
    public String preset = "";
    public Kind kind = Kind.PUFFS;
    public Shape shape = Shape.DISC;
    public float[] rotation = {0, 0, 0};
    public String group = "";
    public float intensity = 1f;
    public Motion motion = Motion.NATURAL;

    // appearance
    public boolean useMid = false;
    public float[] colorMid = {0.4f, 0.4f, 0.4f};
    public float midPos = 0.5f;
    public float endAlpha = 1f;
    public float hueShift = 0f;
    public float density = 1.2f;
    public float softness = 0.5f;
    public float noiseScale = 1f;
    public int noiseDetail = 4;
    public float contrast = 1f;
    public float edgeSoftness = 0.35f;
    public float distortion = 0.6f;
    public float emission = 0f;
    public float[] emissionColor = {1f, 0.5f, 0.2f};
    public float animSpeed = 1f;
    /** fields: density falls off upwards (per block); 0 = uniform */
    public float heightFalloff = 0.25f;

    // physics
    public float gravity = 0f;
    public float buoyancy = 1.5f;
    public float temperature = 1f;
    public float cooling = 0.6f;
    public float[] wind = {1, 0, 0};
    public float windStrength = 0f;
    public float windResponse = 1f;
    public float curlScale = 0.35f;
    public float dissipation = 0.05f;
    /** how front-loaded the growth is: 0 = linear, high = explosive start then slow */
    public float expandCurve = 1f;
    public float drag = 0.8f;
    public float vorticity = 0f;
    public float diffusion = 0.15f;
    public float pressure = 0f;
    public boolean collide = true;
    public float surfaceSpread = 0.7f;
    public boolean interact = true;
    public boolean burst = false;
    public int burstCount = 40;
    public float burstInterval = 0f;
    /** puffs keep moving with the source (auras) instead of leaving a trail in the world */
    public boolean followSource = false;

    // lighting
    public float lightResponse = 1f;
    public float scattering = 1f;
    public float absorption = 1f;
    public float anisotropy = 0.35f;
    public float selfShadow = 1f;
    public float ambient = 1f;
    public float[] lightTint = {1, 1, 1};
    public float fireGlow = 0f;
    public float[] fireColor = {1f, 0.45f, 0.12f};

    // performance
    public float renderDistance = 192f;
    public float qualityBias = 1f;

    // attachment
    public Attach attach = Attach.WORLD;
    public String attachUuid = "";
    public String attachName = "";
    public float[] offset = {0, 0, 0};

    // animation (keyframes in timeline seconds)
    public Map<String, List<Keyframes.Key>> tracks = new LinkedHashMap<>();
    public boolean loopAnim = false;
    public float loopStart = 0f;
    public float loopLength = 10f;

    // ---- runtime
    public transient List<SmokeSim.Puff> puffs = new ArrayList<>();
    public transient float spawnAcc, burstClock;
    public transient boolean removed, wasEnabled, culledVisible;
    public transient String cullReason = "";
    public transient double srcX, srcY, srcZ, prevSrcX, prevSrcY, prevSrcZ;
    public transient boolean srcValid;
    public transient float[] bounds = new float[6];
    public transient float simAcc;
    public transient int frameSkip;
    public transient long id = NEXT_ID++;
    private static long NEXT_ID = 1;
    /** kept for the 1.0.x UI counter */
    public transient int alive;

    public static FogRegion preset(Type t) {
        FogRegion r = new FogRegion();
        r.type = t;
        r.version = 0;
        r.migrate();
        return r;
    }

    /** 1.0.x zones were vanilla-particle clouds; map them onto the closest new look. */
    public void migrate() {
        if (puffs == null) puffs = new ArrayList<>();
        if (tracks == null) tracks = new LinkedHashMap<>();
        if (bounds == null) bounds = new float[6];
        if (version >= 2) return;
        version = 2;
        String keepName = name;
        float[] c = center.clone(), s = size.clone();
        float[] col = color.clone(), colEnd = colorEnd.clone();
        float oldSpeed = speed, oldTurb = turbulence, oldRise = rise, oldRate = rate, oldLife = life;
        float[] oldDir = dir.clone();
        boolean oldGlow = glow, oldBottom = fromBottom, oldConfine = confine, oldEnabled = enabled;
        float oldMin = sizeMin, oldMax = sizeMax, oldGrow = grow, oldIn = fadeIn, oldOut = fadeOut;
        switch (type) {
            case NATURAL -> Presets.apply(this, "morning_fog");
            case DIRTY -> Presets.apply(this, "dirty_smog");
            case SMOKE -> Presets.apply(this, "campfire_small");
        }
        name = keepName;
        center = c;
        size = s;
        enabled = oldEnabled;
        glow = oldGlow;
        System.arraycopy(col, 0, color, 0, 3);
        colorEnd = colEnd;
        if (type == Type.SMOKE) {
            dir = oldDir;
            speed = oldSpeed;
            turbulence = Math.max(0.1f, oldTurb * 2.5f);
            rise = oldRise;
            // old quads were many small sprites; puffs are big and overlap, so fewer of them
            rate = Math.max(1, oldRate * 0.35f);
            life = oldLife;
            sizeMin = oldMin * 0.45f;
            sizeMax = oldMax * 0.45f;
            grow = oldGrow;
            fadeIn = oldIn;
            fadeOut = oldOut;
            fromBottom = oldBottom;
            confine = oldConfine;
            color[3] = Math.min(1, col[3]);
        } else {
            // 1.0.x alpha was per sprite with ~10 overlapping; a field takes it as overall density
            density = Math.min(2.5f, col[3] * 3.2f);
            color[3] = 1;
            dir = oldDir;
            speed = oldSpeed;
            turbulence = oldTurb * 4;
            noiseScale = Math.max(0.3f, 6f / Math.max(1, oldMax));
        }
    }

    public FogRegion copy() {
        FogRegion r = FogStore.GSON.fromJson(FogStore.GSON.toJson(this), FogRegion.class);
        r.migrate();
        r.name = name + " (копия)";
        return r;
    }

    /** Rotation basis in the same convention as the editor gizmo (see FogPanel.gizmo): R = Rz * Ry * Rx. */
    public Matrix3f basis(Matrix3f out) {
        return out.rotationZ((float) Math.toRadians(rotation[2]))
                .rotateY((float) Math.toRadians(rotation[1]))
                .rotateX((float) Math.toRadians(rotation[0]));
    }

    public boolean contains(double x, double y, double z, double pad) {
        org.joml.Vector3f l = new Matrix3f().set(basis(new Matrix3f())).transpose()
                .transform(new org.joml.Vector3f((float) (x - center[0]), (float) (y - center[1]), (float) (z - center[2])));
        return Math.abs(l.x) <= size[0] / 2 + pad && Math.abs(l.y) <= size[1] / 2 + pad && Math.abs(l.z) <= size[2] / 2 + pad;
    }
}
