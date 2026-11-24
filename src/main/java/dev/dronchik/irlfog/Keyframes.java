package dev.dronchik.irlfog;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Keyframe tracks on emitter parameters, evaluated at timeline time (seconds) before every simulation step. */
public final class Keyframes {
    public enum Ease { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, HOLD, CUSTOM }

    public static class Key {
        public float t;
        public float v;
        public Ease ease = Ease.EASE_IN_OUT;
        /** CUSTOM: cubic-bezier control points (like CSS), x clamped to 0..1 */
        public float[] bezier = {0.42f, 0f, 0.58f, 1f};

        public Key() {}

        public Key(float t, float v, Ease e) {
            this.t = t;
            this.v = v;
            this.ease = e;
        }
    }

    public enum Param {
        POS_X("Позиция X", r -> r.center[0], (r, v) -> r.center[0] = v),
        POS_Y("Позиция Y", r -> r.center[1], (r, v) -> r.center[1] = v),
        POS_Z("Позиция Z", r -> r.center[2], (r, v) -> r.center[2] = v),
        ROT_X("Поворот X", r -> r.rotation[0], (r, v) -> r.rotation[0] = v),
        ROT_Y("Поворот Y", r -> r.rotation[1], (r, v) -> r.rotation[1] = v),
        ROT_Z("Поворот Z", r -> r.rotation[2], (r, v) -> r.rotation[2] = v),
        SCALE_X("Масштаб X", r -> r.size[0], (r, v) -> r.size[0] = Math.max(0.05f, v)),
        SCALE_Y("Масштаб Y", r -> r.size[1], (r, v) -> r.size[1] = Math.max(0.05f, v)),
        SCALE_Z("Масштаб Z", r -> r.size[2], (r, v) -> r.size[2] = Math.max(0.05f, v)),
        DENSITY("Плотность", r -> r.density, (r, v) -> r.density = Math.max(0, v)),
        OPACITY("Прозрачность", r -> r.color[3], (r, v) -> r.color[3] = clamp01(v)),
        COLOR_R("Цвет R", r -> r.color[0], (r, v) -> r.color[0] = clamp01(v)),
        COLOR_G("Цвет G", r -> r.color[1], (r, v) -> r.color[1] = clamp01(v)),
        COLOR_B("Цвет B", r -> r.color[2], (r, v) -> r.color[2] = clamp01(v)),
        INTENSITY("Интенсивность", r -> r.intensity, (r, v) -> r.intensity = Math.max(0, v)),
        SPEED("Скорость", r -> r.speed, (r, v) -> r.speed = v),
        WIND_X("Ветер X", r -> r.wind[0], (r, v) -> r.wind[0] = v),
        WIND_Y("Ветер Y", r -> r.wind[1], (r, v) -> r.wind[1] = v),
        WIND_Z("Ветер Z", r -> r.wind[2], (r, v) -> r.wind[2] = v),
        WIND_STRENGTH("Сила ветра", r -> r.windStrength, (r, v) -> r.windStrength = v),
        TURBULENCE("Турбулентность", r -> r.turbulence, (r, v) -> r.turbulence = Math.max(0, v)),
        RATE("Скорость создания", r -> r.rate, (r, v) -> r.rate = Math.max(0, v)),
        EMISSION("Свечение", r -> r.emission, (r, v) -> r.emission = Math.max(0, v)),
        ENABLED("Включён", r -> r.enabled ? 1f : 0f, (r, v) -> r.enabled = v >= 0.5f);

        public final String label;
        public final Function<FogRegion, Float> get;
        public final BiConsumer<FogRegion, Float> set;

        Param(String label, Function<FogRegion, Float> get, BiConsumer<FogRegion, Float> set) {
            this.label = label;
            this.get = get;
            this.set = set;
        }
    }

    /** Writes every animated parameter of r for timeline time t. */
    public static void apply(FogRegion r, float t) {
        if (r.tracks.isEmpty()) return;
        if (r.loopAnim && r.loopLength > 0.01f && t > r.loopStart) t = r.loopStart + (t - r.loopStart) % r.loopLength;
        for (Map.Entry<String, List<Key>> e : r.tracks.entrySet()) {
            Param p;
            try {
                p = Param.valueOf(e.getKey());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            List<Key> keys = e.getValue();
            if (keys == null || keys.isEmpty()) continue;
            p.set.accept(r, sample(keys, t, p == Param.ENABLED));
        }
    }

    public static float sample(List<Key> keys, float t, boolean step) {
        Key first = keys.get(0);
        if (t <= first.t) return first.v;
        for (int i = 0; i < keys.size() - 1; i++) {
            Key a = keys.get(i), b = keys.get(i + 1);
            if (t >= b.t) continue;
            if (step || a.ease == Ease.HOLD) return a.v;
            float u = (t - a.t) / Math.max(1e-5f, b.t - a.t);
            return a.v + (b.v - a.v) * ease(a, u);
        }
        return keys.get(keys.size() - 1).v;
    }

    static float ease(Key k, float u) {
        return switch (k.ease) {
            case LINEAR -> u;
            case EASE_IN -> u * u * u;
            case EASE_OUT -> 1 - (1 - u) * (1 - u) * (1 - u);
            case EASE_IN_OUT -> u < 0.5f ? 4 * u * u * u : 1 - (float) Math.pow(-2 * u + 2, 3) / 2;
            case HOLD -> 0;
            case CUSTOM -> bezier(k.bezier, u);
        };
    }

    /** CSS-style cubic-bezier(x1, y1, x2, y2): solve x(s) = u by bisection, return y(s). */
    static float bezier(float[] b, float u) {
        float x1 = clamp01(b[0]), y1 = b[1], x2 = clamp01(b[2]), y2 = b[3];
        float lo = 0, hi = 1, s = u;
        for (int i = 0; i < 24; i++) {
            s = (lo + hi) / 2;
            if (cubic(x1, x2, s) < u) lo = s;
            else hi = s;
        }
        return cubic(y1, y2, s);
    }

    private static float cubic(float p1, float p2, float s) {
        float m = 1 - s;
        return 3 * m * m * s * p1 + 3 * m * s * s * p2 + s * s * s;
    }

    /** Adds or replaces the key at time t (within 1/40 s). */
    public static void setKey(FogRegion r, Param p, float t, float v, Ease ease) {
        List<Key> keys = r.tracks.computeIfAbsent(p.name(), k -> new java.util.ArrayList<>());
        for (Key k : keys)
            if (Math.abs(k.t - t) < 0.025f) {
                k.v = v;
                return;
            }
        keys.add(new Key(t, v, ease));
        keys.sort(Comparator.comparingDouble(k -> k.t));
    }

    static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private Keyframes() {}

    /** Self-check: java -cp ... dev.dronchik.irlfog.Keyframes */
    public static void main(String[] a) {
        List<Key> keys = new java.util.ArrayList<>(List.of(new Key(0, 0, Ease.LINEAR), new Key(2, 10, Ease.HOLD), new Key(4, 20, Ease.LINEAR)));
        check(sample(keys, -1, false) == 0, "before first");
        check(Math.abs(sample(keys, 1, false) - 5) < 1e-4, "linear mid");
        check(sample(keys, 3, false) == 10, "hold");
        check(sample(keys, 9, false) == 20, "after last");
        Key c = new Key(0, 0, Ease.CUSTOM);
        c.bezier = new float[]{0, 0, 1, 1};
        check(Math.abs(ease(c, 0.5f) - 0.5f) < 1e-3, "bezier symmetric");
        check(ease(new Key(0, 0, Ease.EASE_IN), 0.5f) < 0.5f && ease(new Key(0, 0, Ease.EASE_OUT), 0.5f) > 0.5f, "in/out");
        System.out.println("Keyframes OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
