#version 300 es
precision highp float;
// Over the slab during a cut: the bright line of the stroke, then the front of the next surface
// running out from the cut both ways. Behind the front the slab is gone and the new surface is
// there; the front itself is a seam of the new colour, and a sheen of it trails behind.
in vec2 vUv;
out vec4 o;
uniform vec2 uPoint;        // a point on the cut, screen units
uniform vec2 uNormal;       // across the cut, towards the piece that fell away
uniform float uAspect;      // screen height over width
uniform float uAge;         // seconds into the cut
uniform float uReach;       // how far the new surface has spread from the cut, space units; 0 not yet
uniform float uFade;        // 1 while the front runs, falling to 0 once it has passed
uniform vec3 uNext;

void main() {
    vec2 q = (vUv - uPoint) * vec2(1.0, uAspect);
    float d = abs(dot(q, uNormal));
    vec3 col = vec3(0.0);
    float a = 0.0;
    // the bright line of the stroke, fading as the piece goes
    float line = exp(-d * 260.0) * (1.0 - smoothstep(0.2, 1.0, uAge));
    col += vec3(1.0, 0.97, 0.9) * line;
    a = max(a, line);
    if (uReach > 0.0) {
        float behind = uReach - d;
        // the seam at the front, and the sheen it leaves on the new surface
        float seam = exp(-abs(behind) * 70.0);
        float sheen = behind > 0.0 ? 0.3 * exp(-behind * 2.2) : 0.0;
        vec3 c = uNext * (1.3 * seam + sheen) * uFade;
        col += c;
        a = max(a, clamp(seam * uFade, 0.0, 1.0));
    }
    o = vec4(col, a);
}
