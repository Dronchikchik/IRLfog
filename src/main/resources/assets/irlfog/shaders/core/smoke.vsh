#version 330

#moj_import <irlfog:smoke_common.glsl>

// No vertex buffer: 6 vertices per volume, the screen rectangle (NDC) comes from the volume record.
flat out int vIdx;

void main() {
    int i = gl_VertexID / 6;
    int c = gl_VertexID - i * 6;
    vec2 k = c == 0 ? vec2(0, 0) : c == 1 || c == 4 ? vec2(1, 0) : c == 2 || c == 3 ? vec2(0, 1) : vec2(1, 1);
    vec4 rect = P(i, 16);
    vIdx = i;
    gl_Position = vec4(mix(rect.xy, rect.zw, k), 0.0, 1.0);
}
