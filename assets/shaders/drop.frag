#version 300 es
precision highp float;
#include common
// A drop of quicksilver seen from above: a curved mirror under a faint film.
in vec2 vLocal;
out vec4 o;
uniform float uAlpha;
uniform float uFilm;
uniform vec2 uDir;
uniform vec3 uLayer;   // colour of the current surface: drops take it

void main() {
    float d2 = dot(vLocal, vLocal);
    if (d2 > 1.0) discard;
    // local frame back to screen frame
    vec2 side = vec2(-uDir.y, uDir.x);
    vec2 p = uDir * vLocal.x + side * vLocal.y;
    vec3 n = vec3(p, sqrt(1.0 - d2));
    vec3 r = reflect(vec3(0.0, 0.0, -1.0), n);
    vec3 e = env(vec3(r.x, r.z, -r.y));
    // quicksilver is a bright mirror; the film only tints it
    vec3 tint = mix(vec3(0.9), film(uFilm, n.z), 0.45) * mix(vec3(1.0), uLayer * 1.15, 0.8);
    vec3 room = vec3(0.16, 0.17, 0.21) * smoothstep(-0.3, 1.0, r.z);
    float fres = 0.7 + 0.3 * pow(1.0 - n.z, 4.0);
    vec3 col = tint * (0.03 + e + room) * fres;
    // the black liquid shows as a dark band round the edge
    col *= (1.0 - smoothstep(0.6, 1.0, d2)) * 0.6 + 0.4;
    float a = (1.0 - smoothstep(0.86, 1.0, d2)) * uAlpha;
    o = vec4(tonemap(col), a);
}
