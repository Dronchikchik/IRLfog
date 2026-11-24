package dev.dronchik.irlfog;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuQuery;
import dev.dronchik.irlfog.FogStore.Profile;
import java.util.ArrayDeque;
import java.util.OptionalLong;

/**
 * Quality profiles and the adaptive controller. The controller watches the measured GPU time of the smoke passes
 * (timer queries) and lowers quality in a fixed order: raymarch steps, internal resolution, shadows, far update
 * rate, far detail. It steps down after ~20 frames over budget and up only after ~2 s well under it, with a
 * cooldown between changes, so it does not flap.
 */
public final class SmokePerf {
    public record Quality(int steps, int fieldSteps, int shadow, int octaves, float resolution, float spawn) {}

    public static final int MAX_LEVEL = 6;
    public static int level;
    private static int over, under, cooldown;
    public static float gpuMs, gpuMsRaw, frameMs;
    public static boolean gpuTimerWorks = true;
    private static final ArrayDeque<GpuQuery> PENDING = new ArrayDeque<>();
    private static GpuQuery open;
    private static long lastFrameNs;

    // A/B comparison
    public static final String[] SLOT_NAMES = {"A", "B"};
    public static final float[][] SLOTS = new float[2][];
    private static int benchSlot = -1, benchFrames;
    private static double benchGpu, benchSim, benchFrame;
    public static final int BENCH_FRAMES = 180;

    public static Quality base() {
        FogStore.Settings s = FogStore.settings;
        return switch (s.profile) {
            case LOW -> new Quality(6, 16, 0, 2, 0.35f, 0.5f);
            case MEDIUM -> new Quality(10, 24, 0, 3, 0.5f, 0.75f);
            case HIGH -> new Quality(12, 32, 1, 3, 0.5f, 1f);
            case ULTRA -> new Quality(20, 56, 2, 4, 0.75f, 1f);
            case CINEMATIC -> new Quality(36, 96, 3, 5, 1f, 1.5f);
            case CUSTOM -> new Quality(s.customSteps, s.customFieldSteps, s.customShadowSteps, s.customOctaves, s.customResolution, 1f);
        };
    }

    /** Base profile with the adaptive drops applied. */
    public static Quality current() {
        Quality q = base();
        if (!FogStore.settings.adaptive || FogStore.settings.profile == Profile.CINEMATIC) return q;
        float steps = q.steps, fsteps = q.fieldSteps, res = q.resolution;
        int shadow = q.shadow, oct = q.octaves;
        if (level >= 1) { steps *= 0.75f; fsteps *= 0.75f; }
        if (level >= 2) { steps *= 0.67f; fsteps *= 0.67f; }
        if (level >= 3) res = Math.max(0.3f, res * 0.7f);
        if (level >= 4) shadow = 0;
        if (level >= 6) oct = Math.max(1, oct - 1);
        return new Quality(Math.max(4, Math.round(steps)), Math.max(8, Math.round(fsteps)), shadow, oct, res, q.spawn);
    }

    public static float spawnMul() { return base().spawn; }
    public static int farUpdateMul() { return level >= 5 && FogStore.settings.adaptive ? 2 : 1; }
    public static float simStepMul() { return level >= 5 && FogStore.settings.adaptive ? 1.5f : 1f; }
    /** far detail drop: extra LOD on steps for distant smoke at the last level */
    public static float farDetailMul() { return level >= 6 && FogStore.settings.adaptive ? 0.7f : 1f; }

    // ------------------------------------------------------------------------------------------------ timing

    public static void beginGpu(CommandEncoder enc) {
        if (!gpuTimerWorks || open != null) return;
        try {
            open = enc.timerQueryBegin();
        } catch (Throwable t) {
            gpuTimerWorks = false;
        }
    }

    public static void endGpu(CommandEncoder enc) {
        if (open == null) return;
        try {
            enc.timerQueryEnd(open);
            PENDING.add(open);
        } catch (Throwable t) {
            gpuTimerWorks = false;
            open.close();
        }
        open = null;
    }

    /** Once per frame: collect finished queries, run the controller and the A/B capture. */
    public static void frame(boolean drewSomething) {
        long now = System.nanoTime();
        if (lastFrameNs != 0) frameMs = frameMs * 0.9f + (now - lastFrameNs) / 1e6f * 0.1f;
        lastFrameNs = now;
        while (!PENDING.isEmpty()) {
            OptionalLong v = PENDING.peek().getValue();
            if (v.isEmpty()) {
                if (PENDING.size() > 6) PENDING.poll().close(); // never resolves on this driver
                break;
            }
            PENDING.poll().close();
            gpuMsRaw = v.getAsLong() / 1e6f;
            gpuMs = gpuMs * 0.9f + gpuMsRaw * 0.1f;
        }
        if (!drewSomething) gpuMs *= 0.95f;
        control();
        if (benchSlot >= 0) {
            benchGpu += gpuMsRaw;
            benchSim += SmokeSim.simMs;
            benchFrame += frameMs;
            if (++benchFrames >= BENCH_FRAMES) {
                SLOTS[benchSlot] = new float[]{(float) (benchGpu / benchFrames), (float) (benchSim / benchFrames), (float) (benchFrame / benchFrames),
                        SmokeSim.statPuffs, SmokeSim.statVisible, level};
                benchSlot = -1;
            }
        }
    }

    private static void control() {
        if (cooldown > 0) cooldown--;
        if (!FogStore.settings.adaptive || FogStore.settings.profile == Profile.CINEMATIC) {
            level = 0;
            return;
        }
        float budget = Math.max(0.3f, FogStore.settings.budgetMs);
        float t = gpuTimerWorks ? gpuMs : SmokeSim.simMs;
        over = t > budget * 1.05f ? over + 1 : 0;
        under = t < budget * 0.55f ? under + 1 : 0;
        if (over >= 20 && cooldown == 0 && level < MAX_LEVEL) {
            level++;
            over = 0;
            cooldown = 60;
        } else if (under >= 120 && cooldown == 0 && level > 0) {
            level--;
            under = 0;
            cooldown = 120;
        }
    }

    public static void startBench(int slot) {
        benchSlot = slot;
        benchFrames = 0;
        benchGpu = benchSim = benchFrame = 0;
    }

    public static int benchProgress() {
        return benchSlot < 0 ? -1 : benchFrames * 100 / BENCH_FRAMES;
    }

    public static void close() {
        PENDING.forEach(GpuQuery::close);
        PENDING.clear();
        if (open != null) open.close();
        open = null;
    }

    private SmokePerf() {}
}
