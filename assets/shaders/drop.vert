#version 300 es
// A square around one drop, stretched along its motion. vLocal stays a plain unit circle,
// so the fragment shader never sees the stretch.
layout(location = 0) in vec2 aCorner;   // -1..1
uniform vec2 uCenter;                   // screen units
uniform float uRadius;                  // share of screen width
uniform float uAspect;                  // screen height over width
uniform vec2 uDir;                      // direction of motion, unit, space units
uniform float uStretch;
out vec2 vLocal;
void main() {
    vLocal = aCorner * 1.15;
    vec2 side = vec2(-uDir.y, uDir.x);
    vec2 s = (uDir * vLocal.x * uStretch + side * vLocal.y) * uRadius;
    gl_Position = vec4(uCenter * 2.0 - 1.0 + vec2(s.x, s.y / uAspect) * 2.0, 0.0, 1.0);
}
