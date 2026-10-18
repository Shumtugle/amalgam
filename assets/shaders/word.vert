#version 300 es
// A rectangle for the word, twice as wide as it is tall, centred on a point of the screen.
layout(location = 0) in vec2 aCorner;   // -1..1
uniform vec2 uCenter;                   // screen units
uniform float uSize;                    // half width, as a share of screen width
uniform float uAspect;                  // screen height over width
out vec2 vUv;
void main() {
    vUv = vec2(aCorner.x * 0.5 + 0.5, 0.5 - aCorner.y * 0.5);
    vec2 s = vec2(aCorner.x, aCorner.y * 0.5) * uSize;
    gl_Position = vec4(uCenter * 2.0 - 1.0 + vec2(s.x, s.y / uAspect) * 2.0, 0.0, 1.0);
}
