package dev.dronchik.irlfog;

import dev.dronchik.irlfog.FogRegion.Kind;
import dev.dronchik.irlfog.FogRegion.Shape;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * CPU side of the smoke: a pool of puffs per emitter, advanced on the render thread every frame in fixed substeps.
 * Motion is a cheap visual approximation (drag towards the local air velocity + buoyancy + curl noise + zones +
 * block collision), not CFD. Time comes from the timeline: scene time (world ticks, follows Flashback rewinds),
 * real time or the editor's own preview clock. A jump in time re-simulates the last seconds ("seek").
 */
public final class SmokeSim {
    public static final class Puff {
        public double x, y, z;
        public float vx, vy, vz, age, life, r0, seed, temp, jitter, hue;
        public float radius, alpha, k;
        // lighting cache (refreshed every few frames)
        public float ar, ag, ab, sunVis, lx, ly, lz, lr, lg, lb, lrange;
        public boolean outside;
        int lightTick;
    }

    public enum TimeMode { SCENE, REAL, PREVIEW }

    private static final ArrayDeque<Puff> POOL = new ArrayDeque<>();
    private static final RandomSource RNG = RandomSource.create();
    private static final Long2ByteOpenHashMap SOLID = new Long2ByteOpenHashMap();
    private static final BlockPos.MutableBlockPos BP = new BlockPos.MutableBlockPos();
    private static final Matrix3f BASIS = new Matrix3f();
    private static final Vector3f TMP = new Vector3f();
    private static final float[] CURL = new float[3];

    // ---- timeline / preview controls (editor)
    public static boolean previewMode, previewPlaying = true, paused;
    public static float previewTime, timeScale = 1f;
    public static double time;
    private static double lastTime = Double.NaN;
    private static boolean seekRequested = true;
    private static long frame;

    // ---- stats for the debugger
    public static int statEmitters, statActive, statVisible, statPuffs, statFields;
    public static int cullDisabled, cullDistance, cullFrustum, cullEmpty;
    public static float simMs;

    // interacting movers, gathered once per frame
    private static final List<float[]> MOVERS = new ArrayList<>();

    public static void requestSeek() {
        seekRequested = true;
    }

    public static void restart(FogRegion r) {
        release(r);
        r.wasEnabled = false;
        r.spawnAcc = 0;
        r.burstClock = 0;
    }

    public static void releaseAll() {
        for (FogRegion r : FogStore.REGIONS) release(r);
        MOVERS.clear();
        SOLID.clear();
        lastTime = Double.NaN;
    }

    private static void release(FogRegion r) {
        POOL.addAll(r.puffs);
        r.puffs.clear();
        r.alive = 0;
    }

    /** Timeline time in seconds for this frame. */
    private static double timelineTime(Minecraft mc, float realDt) {
        if (previewMode) {
            if (previewPlaying && !paused) previewTime += realDt * timeScale;
            return previewTime;
        }
        if (FogStore.settings.timeSource == FogStore.TimeSource.SCENE && mc.level != null) {
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            return (mc.level.getGameTime() + partial) / 20.0;
        }
        double t = Double.isNaN(lastTime) ? 0 : lastTime;
        return mc.isPaused() || paused ? t : t + realDt * timeScale;
    }

    public static TimeMode timeMode() {
        return previewMode ? TimeMode.PREVIEW : FogStore.settings.timeSource == FogStore.TimeSource.SCENE ? TimeMode.SCENE : TimeMode.REAL;
    }

    /** Called once per rendered frame before drawing. */
    public static void update(Minecraft mc, Camera cam, float realDt) {
        long t0 = System.nanoTime();
        frame++;
        ClientLevel level = mc.level;
        if (level == null) return;
        if (frame % 30 == 0) SOLID.clear();
        time = timelineTime(mc, realDt);
        double dtTimeline = Double.isNaN(lastTime) ? 0 : time - lastTime;
        // tick + partial tick can step back a hair at a tick boundary: that is jitter, not a rewind
        boolean seek = seekRequested || Double.isNaN(lastTime) || dtTimeline < -0.25 || dtTimeline > 1.5;
        seekRequested = false;
        if (!seek && dtTimeline < 0) time = lastTime;
        lastTime = time;
        float dt = (float) (seek ? 0 : Math.max(0, dtTimeline));
        if (timeMode() == TimeMode.SCENE) dt *= paused ? 0 : timeScale;
        if (mc.isPaused()) dt = 0;

        gatherMovers(mc, cam);
        Vec3 cp = cam.position();
        float maxDist = FogStore.settings.spawnDistance;
        statEmitters = FogStore.REGIONS.size();
        statActive = statVisible = statPuffs = statFields = 0;
        cullDisabled = cullDistance = cullFrustum = cullEmpty = 0;

        for (FogRegion r : FogStore.REGIONS) {
            Keyframes.apply(r, (float) time);
            resolveSource(mc, r);
            if (r.enabled) statActive++;
            double dist = distanceToZone(r, cp);
            float reach = Math.min(maxDist, r.renderDistance);
            if (dist > reach * 1.25 + 8) {
                // far away: not simulated at all; a fresh prewarm happens when the camera comes back
                if (!r.puffs.isEmpty()) release(r);
                r.srcValid = false;
                r.cullReason = "дальность";
                continue;
            }
            if (r.kind == Kind.FIELD) {
                if (!r.puffs.isEmpty()) release(r);
                continue;
            }
            if (seek || !r.srcValid) {
                prewarm(r, (float) time);
                continue;
            }
            // distant emitters are advanced less often, with the accumulated time
            r.simAcc += dt;
            boolean far = FogStore.settings.lod && dist > FogStore.settings.lodFar;
            int every = far ? Math.max(1, FogStore.settings.farUpdateEvery * SmokePerf.farUpdateMul()) : 1;
            if (++r.frameSkip < every) continue;
            r.frameSkip = 0;
            advance(r, r.simAcc, (float) time);
            r.simAcc = 0;
        }
        simMs = (System.nanoTime() - t0) / 1e6f;
    }

    private static void advance(FogRegion r, float dt, float now) {
        if (dt <= 0) {
            r.prevSrcX = r.srcX; r.prevSrcY = r.srcY; r.prevSrcZ = r.srcZ;
            return;
        }
        float step = Math.max(0.01f, FogStore.settings.simStep) * SmokePerf.simStepMul();
        int n = Math.min(8, Math.max(1, (int) Math.ceil(dt / step)));
        float h = dt / n;
        for (int i = 0; i < n; i++) step(r, h, now - dt + h * (i + 1), (float) i / n, (float) (i + 1) / n);
        r.prevSrcX = r.srcX; r.prevSrcY = r.srcY; r.prevSrcZ = r.srcZ;
    }

    /** Re-simulates the last seconds so a seek, a rewind or a newly visible emitter shows settled smoke at once. */
    private static void prewarm(FogRegion r, float now) {
        release(r);
        r.srcValid = true;
        r.prevSrcX = r.srcX; r.prevSrcY = r.srcY; r.prevSrcZ = r.srcZ;
        boolean hasKeys = !r.tracks.isEmpty();
        // a one-shot burst without keys has no position on the timeline: it fires on creation / restart only
        if (r.burst && !hasKeys) return;
        float window = Math.min(r.life * 1.1f, 15f);
        float start = now - window;
        if (hasKeys) Keyframes.apply(r, start);
        r.wasEnabled = r.enabled;
        float h = Math.max(0.05f, window / 120f);
        for (float t = start + h; t <= now + 1e-4f; t += h) {
            if (hasKeys) Keyframes.apply(r, t);
            step(r, h, t, 1, 1);
        }
        if (hasKeys) Keyframes.apply(r, now);
    }

    // ------------------------------------------------------------------------------------------------ stepping

    private static void step(FogRegion r, float dt, float now, float a0, float a1) {
        boolean on = r.enabled && FogStore.settings.enabled;
        float budget = r.maxParticles * FogStore.settings.quality * SmokePerf.spawnMul();
        if (r.burst) {
            if (on && !r.wasEnabled) spawn(r, r.burstCount, a0, a1, budget * 2);
            if (on && r.burstInterval > 0.05f) {
                r.burstClock += dt;
                while (r.burstClock >= r.burstInterval) {
                    r.burstClock -= r.burstInterval;
                    spawn(r, r.burstCount, a0, a1, budget * 2);
                }
            }
        }
        r.wasEnabled = on;
        if (on && r.rate > 0) {
            r.spawnAcc += r.rate * r.intensity * FogStore.settings.quality * SmokePerf.spawnMul() * dt;
            int n = (int) r.spawnAcc;
            r.spawnAcc -= n;
            spawn(r, n, a0, a1, budget);
        }
        integrate(r, dt, now);
    }

    private static void spawn(FogRegion r, int n, float a0, float a1, float budget) {
        if (n <= 0) return;
        r.basis(BASIS);
        float[] s = r.size;
        float dl = len(r.dir[0], r.dir[1], r.dir[2]);
        for (int i = 0; i < n && r.puffs.size() < budget; i++) {
            Puff p = POOL.isEmpty() ? new Puff() : POOL.pop();
            float lx, ly, lz;
            float hy = r.fromBottom ? -s[1] / 2 + RNG.nextFloat() * Math.min(0.5f, s[1] * 0.15f) : (RNG.nextFloat() - 0.5f) * s[1];
            switch (r.shape) {
                case SPHERE -> {
                    float u, v, w;
                    do { u = RNG.nextFloat() * 2 - 1; v = RNG.nextFloat() * 2 - 1; w = RNG.nextFloat() * 2 - 1; } while (u * u + v * v + w * w > 1);
                    lx = u * s[0] / 2; ly = r.fromBottom ? hy : v * s[1] / 2; lz = w * s[2] / 2;
                }
                case POINT -> { lx = (RNG.nextFloat() - 0.5f) * 0.05f; ly = (RNG.nextFloat() - 0.5f) * 0.05f; lz = (RNG.nextFloat() - 0.5f) * 0.05f; }
                case DISC -> {
                    double a = RNG.nextDouble() * Math.PI * 2, rr = Math.sqrt(RNG.nextDouble());
                    lx = (float) (Math.cos(a) * rr) * s[0] / 2; ly = hy; lz = (float) (Math.sin(a) * rr) * s[2] / 2;
                }
                case RING -> {
                    double a = RNG.nextDouble() * Math.PI * 2;
                    lx = (float) Math.cos(a) * s[0] / 2; ly = (RNG.nextFloat() - 0.5f) * s[1]; lz = (float) Math.sin(a) * s[2] / 2;
                }
                case LINE -> { lx = (RNG.nextFloat() - 0.5f) * s[0]; ly = (RNG.nextFloat() - 0.5f) * s[1]; lz = (RNG.nextFloat() - 0.5f) * s[2]; }
                default -> { lx = (RNG.nextFloat() - 0.5f) * s[0]; ly = hy; lz = (RNG.nextFloat() - 0.5f) * s[2]; }
            }
            // radial direction for pressure: from the source centre (random for a point)
            float rx = lx, ry = r.shape == Shape.RING || r.shape == Shape.DISC ? 0 : ly, rz = lz;
            float rl = len(rx, ry, rz);
            if (rl < 1e-3f || r.shape == Shape.POINT) {
                rx = RNG.nextFloat() * 2 - 1; ry = RNG.nextFloat() * 2 - 1; rz = RNG.nextFloat() * 2 - 1;
                rl = Math.max(1e-3f, len(rx, ry, rz));
            }
            rx /= rl; ry /= rl; rz /= rl;
            float sp = r.speed * (0.8f + RNG.nextFloat() * 0.4f) / Math.max(1e-4f, dl);
            float pr = r.pressure * (0.7f + RNG.nextFloat() * 0.6f);
            float df = r.diffusion;
            float vx = r.dir[0] * sp + rx * pr + (RNG.nextFloat() - 0.5f) * df;
            float vy = r.dir[1] * sp + ry * pr + (RNG.nextFloat() - 0.5f) * df;
            float vz = r.dir[2] * sp + rz * pr + (RNG.nextFloat() - 0.5f) * df;
            BASIS.transform(TMP.set(lx, ly, lz));
            float ox = TMP.x, oy = TMP.y, oz = TMP.z;
            BASIS.transform(TMP.set(vx, vy, vz));
            float a = a0 + (a1 - a0) * RNG.nextFloat();
            p.x = r.prevSrcX + (r.srcX - r.prevSrcX) * a + ox;
            p.y = r.prevSrcY + (r.srcY - r.prevSrcY) * a + oy;
            p.z = r.prevSrcZ + (r.srcZ - r.prevSrcZ) * a + oz;
            p.vx = TMP.x; p.vy = TMP.y; p.vz = TMP.z;
            p.age = 0;
            p.life = Math.max(0.2f, r.life * (0.8f + RNG.nextFloat() * 0.4f));
            p.r0 = Math.max(0.02f, r.sizeMin + (r.sizeMax - r.sizeMin) * RNG.nextFloat());
            p.seed = RNG.nextFloat() * 97f;
            p.temp = r.temperature * (0.85f + RNG.nextFloat() * 0.3f);
            p.jitter = (RNG.nextFloat() - 0.5f) * 2 * r.colorJitter;
            p.hue = 0;
            p.lightTick = -1;
            p.outside = false;
            r.puffs.add(p);
        }
    }

    private static void integrate(FogRegion r, float dt, float now) {
        ClientLevel level = Minecraft.getInstance().level;
        FogStore.Settings st = FogStore.settings;
        float gw = len(st.wind[0], st.wind[1], st.wind[2]);
        float gwx = gw < 1e-4f ? 0 : st.wind[0] / gw * st.windStrength, gwy = gw < 1e-4f ? 0 : st.wind[1] / gw * st.windStrength,
                gwz = gw < 1e-4f ? 0 : st.wind[2] / gw * st.windStrength;
        float ew = len(r.wind[0], r.wind[1], r.wind[2]);
        float wx = gwx + (ew < 1e-4f ? 0 : r.wind[0] / ew * r.windStrength), wy = gwy + (ew < 1e-4f ? 0 : r.wind[1] / ew * r.windStrength),
                wz = gwz + (ew < 1e-4f ? 0 : r.wind[2] / ew * r.windStrength);
        wx *= r.windResponse; wy *= r.windResponse; wz *= r.windResponse;
        float[] mm = motion(r.motion);
        float turb = r.turbulence * mm[0];
        float drag = 1 - (float) Math.exp(-Math.max(0, r.drag) * dt);
        float cool = (float) Math.exp(-r.cooling * dt);
        r.basis(BASIS);
        float axX = BASIS.m10, axY = BASIS.m11, axZ = BASIS.m12;
        float vort = r.vorticity + mm[2];
        double followX = 0, followY = 0, followZ = 0;
        if (r.followSource) {
            followX = r.srcX - r.prevSrcX; followY = r.srcY - r.prevSrcY; followZ = r.srcZ - r.prevSrcZ;
        }
        List<ForceField> fields = FogStore.FIELDS;
        List<Puff> list = r.puffs;
        for (int i = list.size() - 1; i >= 0; i--) {
            Puff p = list.get(i);
            p.age += dt;
            if (p.age >= p.life) {
                POOL.push(list.remove(i));
                continue;
            }
            float ax = wx, ay = wy + r.rise, az = wz;
            float accX = 0, accY = 0, accZ = 0;
            if (turb > 0) {
                curl((float) p.x * r.curlScale + p.seed * 0.1f, (float) p.y * r.curlScale, (float) p.z * r.curlScale, now * r.animSpeed * mm[1] + p.seed);
                ax += CURL[0] * turb; ay += CURL[1] * turb * 0.7f; az += CURL[2] * turb;
            }
            if (vort != 0) {
                float qx = (float) (p.x - r.srcX), qy = (float) (p.y - r.srcY), qz = (float) (p.z - r.srcZ);
                float along = qx * axX + qy * axY + qz * axZ;
                qx -= axX * along; qy -= axY * along; qz -= axZ * along;
                float d = len(qx, qy, qz);
                if (d > 1e-3f) {
                    float s = vort / (1 + d * 0.25f);
                    ax += (axY * qz - axZ * qy) / d * s; ay += (axZ * qx - axX * qz) / d * s; az += (axX * qy - axY * qx) / d * s;
                }
            }
            for (int f = 0; f < fields.size(); f++) {
                ForceField ff = fields.get(f);
                if (ff.isMask() || !ff.appliesTo(r)) continue;
                float w = ff.weight(p.x, p.y, p.z);
                if (w <= 0) continue;
                float dx = ff.center[0] - (float) p.x, dy = ff.center[1] - (float) p.y, dz = ff.center[2] - (float) p.z;
                float fl = len(ff.dir[0], ff.dir[1], ff.dir[2]);
                float nx = fl < 1e-4f ? 0 : ff.dir[0] / fl, ny = fl < 1e-4f ? 1 : ff.dir[1] / fl, nz = fl < 1e-4f ? 0 : ff.dir[2] / fl;
                switch (ff.kind) {
                    case WIND -> { ax += nx * ff.strength * w; ay += ny * ff.strength * w; az += nz * ff.strength * w; }
                    case TURBULENCE -> {
                        curl((float) p.x * ff.extra, (float) p.y * ff.extra, (float) p.z * ff.extra, now * 1.3f + f);
                        ax += CURL[0] * ff.strength * w; ay += CURL[1] * ff.strength * w; az += CURL[2] * ff.strength * w;
                    }
                    case ATTRACTOR -> {
                        float d = Math.max(0.3f, len(dx, dy, dz));
                        accX += dx / d * ff.strength * w; accY += dy / d * ff.strength * w; accZ += dz / d * ff.strength * w;
                    }
                    case VORTEX -> {
                        float along = -(dx * nx + dy * ny + dz * nz);
                        float qx = -dx - nx * along, qy = -dy - ny * along, qz = -dz - nz * along;
                        float d = len(qx, qy, qz);
                        if (d > 1e-3f) {
                            float s = ff.strength * w;
                            ax += (ny * qz - nz * qy) / d * s - qx / d * ff.extra * s;
                            ay += (nz * qx - nx * qz) / d * s - qy / d * ff.extra * s;
                            az += (nx * qy - ny * qx) / d * s - qz / d * ff.extra * s;
                        }
                    }
                    default -> {}
                }
            }
            if (r.interact) interact(p, dt);
            p.vx += (ax - p.vx) * drag + accX * dt;
            p.vy += (ay - p.vy) * drag + accY * dt + (r.buoyancy * p.temp - r.gravity) * dt;
            p.vz += (az - p.vz) * drag + accZ * dt;
            p.temp *= cool;
            if (r.diffusion > 0) {
                float j = r.diffusion * dt * 2;
                p.vx += (RNG.nextFloat() - 0.5f) * j; p.vy += (RNG.nextFloat() - 0.5f) * j; p.vz += (RNG.nextFloat() - 0.5f) * j;
            }
            float rad = radius(r, p);
            double nx = p.x + p.vx * dt + followX, ny = p.y + p.vy * dt + followY, nz = p.z + p.vz * dt + followZ;
            if (r.collide && level != null) {
                // probe the puff's leading edge, so it stops with its side at the wall and does not sink into it
                float e = Math.min(rad * 0.6f, 1.5f);
                if (solid(level, nx + Math.signum(p.vx) * e, p.y, p.z)) { nx = p.x; p.vx *= -0.15f; }
                if (solid(level, nx, ny + Math.signum(p.vy) * e, p.z)) {
                    // ceilings and floors turn vertical motion into a spread along the surface
                    float hv = Math.abs(p.vy) * r.surfaceSpread;
                    float hl = len(p.vx, 0, p.vz);
                    float dx, dz;
                    if (hl > 0.05f) { dx = p.vx / hl; dz = p.vz / hl; }
                    else { double a = p.seed * 7.13; dx = (float) Math.cos(a); dz = (float) Math.sin(a); }
                    p.vx += dx * hv; p.vz += dz * hv;
                    p.vy *= -0.1f;
                    ny = p.y;
                }
                if (solid(level, nx, ny, nz + Math.signum(p.vz) * e)) { nz = p.z; p.vz *= -0.15f; }
            }
            p.x = nx; p.y = ny; p.z = nz;
            if (r.confine) {
                p.outside = !r.contains(p.x, p.y, p.z, 0);
                if (p.outside && !r.contains(p.x, p.y, p.z, 2)) {
                    POOL.push(list.remove(i));
                    continue;
                }
            } else p.outside = false;
        }
        r.alive = list.size();
    }

    /** CALM .. SLOW_MOTION: turbulence multiplier, animation speed multiplier, extra swirl, time scale. */
    static float[] motion(FogRegion.Motion m) {
        return switch (m == null ? FogRegion.Motion.NATURAL : m) {
            case CALM -> new float[]{0.4f, 0.6f, 0, 1};
            case NATURAL -> new float[]{1, 1, 0, 1};
            case TURBULENT -> new float[]{1.8f, 1.3f, 0, 1};
            case CHAOTIC -> new float[]{3f, 1.8f, 0.6f, 1};
            case VIOLENT -> new float[]{4.5f, 2.5f, 0, 1};
            case MAGICAL -> new float[]{1.2f, 1f, 0.8f, 1};
            case SLOW_MOTION -> new float[]{0.7f, 0.3f, 0, 0.3f};
        };
    }

    /** Divergence-free flow: curl of a sum of sine potentials, two rotated octaves; result roughly in -1..1. */
    static void curl(float x, float y, float z, float t) {
        float cx = 1.65f * cos(3.3f * y - t + 1.3f) - 1.3f * cos(1.3f * z + 1.1f * t + 0.4f);
        float cy = 1.55f * cos(3.1f * z - 1.3f * t + 2.1f) - 1.9f * cos(1.9f * x + 0.8f * t + 5.2f);
        float cz = 1.45f * cos(2.9f * x + 0.7f * t + 3.3f) - 1.7f * cos(1.7f * y + 0.9f * t + 0.7f);
        // second octave in a rotated frame (rotate in, evaluate, rotate back: still divergence-free)
        float u = 0.8f * y + 0.6f * z, v = -0.8f * x + 0.36f * y - 0.48f * z, w = -0.6f * x - 0.48f * y + 0.64f * z;
        u *= 2.3f; v *= 2.3f; w *= 2.3f;
        float dx = 1.65f * cos(3.3f * v - t * 1.7f) - 1.3f * cos(1.3f * w + t);
        float dy = 1.55f * cos(3.1f * w - t * 1.4f + 1) - 1.9f * cos(1.9f * u + t * 0.6f + 2);
        float dz = 1.45f * cos(2.9f * u + t * 1.2f + 4) - 1.7f * cos(1.7f * v + t * 0.5f + 3);
        CURL[0] = (cx + 0.5f * (-0.8f * dy - 0.6f * dz)) / 3.5f;
        CURL[1] = (cy + 0.5f * (0.8f * dx + 0.36f * dy - 0.48f * dz)) / 3.5f;
        CURL[2] = (cz + 0.5f * (0.6f * dx - 0.48f * dy + 0.64f * dz)) / 3.5f;
    }

    private static float cos(float a) {
        return (float) Math.cos(a);
    }

    private static boolean solid(ClientLevel level, double x, double y, double z) {
        BP.set(Math.floor(x), Math.floor(y), Math.floor(z));
        long key = BP.asLong();
        byte v = SOLID.get(key);
        if (v == 0) {
            BlockState s = level.getBlockState(BP);
            v = (byte) (s.blocksMotion() || !s.getFluidState().isEmpty() ? 2 : 1);
            SOLID.put(key, v);
        }
        return v == 2;
    }

    // ------------------------------------------------------------------------------------------------ interaction

    private static void gatherMovers(Minecraft mc, Camera cam) {
        MOVERS.clear();
        if (mc.level == null) return;
        Vec3 cp = cam.position();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (MOVERS.size() >= 24) break;
            if (e.distanceToSqr(cp) > 64 * 64) continue;
            float vx = (float) (e.getX() - e.xo) * 20, vy = (float) (e.getY() - e.yo) * 20, vz = (float) (e.getZ() - e.zo) * 20;
            if (vx * vx + vy * vy + vz * vz < 0.3f) continue;
            MOVERS.add(new float[]{(float) e.getX(), (float) e.getY(), (float) e.getZ(), vx, vy, vz, e.getBbWidth() * 0.6f + 0.3f, e.getBbHeight()});
        }
    }

    /** A body moving through smoke drags it along and pushes it aside, which reads as a swirl behind it. */
    private static void interact(Puff p, float dt) {
        for (int i = 0; i < MOVERS.size(); i++) {
            float[] m = MOVERS.get(i);
            float dx = (float) p.x - m[0], dz = (float) p.z - m[2], dy = (float) p.y - (m[1] + m[7] * 0.5f);
            float reach = m[6] + p.radius;
            float h = (float) Math.sqrt(dx * dx + dz * dz);
            if (h > reach || Math.abs(dy) > m[7] * 0.5f + p.radius) continue;
            float w = 1 - h / reach;
            float k = Math.min(1, 6 * dt) * w;
            p.vx += (m[3] * 0.7f - p.vx) * k + (h > 1e-3f ? dx / h : 0) * 2.5f * w * dt;
            p.vy += (m[4] * 0.5f - p.vy) * k * 0.5f;
            p.vz += (m[5] * 0.7f - p.vz) * k + (h > 1e-3f ? dz / h : 0) * 2.5f * w * dt;
        }
    }

    // ------------------------------------------------------------------------------------------------ attachment

    private static void resolveSource(Minecraft mc, FogRegion r) {
        if (r.attach == FogRegion.Attach.ENTITY && mc.level != null && !r.attachUuid.isEmpty()) {
            Entity e = findEntity(mc, r.attachUuid);
            if (e != null) {
                float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
                Vec3 pos = e.getPosition(partial);
                r.center[0] = (float) pos.x + r.offset[0];
                r.center[1] = (float) pos.y + r.offset[1];
                r.center[2] = (float) pos.z + r.offset[2];
            }
        }
        r.srcX = r.center[0];
        r.srcY = r.center[1];
        r.srcZ = r.center[2];
    }

    private static Entity cachedEntity;

    static Entity findEntity(Minecraft mc, String uuid) {
        if (cachedEntity != null && !cachedEntity.isRemoved() && cachedEntity.level() == mc.level
                && cachedEntity.getStringUUID().equals(uuid)) return cachedEntity;
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (Entity e : mc.level.entitiesForRendering())
            if (e.getUUID().equals(id)) return cachedEntity = e;
        return null;
    }

    // ------------------------------------------------------------------------------------------------ per frame

    public static float radius(FogRegion r, Puff p) {
        float k = Math.min(1, p.age / p.life);
        float e = Math.max(0, r.expandCurve);
        float f = e < 0.01f ? k : (float) ((1 - Math.exp(-e * 3 * k)) / (1 - Math.exp(-e * 3)));
        return p.r0 * (1 + (r.grow - 1) * f);
    }

    /** Fills radius/alpha/k of every puff and refreshes its lighting every few frames. */
    static void prepare(FogRegion r, ClientLevel level, Vec3 cam, List<?> lights) {
        int i = 0;
        for (Puff p : r.puffs) {
            float k = Math.min(1, p.age / p.life);
            p.k = k;
            p.radius = radius(r, p);
            float a = 1;
            if (r.fadeIn > 0 && k < r.fadeIn) a = k / r.fadeIn;
            if (r.fadeOut > 0 && k > 1 - r.fadeOut) a = Math.min(a, (1 - k) / r.fadeOut);
            a = a * a * (3 - 2 * a);
            a *= (float) Math.exp(-r.dissipation * p.age) * (1 + (r.endAlpha - 1) * k);
            if (p.outside) a *= 0.5f;
            p.alpha = a * r.color[3] * Math.min(2, r.intensity);
            if (p.lightTick < 0 || (frame + i) % 4 == 0) light(r, p, level, lights);
            i++;
        }
    }

    static float skyR, skyG, skyB;

    private static void light(FogRegion r, Puff p, ClientLevel level, List<?> lights) {
        p.lightTick = (int) frame;
        if (r.glow) {
            p.ar = p.ag = p.ab = 1;
            p.sunVis = 0;
            p.lrange = 0;
            return;
        }
        BP.set(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
        if (solid(level, p.x, p.y, p.z)) BP.move(0, 1, 0);
        float sky = FogStore.settings.sunLight ? level.getBrightness(LightLayer.SKY, BP) / 15f : 0;
        float block = FogStore.settings.blockLight ? level.getBrightness(LightLayer.BLOCK, BP) / 15f : 0;
        block = block * block * 1.3f;
        p.sunVis = sky;
        p.ar = skyR * sky + block;
        p.ag = skyG * sky + block * 0.78f;
        p.ab = skyB * sky + block * 0.52f;
        p.lrange = 0;
        if (lights != null && !lights.isEmpty()) IrlLights.strongest(p, lights);
    }

    static double distanceToZone(FogRegion r, Vec3 c) {
        double hx = r.size[0] / 2, hy = r.size[1] / 2, hz = r.size[2] / 2;
        double rad = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double d = Math.sqrt((c.x - r.center[0]) * (c.x - r.center[0]) + (c.y - r.center[1]) * (c.y - r.center[1]) + (c.z - r.center[2]) * (c.z - r.center[2]));
        return Math.max(0, d - rad);
    }

    static float len(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private SmokeSim() {}
}
