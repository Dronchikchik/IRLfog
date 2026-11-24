// Layout must match SmokeRenderer (frame UBO and the 17-vec4 per-volume records).
layout(std140) uniform SmokeFrame {
    mat4 InvViewProj;
    mat4 ViewProj;
    vec4 Screen;   // target w, h, 1/w, 1/h
    vec4 Params;   // time, max puff steps, max field steps, shadow taps
    vec4 Params2;  // octave cap, camera world pos (x,z wrapped to 1024)
    vec4 SunDir;
    vec4 SunColor; // rgb, frame index (jitter)
    vec4 MaskInfo; // count, has "only here" masks
    vec4 Masks[16];
};

layout(std140) uniform SmokePuffs {
    vec4 D[60 * 17];
};

#define P(i, s) D[(i) * 17 + (s)]

uniform sampler2D NoiseSampler;

// value-noise lattice: R = value, G = R of the next z slice (offset 37,17), so one bilinear fetch = smooth 3D noise
float sm_noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    vec2 uv = (i.xy + vec2(37.0, 17.0) * i.z) + f.xy;
    vec2 rg = textureLod(NoiseSampler, (uv + 0.5) / 256.0, 0.0).rg;
    return mix(rg.x, rg.y, f.z);
}

const mat3 SM_ROT = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

float sm_fbm(vec3 p, int octaves) {
    float v = 0.0, a = 0.5, norm = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i >= octaves) break;
        v += a * sm_noise(p);
        norm += a;
        p = SM_ROT * p * 2.03 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return v / max(norm, 0.001);
}
