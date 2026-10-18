#version 300 es
precision highp float;
#include common
// The word, lit from within with the colours of the film.
in vec2 vUv;
out vec4 o;
uniform sampler2D uText;   // the word in white on nothing
uniform float uAlpha;
uniform float uTime;

void main() {
    float a = texture(uText, vUv).a;
    vec3 tint = mix(vec3(1.0), film(180.0 + vUv.x * 160.0 + uTime * 40.0, 1.0), 0.45);
    o = vec4(tonemap(tint * 1.6) * a * uAlpha, 1.0);
}
