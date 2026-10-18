#version 300 es
precision highp float;
#include common
// The liquid seen straight down through the glass.
in vec2 vUv;
out vec4 o;
uniform sampler2D uField;   // r, g: slope; b: height; all offset by half
uniform vec4 uGlow;         // xy: crystal on screen, z: glow strength, w: screen height over width
uniform float uFilm;        // shifts every film thickness
uniform float uLens;        // how strongly the crystal's mass bends the view round it
uniform vec3 uLayer;        // colour of the current surface

void main() {
    // Lensing: a point seen at distance r from the mass shows what lies closer to it.
    vec2 d0 = (vUv - uGlow.xy) * vec2(1.0, uGlow.w);
    vec2 bend = -d0 * uLens / (dot(d0, d0) + 0.003);
    bend /= max(1.0, length(bend) / 0.06);
    vec4 t = texture(uField, vUv + bend * vec2(1.0, 1.0 / uGlow.w));
    vec2 s = (t.xy - 0.50196) * (255.0 / 300.0);
    float h = (t.z - 0.50196) * (255.0 / 60.0);
    vec3 n = normalize(vec3(-s * 2.4, 1.0));
    vec3 r = reflect(vec3(0.0, 0.0, -1.0), n);
    vec3 e = env(vec3(r.x, r.z, -r.y));
    float slope = length(s);

    float fres = 0.07 + 0.93 * pow(1.0 - n.z, 5.0);
    vec3 sheen = film(260.0 + h * 140.0 + uFilm, n.z);
    vec3 col = e * fres * mix(vec3(1.0), sheen, clamp(slope * 6.0, 0.0, 0.75));
    // each surface reflects in its own colour
    col *= mix(vec3(1.0), uLayer, 0.65);

    vec2 d = (vUv - uGlow.xy) * vec2(1.0, uGlow.w);
    float g = uGlow.z * exp(-dot(d, d) / 0.03);
    vec3 tint = film(150.0 + uFilm, 1.0);
    col += tint * g * (0.008 + slope * 2.0);

    o = vec4(tonemap(col), 1.0);
}
