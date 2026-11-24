package dev.dronchik.irlfog;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dronchik.irlfog.FogRegion.Kind;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Volumetric smoke, drawn after LevelRenderer.renderLevel (so after a shader pack's final pass, before hand and GUI).
 * Every visible puff (a noise-carved sphere) and every fog field (an oriented box) is one screen-space rectangle
 * raymarched in shaders/core/smoke.fsh against the scene depth, into an own premultiplied RGBA target at the
 * profile's resolution; the target is then composited over the frame. No vertex buffers: the per-volume data lives
 * in a uniform buffer, 60 volumes per draw, ordered far to near.
 */
public final class SmokeRenderer {
    static final int VEC4S = 17, CHUNK = 60, STRIDE = 16384, FRAME_BYTES = 128 + 16 * 6 + 16 * 16;

    private static final RenderPipeline VOLUME = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("irlfog", "pipeline/smoke_volume"))
            .withVertexShader(Identifier.fromNamespaceAndPath("irlfog", "core/smoke"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("irlfog", "core/smoke"))
            .withUniform("SmokeFrame", UniformType.UNIFORM_BUFFER)
            .withUniform("SmokePuffs", UniformType.UNIFORM_BUFFER)
            .withSampler("DepthSampler")
            .withSampler("NoiseSampler")
            .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();

    private static final RenderPipeline COMPOSITE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("irlfog", "pipeline/smoke_composite"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("irlfog", "post/smoke_composite"))
            .withSampler("SmokeSampler")
            .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            .build();

    private static final class Item {
        FogRegion r;
        SmokeSim.@Nullable Puff p;
        float dist;
        final float[] rect = new float[4];
        float stepsMul;
    }

    private static final List<Item> POOL = new ArrayList<>(), ITEMS = new ArrayList<>();
    private static @Nullable TextureTarget target;
    private static @Nullable GpuTextureView noise;
    private static @Nullable GpuTexture noiseTex;
    private static @Nullable MappableRingBuffer frameUbo;
    private static @Nullable GpuBuffer puffBuf;
    private static long puffCap;
    private static @Nullable ByteBuffer staging;
    private static boolean failed;
    private static long frameNo;
    private static final Matrix4f VP = new Matrix4f(), INV = new Matrix4f();
    private static final Vector4f V4 = new Vector4f();
    private static final Matrix3f B = new Matrix3f();
    private static final float[] LIGHT = new float[7];
    private static float sunX, sunY = 1, sunZ, sunR, sunG, sunB;

    // stats
    public static int statDraws, statVolumes, targetW, targetH;
    public static float prepMs;
    public static String lastError = "";

    public static void afterLevel(Matrix4f modelView, Matrix4f projection, float realDt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || failed) return;
        Camera cam = mc.gameRenderer.getMainCamera();
        statDraws = statVolumes = 0;
        try {
            SmokeSim.update(mc, cam, realDt);
            boolean drew = FogStore.settings.enabled && draw(mc, cam, modelView, projection);
            SmokePerf.frame(drew);
        } catch (Throwable t) {
            failed = true;
            lastError = t.toString();
            FogStore.LOG.error("IRL Fog: рендер дыма отключён до перезапуска из-за ошибки", t);
        }
    }

    private static boolean draw(Minecraft mc, Camera cam, Matrix4f modelView, Matrix4f projection) {
        long t0 = System.nanoTime();
        ClientLevel level = mc.level;
        RenderTarget main = mc.getMainRenderTarget();
        if (main.getColorTexture() == null || main.getDepthTexture() == null) return false;
        Vec3 cp = cam.position();
        VP.set(projection).mul(modelView);
        INV.set(VP).invert();
        FrustumIntersection frustum = new FrustumIntersection(VP);
        SmokePerf.Quality q = SmokePerf.current();
        sky(level, cam);
        List<?> lights = IrlLights.all();

        int w = main.width, h = main.height;
        int sw = Math.max(1, Math.round(w * q.resolution())), sh = Math.max(1, Math.round(h * q.resolution()));
        float lodNear = FogStore.settings.lodNear, lodFar = FogStore.settings.lodFar;

        POOL.addAll(ITEMS);
        ITEMS.clear();
        for (FogRegion r : FogStore.REGIONS) {
            r.culledVisible = false;
            if (!r.enabled && r.puffs.isEmpty()) { r.cullReason = "выключен"; SmokeSim.cullDisabled++; continue; }
            double zd = SmokeSim.distanceToZone(r, cp);
            float reach = Math.min(FogStore.settings.spawnDistance, r.renderDistance);
            if (zd > reach) { r.cullReason = "дальность"; SmokeSim.cullDistance++; continue; }
            float lod = !FogStore.settings.lod ? 1 : zd < lodNear ? 1 : zd < lodFar ? 0.75f : 0.5f * SmokePerf.farDetailMul();
            lod *= Math.max(0.25f, r.qualityBias);
            if (r.kind == Kind.FIELD) {
                if (!r.enabled) { r.cullReason = "выключен"; SmokeSim.cullDisabled++; continue; }
                Item it = item(r, null);
                if (!fieldRect(r, cp, it.rect) || !frustumBox(frustum, r, cp)) { ITEMS.remove(ITEMS.size() - 1); r.cullReason = "вне кадра"; SmokeSim.cullFrustum++; continue; }
                it.dist = (float) closestDistance(r, cp);
                it.stepsMul = lod;
                r.culledVisible = true;
                r.cullReason = "";
                SmokeSim.statFields++;
                SmokeSim.statVisible++;
                continue;
            }
            if (r.puffs.isEmpty()) { r.cullReason = r.enabled ? "нет клубов" : "выключен"; SmokeSim.cullEmpty++; continue; }
            SmokeSim.prepare(r, level, cp, lights);
            float[] b = r.bounds;
            b[0] = b[1] = b[2] = Float.MAX_VALUE;
            b[3] = b[4] = b[5] = -Float.MAX_VALUE;
            int before = ITEMS.size();
            for (SmokeSim.Puff p : r.puffs) {
                float x = (float) (p.x - cp.x), y = (float) (p.y - cp.y), z = (float) (p.z - cp.z), rad = p.radius;
                b[0] = Math.min(b[0], (float) p.x - rad); b[1] = Math.min(b[1], (float) p.y - rad); b[2] = Math.min(b[2], (float) p.z - rad);
                b[3] = Math.max(b[3], (float) p.x + rad); b[4] = Math.max(b[4], (float) p.y + rad); b[5] = Math.max(b[5], (float) p.z + rad);
                if (p.alpha < 0.004f || !frustum.testSphere(x, y, z, rad)) continue;
                Item it = item(r, p);
                if (!sphereRect(x, y, z, rad, it.rect) || (it.rect[2] - it.rect[0]) * 0.5f * sw < 1.2f) {
                    ITEMS.remove(ITEMS.size() - 1);
                    continue;
                }
                float px = (it.rect[2] - it.rect[0]) * 0.5f * sw;
                it.dist = (float) Math.sqrt(x * x + y * y + z * z);
                it.stepsMul = lod * (px < 40 ? 0.6f : 1);
            }
            int n = ITEMS.size() - before;
            SmokeSim.statPuffs += n;
            if (n == 0) { r.cullReason = "вне кадра"; SmokeSim.cullFrustum++; }
            else { r.culledVisible = true; r.cullReason = ""; SmokeSim.statVisible++; }
        }
        ITEMS.sort((a, c) -> Float.compare(c.dist, a.dist));
        statVolumes = ITEMS.size();
        if (ITEMS.isEmpty()) {
            prepMs = (System.nanoTime() - t0) / 1e6f;
            return false;
        }

        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        if (noise == null) noise = noiseTexture(enc);
        if (frameUbo == null) frameUbo = new MappableRingBuffer(() -> "irlfog frame", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FRAME_BYTES);
        target = fit(target, sw, sh);
        targetW = sw;
        targetH = sh;
        frameNo++;

        int chunks = (ITEMS.size() + CHUNK - 1) / CHUNK;
        long bytes = (long) chunks * STRIDE;
        if (puffBuf == null || puffCap < bytes) {
            if (puffBuf != null) puffBuf.close();
            puffCap = Math.max(bytes, STRIDE * 8L);
            puffBuf = RenderSystem.getDevice().createBuffer(() -> "irlfog puffs", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, puffCap);
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc((int) puffCap);
        }
        ByteBuffer bb = staging;
        bb.clear();
        float time = (float) (SmokeSim.time % 4096.0);
        for (int i = 0; i < ITEMS.size(); i++) {
            bb.position((i / CHUNK) * STRIDE + (i % CHUNK) * VEC4S * 16);
            Item it = ITEMS.get(i);
            if (it.p != null) putPuff(bb, it, cp, q);
            else putField(bb, it, cp, q, level, lights, time);
        }
        bb.position(0).limit((int) bytes);
        enc.writeToBuffer(puffBuf.slice(0, bytes), bb);

        try (GpuBuffer.MappedView v = enc.mapBuffer(frameUbo.currentBuffer(), false, true)) {
            Std140Builder s = Std140Builder.intoBuffer(v.data());
            s.putMat4f(INV);
            s.putMat4f(VP);
            s.putVec4(sw, sh, 1f / sw, 1f / sh);
            s.putVec4(time, q.steps(), q.fieldSteps(), q.shadow());
            s.putVec4(q.octaves(), wrap(cp.x), (float) cp.y, wrap(cp.z));
            s.putVec4(sunX, sunY, sunZ, 0);
            s.putVec4(sunR, sunG, sunB, frameNo % 64);
            int masks = 0;
            List<ForceField> mlist = new ArrayList<>();
            for (ForceField f : FogStore.FIELDS) if (f.enabled && f.isMask() && mlist.size() < 8) mlist.add(f);
            for (ForceField f : mlist) if (f.kind == ForceField.Kind.MASK_ONLY) masks |= 1;
            s.putVec4(mlist.size(), masks, 0, 0);
            for (int i = 0; i < 8; i++) {
                if (i < mlist.size()) {
                    ForceField f = mlist.get(i);
                    s.putVec4((float) (f.center[0] - cp.x), (float) (f.center[1] - cp.y), (float) (f.center[2] - cp.z), f.kind == ForceField.Kind.MASK_HIDE ? 1 : 2);
                    s.putVec4(f.size[0] / 2, f.size[1] / 2, f.size[2] / 2, Math.max(0.01f, f.falloff));
                } else {
                    s.putVec4(0, 0, 0, 0);
                    s.putVec4(0, 0, 0, 0);
                }
            }
        }
        prepMs = (System.nanoTime() - t0) / 1e6f;

        var samplers = RenderSystem.getSamplerCache();
        SmokePerf.beginGpu(enc);
        enc.clearColorTexture(target.getColorTexture(), 0);
        try (RenderPass pass = enc.createRenderPass(() -> "irlfog smoke", target.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(VOLUME);
            pass.setUniform("SmokeFrame", frameUbo.currentBuffer());
            pass.bindTexture("DepthSampler", main.getDepthTextureView(), samplers.getClampToEdge(FilterMode.NEAREST));
            pass.bindTexture("NoiseSampler", noise, samplers.getRepeat(FilterMode.LINEAR));
            for (int c = 0; c < chunks; c++) {
                int n = Math.min(CHUNK, ITEMS.size() - c * CHUNK);
                pass.setUniform("SmokePuffs", puffBuf.slice((long) c * STRIDE, CHUNK * VEC4S * 16L));
                pass.draw(0, n * 6);
                statDraws++;
            }
        }
        try (RenderPass pass = enc.createRenderPass(() -> "irlfog composite", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(COMPOSITE);
            pass.bindTexture("SmokeSampler", target.getColorTextureView(), samplers.getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
            statDraws++;
        }
        SmokePerf.endGpu(enc);
        frameUbo.rotate();
        return true;
    }

    private static Item item(FogRegion r, SmokeSim.@Nullable Puff p) {
        Item it = POOL.isEmpty() ? new Item() : POOL.remove(POOL.size() - 1);
        it.r = r;
        it.p = p;
        ITEMS.add(it);
        return it;
    }

    // ------------------------------------------------------------------------------------------------ packing

    private static void putPuff(ByteBuffer b, Item it, Vec3 cp, SmokePerf.Quality q) {
        FogRegion r = it.r;
        SmokeSim.Puff p = it.p;
        float k = p.k;
        float[] c = color(r, k, p.jitter, p.age);
        float[] mm = SmokeSim.motion(r.motion);
        float vl = Math.max(1e-3f, SmokeSim.len(p.vx, p.vy, p.vz));
        float fire = r.fireGlow * Math.min(1, p.temp / Math.max(0.05f, r.temperature)) * (1 - k * 0.6f);
        vec(b, (float) (p.x - cp.x), (float) (p.y - cp.y), (float) (p.z - cp.z), p.radius);
        vec(b, c[0], c[1], c[2], p.alpha);
        vec(b, r.noiseScale, Math.min(r.noiseDetail, q.octaves()), r.contrast, r.edgeSoftness);
        vec(b, r.distortion, p.age * r.animSpeed * mm[1] + p.seed, p.seed, 0);
        vec(b, p.ar * r.lightTint[0], p.ag * r.lightTint[1], p.ab * r.lightTint[2], p.sunVis);
        vec(b, r.emissionColor[0] * r.emission, r.emissionColor[1] * r.emission, r.emissionColor[2] * r.emission, fire);
        if (p.lrange > 0) vec(b, (float) (p.lx - cp.x), (float) (p.ly - cp.y), (float) (p.lz - cp.z), p.lrange);
        else vec(b, 0, 0, 0, 0);
        vec(b, p.lr * r.lightTint[0], p.lg * r.lightTint[1], p.lb * r.lightTint[2], r.scattering);
        vec(b, r.absorption, r.anisotropy, r.selfShadow * (q.shadow() > 0 ? 1 : 0.8f), r.lightResponse);
        vec(b, p.vx / vl, p.vy / vl, p.vz / vl, 0);
        vec(b, r.fireColor[0], r.fireColor[1], r.fireColor[2], it.stepsMul);
        vec(b, r.density, r.softness, k, r.ambient);
        vec(b, r.lightTint[0], r.lightTint[1], r.lightTint[2], 0);
        vec(b, 0, 0, 0, 0);
        vec(b, 0, 0, 0, 0);
        vec(b, 0, 0, 0, 0);
        vec(b, it.rect[0], it.rect[1], it.rect[2], it.rect[3]);
    }

    private static void putField(ByteBuffer b, Item it, Vec3 cp, SmokePerf.Quality q, ClientLevel level, List<?> lights, float time) {
        FogRegion r = it.r;
        float[] c = color(r, 0, 0, time);
        r.basis(B);
        // light the field as seen from the camera: sample light where the camera's position falls into the box
        double sx = clamp(cp.x, r.center[0] - r.size[0] / 2, r.center[0] + r.size[0] / 2);
        double sy = clamp(cp.y, r.center[1] - r.size[1] / 2, r.center[1] + r.size[1] / 2);
        double sz = clamp(cp.z, r.center[2] - r.size[2] / 2, r.center[2] + r.size[2] / 2);
        BlockPos bp = BlockPos.containing(sx, sy, sz);
        float sky = r.glow ? 0 : FogStore.settings.sunLight ? level.getBrightness(LightLayer.SKY, bp) / 15f : 0;
        float block = r.glow || !FogStore.settings.blockLight ? 0 : level.getBrightness(LightLayer.BLOCK, bp) / 15f;
        block = block * block * 1.3f;
        float ar = r.glow ? 1 : SmokeSim.skyR * sky + block, ag = r.glow ? 1 : SmokeSim.skyG * sky + block * 0.78f, ab = r.glow ? 1 : SmokeSim.skyB * sky + block * 0.52f;
        boolean lit = !r.glow && IrlLights.strongest(sx, sy, sz, lights, LIGHT);
        float dl = Math.max(1e-4f, SmokeSim.len(r.dir[0], r.dir[1], r.dir[2]));
        float[] st = FogStore.settings.wind;
        float gl = Math.max(1e-4f, SmokeSim.len(st[0], st[1], st[2]));
        float fx = r.dir[0] / dl * r.speed + st[0] / gl * FogStore.settings.windStrength * r.windResponse;
        float fy = r.dir[1] / dl * r.speed + r.rise;
        float fz = r.dir[2] / dl * r.speed + st[2] / gl * FogStore.settings.windStrength * r.windResponse;
        float[] mm = SmokeSim.motion(r.motion);
        vec(b, r.center[0] - (float) cp.x, r.center[1] - (float) cp.y, r.center[2] - (float) cp.z, 1);
        vec(b, c[0], c[1], c[2], r.color[3] * Math.min(2, r.intensity));
        vec(b, r.noiseScale, Math.min(r.noiseDetail, q.octaves()), r.contrast, r.edgeSoftness);
        vec(b, r.distortion, time * r.animSpeed * mm[1] * (0.3f + r.turbulence * mm[0]), 0, 1);
        vec(b, ar * r.lightTint[0], ag * r.lightTint[1], ab * r.lightTint[2], sky);
        vec(b, r.emissionColor[0] * r.emission, r.emissionColor[1] * r.emission, r.emissionColor[2] * r.emission, 0);
        if (lit) vec(b, LIGHT[0] - (float) cp.x, LIGHT[1] - (float) cp.y, LIGHT[2] - (float) cp.z, LIGHT[6]);
        else vec(b, 0, 0, 0, 0);
        vec(b, LIGHT[3] * r.lightTint[0], LIGHT[4] * r.lightTint[1], LIGHT[5] * r.lightTint[2], r.scattering);
        vec(b, r.absorption, r.anisotropy, r.selfShadow * (q.shadow() > 0 ? 1 : 0), r.lightResponse);
        vec(b, r.size[0] / 2, r.size[1] / 2, r.size[2] / 2, r.heightFalloff);
        vec(b, r.fireColor[0], r.fireColor[1], r.fireColor[2], it.stepsMul);
        vec(b, r.density, r.softness, 0, r.ambient);
        vec(b, r.lightTint[0], r.lightTint[1], r.lightTint[2], 0);
        // rows of R^T (world -> local); flow in the spare w
        vec(b, B.m00, B.m01, B.m02, fx * time);
        vec(b, B.m10, B.m11, B.m12, fy * time);
        vec(b, B.m20, B.m21, B.m22, fz * time);
        vec(b, it.rect[0], it.rect[1], it.rect[2], it.rect[3]);
    }

    /** start → (mid) → end colour over the life, plus hue drift and per-puff jitter. */
    static float[] color(FogRegion r, float k, float jitter, float age) {
        float[] a = r.color, e = r.colorEnd;
        float cr, cg, cb;
        if (r.useMid) {
            float m = Math.max(0.01f, Math.min(0.99f, r.midPos));
            float[] s = k < m ? a : r.colorMid, t = k < m ? r.colorMid : e;
            float u = k < m ? k / m : (k - m) / (1 - m);
            cr = s[0] + (t[0] - s[0]) * u; cg = s[1] + (t[1] - s[1]) * u; cb = s[2] + (t[2] - s[2]) * u;
        } else {
            cr = a[0] + (e[0] - a[0]) * k; cg = a[1] + (e[1] - a[1]) * k; cb = a[2] + (e[2] - a[2]) * k;
        }
        if (r.hueShift != 0) {
            // rotate around the grey axis (cheap hue rotation)
            double th = Math.toRadians(r.hueShift * age), cs = Math.cos(th), sn = Math.sin(th), o = (1 - cs) / 3, s3 = sn / Math.sqrt(3);
            float nr = (float) (cr * (cs + o) + cg * (o - s3) + cb * (o + s3));
            float ng = (float) (cr * (o + s3) + cg * (cs + o) + cb * (o - s3));
            float nb = (float) (cr * (o - s3) + cg * (o + s3) + cb * (cs + o));
            cr = nr; cg = ng; cb = nb;
        }
        return new float[]{clamp01(cr + jitter), clamp01(cg + jitter), clamp01(cb + jitter)};
    }

    private static void vec(ByteBuffer b, float x, float y, float z, float w) {
        b.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
    }

    // ------------------------------------------------------------------------------------------------ geometry

    /** NDC rectangle covering the sphere's bounding cube; fullscreen if the camera is near or inside it. */
    private static boolean sphereRect(float x, float y, float z, float r, float[] out) {
        if (x * x + y * y + z * z < (r * 1.8f) * (r * 1.8f) + 0.2f) return full(out);
        return rect(out, x - r, y - r, z - r, x + r, y + r, z + r, null);
    }

    private static boolean fieldRect(FogRegion r, Vec3 cp, float[] out) {
        r.basis(B);
        if (r.contains(cp.x, cp.y, cp.z, 0.5)) return full(out);
        return rect(out, -r.size[0] / 2, -r.size[1] / 2, -r.size[2] / 2, r.size[0] / 2, r.size[1] / 2, r.size[2] / 2,
                new float[]{r.center[0] - (float) cp.x, r.center[1] - (float) cp.y, r.center[2] - (float) cp.z});
    }

    private static boolean rect(float[] out, float x0, float y0, float z0, float x1, float y1, float z1, float @Nullable [] orient) {
        float mnx = Float.MAX_VALUE, mny = Float.MAX_VALUE, mxx = -Float.MAX_VALUE, mxy = -Float.MAX_VALUE;
        Vector3f v = new Vector3f();
        for (int i = 0; i < 8; i++) {
            v.set((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1);
            if (orient != null) B.transform(v).add(orient[0], orient[1], orient[2]);
            VP.transform(V4.set(v, 1));
            if (V4.w < 0.05f) return full(out);
            float nx = V4.x / V4.w, ny = V4.y / V4.w;
            mnx = Math.min(mnx, nx); mny = Math.min(mny, ny); mxx = Math.max(mxx, nx); mxy = Math.max(mxy, ny);
        }
        if (mxx < -1 || mxy < -1 || mnx > 1 || mny > 1) return false;
        out[0] = Math.max(-1, mnx); out[1] = Math.max(-1, mny); out[2] = Math.min(1, mxx); out[3] = Math.min(1, mxy);
        return true;
    }

    private static boolean full(float[] out) {
        out[0] = -1; out[1] = -1; out[2] = 1; out[3] = 1;
        return true;
    }

    private static boolean frustumBox(FrustumIntersection f, FogRegion r, Vec3 cp) {
        float e = (float) Math.sqrt(r.size[0] * r.size[0] + r.size[1] * r.size[1] + r.size[2] * r.size[2]) / 2;
        return f.testSphere(r.center[0] - (float) cp.x, r.center[1] - (float) cp.y, r.center[2] - (float) cp.z, e);
    }

    private static double closestDistance(FogRegion r, Vec3 cp) {
        if (r.contains(cp.x, cp.y, cp.z, 0)) return 0;
        return SmokeSim.distanceToZone(r, cp);
    }

    // ------------------------------------------------------------------------------------------------ sky light

    /** Sun or moon direction and colour, sky ambient; daylight from the environment attributes like the sky. */
    private static void sky(ClientLevel level, Camera cam) {
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float rain = level.getRainLevel(partial);
        boolean hasSky = level.dimensionType().hasSkyLight();
        float sa = hasSky ? cam.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partial) * (float) (Math.PI / 180) : 0;
        float ma = hasSky ? cam.attributeProbe().getValue(EnvironmentAttributes.MOON_ANGLE, partial) * (float) (Math.PI / 180) : 0;
        float sy = (float) Math.cos(sa);
        float day = smooth(-0.1f, 0.25f, sy);
        float dim = 1 - 0.65f * rain;
        if (sy > -0.05f) {
            sunX = (float) -Math.sin(sa); sunY = sy; sunZ = 0;
            float noon = smooth(0f, 0.45f, sy);
            float i = 1.1f * day * dim;
            sunR = i * (1f); sunG = i * (0.55f + 0.41f * noon); sunB = i * (0.32f + 0.56f * noon);
        } else {
            sunX = (float) -Math.sin(ma); sunY = (float) Math.cos(ma); sunZ = 0;
            float i = 0.35f * (1 - day) * dim * smooth(-0.05f, 0.2f, sunY);
            sunR = i * 0.32f; sunG = i * 0.4f; sunB = i * 0.62f;
        }
        if (!hasSky) { sunR = sunG = sunB = 0; }
        SmokeSim.skyR = (0.05f + 0.5f * day) * dim;
        SmokeSim.skyG = (0.06f + 0.56f * day) * dim;
        SmokeSim.skyB = (0.09f + 0.63f * day) * dim;
    }

    // ------------------------------------------------------------------------------------------------ resources

    private static TextureTarget fit(@Nullable TextureTarget t, int w, int h) {
        if (t == null) return new TextureTarget("irlfog smoke", w, h, false);
        if (t.width != w || t.height != h) t.resize(w, h);
        return t;
    }

    /** 256² value-noise lattice: R = value, G = R of the next z slice (offset 37,17); one bilinear fetch = 3D noise. */
    private static GpuTextureView noiseTexture(CommandEncoder enc) {
        var dev = RenderSystem.getDevice();
        noiseTex = dev.createTexture("irlfog noise", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, 256, 256, 1, 1);
        byte[] r = new byte[256 * 256];
        new java.util.Random(0x5A0CE).nextBytes(r);
        ByteBuffer buf = MemoryUtil.memAlloc(256 * 256 * 4);
        try {
            for (int y = 0; y < 256; y++)
                for (int x = 0; x < 256; x++) {
                    int i = y * 256 + x;
                    buf.put(i * 4, r[i]);
                    buf.put(i * 4 + 1, r[((y + 17) & 255) * 256 + ((x + 37) & 255)]);
                    buf.put(i * 4 + 2, (byte) 0);
                    buf.put(i * 4 + 3, (byte) 255);
                }
            enc.writeToTexture(noiseTex, buf, NativeImage.Format.RGBA, 0, 0, 0, 0, 256, 256);
        } finally {
            MemoryUtil.memFree(buf);
        }
        return dev.createTextureView(noiseTex);
    }

    /** Rough VRAM in use by the smoke, for the debugger. */
    public static long vramBytes() {
        long v = 0;
        if (target != null) v += (long) target.width * target.height * 4;
        if (noiseTex != null) v += 256 * 256 * 4;
        v += puffCap + FRAME_BYTES * 3L;
        return v;
    }

    /** After an error: let the user retry from the debugger. */
    public static void retry() {
        failed = false;
        lastError = "";
    }

    public static boolean failed() {
        return failed;
    }

    public static void close() {
        if (target != null) target.destroyBuffers();
        target = null;
        if (noise != null) noise.close();
        if (noiseTex != null) noiseTex.close();
        noise = null;
        noiseTex = null;
        if (frameUbo != null) frameUbo.close();
        frameUbo = null;
        if (puffBuf != null) puffBuf.close();
        puffBuf = null;
        puffCap = 0;
        if (staging != null) MemoryUtil.memFree(staging);
        staging = null;
        SmokePerf.close();
    }

    private static float wrap(double v) {
        return (float) (v - Math.floor(v / 1024) * 1024);
    }

    private static double clamp(double v, double a, double b) {
        return Math.max(a, Math.min(b, v));
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static float smooth(float e0, float e1, float x) {
        float k = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return k * k * (3 - 2 * k);
    }

    private SmokeRenderer() {}
}
