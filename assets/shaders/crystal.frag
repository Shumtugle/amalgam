#version 300 es
precision highp float;
#include common
// Bismuth: a mirror under a film of oxide whose colour turns with the angle of view.
in vec3 vN;
in vec3 vW;
in vec3 vP;
in vec2 vTag;
out vec4 o;
uniform vec3 uEye;
uniform float uPulse;   // breath, peaks on the beat
uniform float uFlash;   // after a resonant touch
uniform float uFilm;
uniform float uEntropy; // disorder: the film shimmers and loses its colour
uniform float uTime;
uniform float uSink;    // the cluster under the surface after a cut, rising back: it darkens
uniform vec4 uCuts[8];  // planes the world was cut along, in cluster space; positive side is gone
uniform int uCutCount;
uniform int uCap;       // the cut whose face is being drawn, or -1

// A rainbow from red to violet; the spectrum is the clock of a cycle.
vec3 spectrum(float h) {
    float x = h * 0.8;
    vec3 c = clamp(abs(mod(x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return c * c * (3.0 - 2.0 * c);
}

void main() {
    for (int k = 0; k < 8; k++) {
        if (k >= uCutCount) break;
        if (k != uCap && dot(uCuts[k].xyz, vP) + uCuts[k].w > 0.0) discard;
    }
    float dim = (1.0 - uSink) * (1.0 - uSink);
    vec3 n = normalize(vN);
    if (vTag.y > 4.5) {
        // the face a cut leaves: freshly broken bismuth, a pale pink mirror with no film yet
        vec3 v = normalize(uEye - vW);
        vec3 rc = reflect(-v, n);
        float cc = clamp(dot(n, v), 0.0, 1.0);
        vec3 metal = vec3(0.95, 0.84, 0.88);
        vec3 room = vec3(0.16, 0.15, 0.18) * smoothstep(-0.3, 1.0, rc.y);
        vec3 cap = metal * (0.3 + 0.6 * (env(rc) + room)) * (0.75 + 0.25 * pow(1.0 - cc, 3.0));
        cap += metal * (0.06 * uPulse + 0.3 * uFlash);
        o = vec4(tonemap(cap) * dim, 1.0);
        return;
    }
    if (vTag.y > 3.5) {
        // entanglement: a faint cold filament that breathes with the crystal
        o = vec4(vec3(0.32, 0.5, 0.85) * (0.45 + 0.4 * uPulse) * dim, 1.0);
        return;
    }
    if (vTag.y > 2.5) {
        // a qubit: light of its own, brighter towards the edges of each face
        vec3 v = normalize(uEye - vW);
        float c = clamp(dot(n, v), 0.0, 1.0);
        vec3 hue = vTag.x > 1.5 ? vec3(1.0, 0.97, 0.92) : spectrum(vTag.x);
        vec3 q = hue * (1.1 + 0.7 * uPulse + 0.6 * pow(1.0 - c, 2.0));
        o = vec4(tonemap(q * 1.6) * dim, 1.0);
        return;
    }
    vec3 v = normalize(uEye - vW);
    if (dot(n, v) < -0.02) {
        // a face seen from inside: only a cut opens the metal like this. Shaded flat, the
        // inside reads as the cut face itself: bare bismuth, pale and pinkish, no film on it.
        vec3 bare = vec3(0.9, 0.8, 0.84) * (0.62 + 0.2 * uPulse + 0.4 * uFlash);
        o = vec4(tonemap(bare) * dim, 1.0);
        return;
    }
    float c = clamp(dot(n, v), 0.0, 1.0);
    vec3 r = reflect(-v, n);
    float inside = sqrt(1.0 - (1.0 - c * c) / 4.0);
    float bands = 40.0 * sin(dot(vW, vec3(1.3, 0.9, 1.1)));
    bands += uEntropy * 30.0 * sin(dot(vW, vec3(2.6, 2.1, 2.4)) + uTime * 2.5);
    vec3 tint = film(vTag.x + bands + uFilm, inside);
    // a dim room around the lights, so faces turned away still read as metal, not holes
    vec3 room = vec3(0.15, 0.16, 0.20) * smoothstep(-0.3, 1.0, r.y);
    vec3 e = env(r) + room;
    float fres = 0.62 + 0.38 * pow(1.0 - c, 5.0);
    tint = mix(tint, vec3(dot(tint, vec3(0.33))), uEntropy * 0.5);
    vec3 col = tint * (0.012 + e) * fres * vTag.y;
    col += tint * (0.07 * uPulse + 0.30 * uFlash);
    o = vec4(tonemap(col) * dim, 1.0);
}
