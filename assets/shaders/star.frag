#version 300 es
precision highp float;
// A star in the deep left by a finished cycle. It shows only where the water is still:
// a ripple over it puts it out.
in vec2 vLocal;
out vec4 o;
uniform sampler2D uField;   // the liquid
uniform vec2 uCenter;       // where the star is, screen units
uniform vec3 uTint;
uniform float uTime;
uniform float uSeed;

void main() {
    vec2 s = (texture(uField, uCenter).xy - 0.50196) * (255.0 / 300.0);
    float still = 1.0 - smoothstep(0.015, 0.08, length(s));
    float r2 = dot(vLocal, vLocal);
    float core = exp(-r2 * 26.0);
    float rays = exp(-abs(vLocal.x) * 40.0) * exp(-abs(vLocal.y) * 3.0)
               + exp(-abs(vLocal.y) * 40.0) * exp(-abs(vLocal.x) * 3.0);
    float twinkle = 0.75 + 0.25 * sin(uTime * (0.7 + uSeed) + uSeed * 20.0);
    float b = (core * 1.8 + rays * 0.4) * twinkle * still;
    o = vec4(uTint * b, 1.0);
}
