package dev.dronchik.irlfog;

/** Scene-wide zones acting on every puff inside them (or only on one group). MASK_* zones cut the rendered smoke. */
public class ForceField {
    public enum Kind { WIND, TURBULENCE, ATTRACTOR, VORTEX, MASK_HIDE, MASK_ONLY }

    public String name = "Поле";
    public boolean enabled = true;
    public Kind kind = Kind.WIND;
    public float[] center = {0, 64, 0};
    /** box size for WIND / TURBULENCE / masks; ATTRACTOR and VORTEX use size[0] / 2 as radius */
    public float[] size = {16, 8, 16};
    /** WIND direction, VORTEX axis */
    public float[] dir = {1, 0, 0};
    /** blocks/s for wind, accel for attractor (negative = repel), swirl speed for vortex */
    public float strength = 2f;
    /** VORTEX: pull towards the axis; TURBULENCE: noise frequency */
    public float extra = 0.3f;
    /** soft edge of the zone, 0..1 of its size */
    public float falloff = 0.3f;
    /** only puffs of emitters in this group ("" = all) */
    public String group = "";

    public static ForceField of(Kind k) {
        ForceField f = new ForceField();
        f.kind = k;
        f.name = switch (k) {
            case WIND -> "Зона ветра";
            case TURBULENCE -> "Поле турбулентности";
            case ATTRACTOR -> "Точка притяжения";
            case VORTEX -> "Вихрь";
            case MASK_HIDE -> "Маска: скрыть дым";
            case MASK_ONLY -> "Маска: только здесь";
        };
        switch (k) {
            case TURBULENCE -> { f.strength = 1.5f; f.extra = 0.4f; }
            case ATTRACTOR -> { f.size = new float[]{12, 12, 12}; f.strength = 1.5f; }
            case VORTEX -> { f.size = new float[]{10, 10, 10}; f.dir = new float[]{0, 1, 0}; f.strength = 3f; f.extra = 0.4f; }
            case MASK_HIDE, MASK_ONLY -> { f.size = new float[]{6, 6, 6}; f.falloff = 0.15f; }
            default -> {}
        }
        return f;
    }

    public boolean appliesTo(FogRegion r) {
        return enabled && (group.isEmpty() || group.equals(r.group));
    }

    public boolean isMask() {
        return kind == Kind.MASK_HIDE || kind == Kind.MASK_ONLY;
    }

    /** 1 inside, fading to 0 across the soft edge, 0 outside. */
    public float weight(double x, double y, double z) {
        double dx = Math.abs(x - center[0]), dy = Math.abs(y - center[1]), dz = Math.abs(z - center[2]);
        if (kind == Kind.ATTRACTOR || kind == Kind.VORTEX) {
            double r = size[0] / 2, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return edge(d, r);
        }
        return Math.min(edge(dx, size[0] / 2), Math.min(edge(dy, size[1] / 2), edge(dz, size[2] / 2)));
    }

    private float edge(double d, double half) {
        if (d >= half) return 0;
        double soft = Math.max(1e-3, half * falloff);
        return (float) Math.min(1, (half - d) / soft);
    }
}
