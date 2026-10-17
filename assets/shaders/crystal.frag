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

// A rainbow from red to violet; the spectrum is the clock of a cycle.
vec3 spectrum(float h) {
    float x = h * 0.8;
    vec3 c = clamp(abs(mod(x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return c * c * (3.0 - 2.0 * c);
}

void main() {
    vec3 n = normalize(vN);
    if (vTag.y > 2.5) {
        // a qubit: light of its own, brighter towards the edges of each face
        vec3 v = normalize(uEye - vW);
        float c = clamp(dot(n, v), 0.0, 1.0);
        vec3 q = spectrum(vTag.x) * (1.1 + 0.7 * uPulse + 0.6 * pow(1.0 - c, 2.0));
        o = vec4(tonemap(q * 1.6), 1.0);
        return;
    }
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
