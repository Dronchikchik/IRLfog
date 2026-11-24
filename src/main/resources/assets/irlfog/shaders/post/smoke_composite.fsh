#version 330

// Premultiplied smoke target over the frame (blend ONE, ONE_MINUS_SRC_ALPHA); a tiny dither hides 8-bit banding.
uniform sampler2D SmokeSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 s = texture(SmokeSampler, texCoord);
    if (s.a < 0.002) discard;
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5;
    fragColor = vec4(max(s.rgb + n / 255.0, 0.0), s.a);
}
