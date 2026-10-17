#version 300 es
precision highp float;
#include common
// Bismuth: a mirror under a film of oxide whose colour turns with the angle of view.
in vec3 vN;
in vec3 vW;
in vec2 vTag;
out vec4 o;
uniform vec3 uEye;
uniform float uPulse;   // breath, peaks on the beat
uniform float uFlash;   // after a resonant touch
uniform float uFilm;

void main() {
    vec3 n = normalize(vN);
    vec3 v = normalize(uEye - vW);
    float c = clamp(dot(n, v), 0.0, 1.0);
    vec3 r = reflect(-v, n);
    float inside = sqrt(1.0 - (1.0 - c * c) / 4.0);
    float bands = 40.0 * sin(dot(vW, vec3(1.3, 0.9, 1.1)));
    vec3 tint = film(vTag.x + bands + uFilm, inside);
    // a dim room around the lights, so faces turned away still read as metal, not holes
    vec3 room = vec3(0.15, 0.16, 0.20) * smoothstep(-0.3, 1.0, r.y);
    vec3 e = env(r) + room;
    float fres = 0.62 + 0.38 * pow(1.0 - c, 5.0);
    vec3 col = tint * (0.012 + e) * fres * vTag.y;
    col += tint * (0.07 * uPulse + 0.30 * uFlash);
    o = vec4(tonemap(col), 1.0);
}
