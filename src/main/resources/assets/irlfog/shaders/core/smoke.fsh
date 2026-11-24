#version 330

#moj_import <irlfog:smoke_common.glsl>

// Raymarches one volume (puff sphere or fog box) along the pixel's view ray, from where the ray enters it to where
// it leaves it or hits the scene depth. Output is premultiplied (colour, coverage) into the smoke target.
// Puff density = sphere falloff carved by domain-warped fbm in the puff's own frame (so each puff billows
// independently); field density = height falloff * soft box edges * world-space fbm drifting with the wind.

uniform sampler2D DepthSampler;

flat in int vIdx;
out vec4 fragColor;

const float PI = 3.14159265;
const float EXT_PUFF = 1.5;   // extinction per block at density 1
const float EXT_FIELD = 0.5;

vec3 unproject(vec2 uv, float depth) {
    vec4 p = InvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

float ign(vec2 px) {
    px += SunColor.w * 5.588238;
    return fract(52.9829189 * fract(0.06711056 * px.x + 0.00583715 * px.y));
}

// Henyey-Greenstein, normalised so isotropic = 1
float phase(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * c, 1e-4), 1.5);
}

// soft roll-off instead of a hard clip at 1, so sunlit white smoke keeps its shading
vec3 shoulder(vec3 c) {
    float m = max(max(c.r, c.g), c.b);
    return m > 0.75 ? c * ((0.75 + (1.0 - exp(-(m - 0.75) * 4.0)) * 0.25) / m) : c;
}

float maskAt(vec3 p) {
    int n = int(MaskInfo.x + 0.5);
    if (n == 0) return 1.0;
    float only = MaskInfo.y > 0.5 ? 0.0 : 1.0;
    float m = 1.0;
    for (int i = 0; i < 8; i++) {
        if (i >= n) break;
        vec4 a = Masks[i * 2], b = Masks[i * 2 + 1];
        vec3 d = abs(p - a.xyz);
        vec3 e = clamp((b.xyz - d) / max(b.xyz * b.w, vec3(1e-3)), 0.0, 1.0);
        float w = e.x * e.y * e.z;
        if (a.w < 1.5) m *= 1.0 - w;
        else only = max(only, w);
    }
    return m * only;
}

// ------------------------------------------------------------------------------------------------ puffs

// The domain warp is evaluated once per ray (warpFor) and reused by every sample: most of the shape change for
// a fraction of the noise fetches. Shadow rays use puffSoft: one fetch, no detail.
vec3 puffNoisePos(int i, vec3 q) {
    vec4 nz = P(i, 2), misc = P(i, 3);
    return q * (2.1 * nz.x) + vec3(misc.z, misc.z * 0.37, -misc.z * 0.71) - P(i, 9).xyz * misc.y * 0.12;
}

vec3 warpFor(int i, vec3 q) {
    vec3 np = puffNoisePos(i, q) * 0.8;
    float anim = P(i, 3).y;
    vec2 a = vec2(sm_noise(np + vec3(0.0, anim * 0.21, 0.0)), sm_noise(np + vec3(17.3, -anim * 0.17, 5.1))) - 0.5;
    return vec3(a.x, a.y, a.x - a.y) * P(i, 3).x * 1.6;
}

float puffDensity(int i, vec3 q, vec3 warp) {
    float r2 = dot(q, q);
    if (r2 >= 1.0) return 0.0;
    vec4 nz = P(i, 2), den = P(i, 11);
    float base = 1.0 - sqrt(r2);
    float anim = P(i, 3).y;
    float n = sm_fbm(puffNoisePos(i, q) + warp + vec3(0.0, -anim * 0.25, 0.0), int(nz.y + 0.5));
    n = mix(n, 0.5, den.y * 0.6);
    // billows: noise eats into the sphere from the outside, and thins the inside so it is not a solid ball
    float shape = base * 1.15 + (n - 0.6) * nz.z * 1.6;
    float d = smoothstep(0.0, max(0.02, nz.w), shape) * smoothstep(0.0, 0.35, base);
    d *= 0.35 + 1.3 * n * n;
    return d * den.x;
}

float puffSoft(int i, vec3 q, vec3 warp) {
    float r2 = dot(q, q);
    if (r2 >= 1.0) return 0.0;
    float base = 1.0 - sqrt(r2);
    float n = sm_noise((puffNoisePos(i, q) + warp) * 0.5);
    return smoothstep(0.0, 0.5, base + (n - 0.55) * P(i, 2).z * 0.8) * 0.6 * P(i, 11).x;
}

// distance (in sphere radii) from q to the unit sphere's surface along unit direction l
float exitDist(vec3 q, vec3 l) {
    float b = dot(q, l);
    return -b + sqrt(max(b * b - dot(q, q) + 1.0, 0.0));
}

vec4 marchPuff(int i, vec3 rd, float sceneT, float jitter) {
    vec4 cr = P(i, 0);
    vec3 C = cr.xyz;
    float R = cr.w;
    float b = dot(C, rd);
    vec3 off = C - rd * b;
    float h2 = R * R - dot(off, off);
    if (h2 <= 0.0) return vec4(0.0);
    float h = sqrt(h2);
    float t0 = max(b - h, 0.05), t1 = min(b + h, sceneT);
    if (t1 <= t0) return vec4(0.0);

    vec4 col = P(i, 1), amb = P(i, 4), emi = P(i, 5), lpos = P(i, 6), lcol = P(i, 7), opt = P(i, 8), fire = P(i, 10), den = P(i, 11), tint = P(i, 12);
    float steps = clamp(Params.y * fire.w * (t1 - t0) / (2.0 * R) + 1.0, 3.0, max(Params.y * fire.w, 3.0));
    int n = int(steps);
    float dt = (t1 - t0) / float(n);
    float sigmaK = EXT_PUFF * opt.x * col.a;
    vec3 L = normalize(SunDir.xyz);
    float cosSun = dot(rd, L);
    float phSun = mix(1.0, phase(cosSun, opt.y), 0.75);
    int shadowTaps = int(Params.w + 0.5);
    vec3 sun = SunColor.rgb * tint.rgb * amb.w;
    float ambK = den.w;
    vec3 acc = vec3(0.0);
    float T = 1.0;
    float t = t0 + dt * jitter;
    vec3 warp = warpFor(i, (rd * (0.5 * (t0 + t1)) - C) / R);
    for (int s = 0; s < 64; s++) {
        if (s >= n) break;
        vec3 p = rd * t;
        vec3 q = (p - C) / R;
        float d = puffDensity(i, q, warp);
        if (d > 0.002) {
            d *= maskAt(p);
            float sigma = d * sigmaK;
            // self-shadow towards the sun: analytic path length through the puff * sampled density
            float ex = exitDist(q, L);
            float avg = d * 0.45;
            if (shadowTaps > 0) {
                float sum = 0.0;
                for (int k = 1; k <= 3; k++) {
                    if (k > shadowTaps) break;
                    sum += puffSoft(i, q + L * ex * (float(k) / float(shadowTaps + 1)), warp);
                }
                avg = sum / float(shadowTaps);
            }
            float od = ex * R * avg * EXT_PUFF * opt.x * col.a * opt.z;
            float Ts = exp(-od);
            float powder = 1.0 - exp(-od * 2.0 - 0.15);
            vec3 light = sun * Ts * mix(1.0, powder * 1.6, 0.4) * phSun * lcol.w;
            // sky and block light, a little darker underneath
            light += amb.rgb * ambK * mix(0.55, 1.0, clamp(q.y * 0.5 + 0.5, 0.0, 1.0)) * (0.65 + 0.35 * Ts);
            if (lpos.w > 0.0) {
                vec3 Lp = lpos.xyz - p;
                float dist = length(Lp);
                vec3 Ld = Lp / max(dist, 1e-3);
                float att = clamp(1.0 - dist / lpos.w, 0.0, 1.0);
                att *= att;
                float exl = min(exitDist(q, Ld) * R, dist);
                float Tl = exp(-exl * d * 0.5 * EXT_PUFF * opt.x * opt.z);
                light += lcol.rgb * att * Tl * mix(1.0, phase(dot(rd, Ld), opt.y), 0.75) * lcol.w;
            }
            // a fire below lights the underside warm
            light += fire.rgb * emi.w * exp(-max(q.y + 1.0, 0.0) * 1.6) * 1.4;
            vec3 S = shoulder(col.rgb * mix(vec3(1.0), light, opt.w)) + emi.rgb;
            float Tstep = exp(-sigma * dt);
            acc += T * S * (1.0 - Tstep);
            T *= Tstep;
            if (T < 0.01) break;
        }
        t += dt;
    }
    return vec4(acc, 1.0 - T);
}

// ------------------------------------------------------------------------------------------------ fields

float fieldDensity(int i, vec3 lp, vec3 wp, vec3 E, vec3 warp, int oct) {
    vec4 nz = P(i, 2), misc = P(i, 3), ext = P(i, 9), den = P(i, 11);
    float hgt = lp.y + E.y;
    float hf = exp(-max(hgt, 0.0) * ext.w);
    float soft = max(nz.w, 0.02);
    vec3 e = (E - abs(lp)) / max(E * soft, vec3(0.05));
    float edge = clamp(min(min(e.x, e.z), e.y), 0.0, 1.0);
    edge = edge * edge * (3.0 - 2.0 * edge);
    if (edge * hf < 0.002) return 0.0;
    vec3 flow = vec3(P(i, 13).w, P(i, 14).w, P(i, 15).w);
    vec3 np = (wp - flow) * 0.09 * nz.x + warp;
    float n = oct > 0 ? sm_fbm(np + vec3(0.0, misc.y * 0.03, 0.0), oct) : sm_noise(np * 0.5);
    n = mix(n, 0.5, den.y * 0.6);
    float v = clamp(0.5 + (n - 0.5) * nz.z * 2.2, 0.0, 1.0);
    return den.x * hf * edge * v;
}

vec4 marchField(int i, vec3 rd, float sceneT, float jitter) {
    vec3 C = P(i, 0).xyz;
    mat3 Rt = mat3(P(i, 13).xyz, P(i, 14).xyz, P(i, 15).xyz); // columns = rows of R^T
    Rt = transpose(Rt);
    vec3 ro = Rt * (-C), rdl = Rt * rd;
    vec3 E = P(i, 9).xyz;
    vec3 safe = mix(rdl, vec3(1e-6), vec3(lessThan(abs(rdl), vec3(1e-6))));
    vec3 inv = 1.0 / safe;
    vec3 ta = (-E - ro) * inv, tb = (E - ro) * inv;
    vec3 tmin = min(ta, tb), tmax = max(ta, tb);
    float t0 = max(max(tmin.x, tmin.y), max(tmin.z, 0.05));
    float t1 = min(min(tmax.x, tmax.y), min(tmax.z, sceneT));
    if (t1 <= t0) return vec4(0.0);

    vec4 col = P(i, 1), amb = P(i, 4), emi = P(i, 5), lpos = P(i, 6), lcol = P(i, 7), opt = P(i, 8), fire = P(i, 10), den = P(i, 11), tint = P(i, 12);
    float maxSteps = max(Params.z * fire.w, 6.0);
    int n = int(clamp((t1 - t0) / 0.35, 4.0, maxSteps));
    float dt = (t1 - t0) / float(n);
    vec3 L = normalize(SunDir.xyz);
    vec3 Ll = Rt * L;
    float phSun = mix(1.0, phase(dot(rd, L), opt.y), 0.75);
    vec3 sun = SunColor.rgb * tint.rgb * amb.w;
    vec3 camW = vec3(Params2.y, Params2.z, Params2.w);
    vec3 acc = vec3(0.0);
    float T = 1.0;
    float t = t0 + dt * jitter;
    int oct = int(P(i, 2).y + 0.5);
    vec3 mid = rd * (0.5 * (t0 + t1)) + camW;
    vec3 warp = vec3(sm_noise(mid * 0.045 + vec3(0.0, P(i, 3).y * 0.05, 0.0)) - 0.5) * P(i, 3).x * 2.0;
    for (int s = 0; s < 128; s++) {
        if (s >= n) break;
        vec3 p = rd * t;
        vec3 lp = ro + rdl * t;
        float d = fieldDensity(i, lp, p + camW, E, warp, oct);
        if (d > 0.001) {
            d *= maskAt(p);
            float sigma = d * EXT_FIELD * opt.x * col.a;
            float Ts = 1.0;
            if (opt.z > 0.0) {
                float ds = fieldDensity(i, lp + Ll * 3.0, p + L * 3.0 + camW, E, warp, 0);
                Ts = exp(-(ds + d) * 1.5 * EXT_FIELD * 3.0 * opt.x * opt.z);
            }
            float hk = clamp((lp.y + E.y) / max(2.0 * E.y, 1e-3), 0.0, 1.0);
            vec3 light = sun * Ts * phSun * lcol.w + amb.rgb * den.w * (0.7 + 0.3 * hk);
            if (lpos.w > 0.0) {
                vec3 Lp = lpos.xyz - p;
                float dist = length(Lp);
                float att = clamp(1.0 - dist / lpos.w, 0.0, 1.0);
                light += lcol.rgb * att * att * mix(1.0, phase(dot(rd, Lp / max(dist, 1e-3)), opt.y), 0.75) * lcol.w;
            }
            vec3 S = shoulder(col.rgb * mix(vec3(1.0), light, opt.w)) + emi.rgb;
            float Tstep = exp(-sigma * dt);
            acc += T * S * (1.0 - Tstep);
            T *= Tstep;
            if (T < 0.01) break;
        }
        t += dt;
    }
    return vec4(acc, 1.0 - T);
}

void main() {
    vec2 uv = gl_FragCoord.xy * Screen.zw;
    vec3 rd = normalize(unproject(uv, 1.0));
    float depth = texture(DepthSampler, uv).r;
    float sceneT = depth >= 0.99999 ? 1e6 : length(unproject(uv, depth));
    float jitter = 0.5 + (ign(gl_FragCoord.xy) - 0.5) * 0.6;
    vec4 c = P(vIdx, 3).w > 0.5 ? marchField(vIdx, rd, sceneT, jitter) : marchPuff(vIdx, rd, sceneT, jitter);
    if (c.a < 0.001) discard;
    fragColor = c;
}
