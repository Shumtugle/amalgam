#version 300 es
precision highp float;
#include common
// The eye in the corner: an iris of oxide colours round a black pupil that widens when it watches.
in vec2 vLocal;
out vec4 o;
uniform float uAlpha;
uniform float uTime;
uniform float uOpen;   // 0 resting, 1 watching: the pupil widens

void main() {
    float r = length(vLocal);
    if (r > 1.0) discard;
    float a = atan(vLocal.y, vLocal.x);
    float pupil = 0.32 + 0.22 * uOpen + 0.03 * sin(uTime * 1.7);
    // fibres of the iris, and a darker rim round it
    float fibres = 0.55 + 0.45 * sin(a * 47.0 + sin(a * 9.0) * 2.0) * sin(a * 23.0 + r * 9.0);
    vec3 iris = film(40.0 + r * 150.0 + fibres * 25.0, 1.0) * (0.35 + 0.45 * fibres);
    iris *= 1.0 - smoothstep(0.78, 1.0, r) * 0.85;
    vec3 col = mix(vec3(0.0), iris, smoothstep(pupil, pupil + 0.04, r));
    // a highlight, as on a wet eye
    col += vec3(1.0) * 0.8 * (1.0 - smoothstep(0.0, 0.09, length(vLocal - vec2(-0.3, 0.32))));
    float edge = 1.0 - smoothstep(0.93, 1.0, r);
    o = vec4(tonemap(col * 0.9), edge * uAlpha);
}
