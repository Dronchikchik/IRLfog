package dev.dronchik.irlfog;

import java.util.Collections;
import java.util.List;
import org.qualet.irlredactor.light.LightScene;
import org.qualet.irlredactor.light.PlacedLight;

/** Lights placed in IRL Redactor (J): the strongest one at a puff lights it with its colour (and spot cone). */
final class IrlLights {
    private static boolean broken;

    static List<?> all() {
        if (broken || !FogStore.settings.irlLights) return Collections.emptyList();
        try {
            return LightScene.all();
        } catch (Throwable t) {
            broken = true;
            FogStore.LOG.warn("IRL Fog: не удалось прочитать свет IRL Redactor, подсветка дыма им отключена", t);
            return Collections.emptyList();
        }
    }

    static float reach(PlacedLight l) {
        return Math.max(1, l.range > 0 ? l.range : l.radius > 0 ? l.radius : 8);
    }

    /** Picks the light with the largest contribution at the point; writes position, colour*intensity*cone, reach. */
    static boolean strongest(double x, double y, double z, List<?> lights, float[] out) {
        float best = 0;
        for (Object o : lights) {
            if (!(o instanceof PlacedLight l) || l.entitiesOnly) continue;
            float reach = reach(l);
            double dx = x - l.x, dy = y - l.y, dz = z - l.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d >= reach) continue;
            float att = (float) (1 - d / reach);
            att *= att;
            float cone = 1;
            if (l.type == PlacedLight.Type.SPOT && d > 1e-3) {
                float dl = (float) Math.sqrt(l.dirX * l.dirX + l.dirY * l.dirY + l.dirZ * l.dirZ);
                if (dl > 1e-4f) {
                    float c = (float) ((dx * l.dirX + dy * l.dirY + dz * l.dirZ) / (d * dl));
                    float co = (float) Math.cos(Math.toRadians(l.outerAngleDeg)), ci = (float) Math.cos(Math.toRadians(Math.min(l.innerAngleDeg, l.outerAngleDeg)));
                    cone = Math.max(0, Math.min(1, (c - co) / Math.max(1e-3f, ci - co)));
                }
            }
            float lum = (l.r + l.g + l.b) / 3 * l.intensity * cone;
            if (lum * att <= best) continue;
            best = lum * att;
            out[0] = (float) l.x; out[1] = (float) l.y; out[2] = (float) l.z;
            out[3] = l.r * l.intensity * cone; out[4] = l.g * l.intensity * cone; out[5] = l.b * l.intensity * cone;
            out[6] = reach;
        }
        return best > 0.002f;
    }

    private static final float[] TMP = new float[7];

    static void strongest(SmokeSim.Puff p, List<?> lights) {
        if (!strongest(p.x, p.y, p.z, lights, TMP)) return;
        p.lx = TMP[0]; p.ly = TMP[1]; p.lz = TMP[2];
        p.lr = TMP[3]; p.lg = TMP[4]; p.lb = TMP[5];
        p.lrange = TMP[6];
    }

    private IrlLights() {}
}
