#version 300 es
precision highp float;
// The cut. The piece on the far side of the line darkens and falls away; along the edge that
// stays, the strata of the surfaces below show for a moment; then the next colour spreads.
in vec2 vUv;
out vec4 o;
uniform vec2 uPoint;        // a point on the cut, screen units
uniform vec2 uNormal;       // towards the piece that falls away, space units
uniform float uAspect;      // screen height over width
uniform float uAge;         // seconds into the cut
uniform vec3 uStrata[7];    // the surfaces' colours, starting with the next one down
uniform vec3 uNext;

void main() {
    vec2 q = (vUv - uPoint) * vec2(1.0, uAspect);
    float d = dot(q, uNormal);
    float t = uAge;
    vec3 col = vec3(0.0);
    float a = 0.0;
    // the falling piece: it darkens and its edge slides away
    float fall = smoothstep(0.0, 1.0, t);
    float edge = fall * 0.25;
    if (d > edge) a = fall;
    else if (d > 0.0) a = fall * 0.6;
    // and once it is gone, the new surface shows through where it was
    a *= 1.0 - smoothstep(2.1, 2.6, t);
    // the strata: thin bands in the colours of the surfaces below, on the side that stays
    float into = -d;
    float show = smoothstep(0.35, 0.8, t) * (1.0 - smoothstep(1.6, 2.3, t));
    if (into > 0.0 && into < 0.11) {
        int k = min(int(into / 0.0157), 6);
        vec3 band = uStrata[k];
        float rim = 1.0 - smoothstep(0.75, 1.0, fract(into / 0.0157));
        col += band * 0.8 * rim * show;
        a = max(a, 0.85 * show * rim);
    }
    // the bright line of the cut itself, fading as the piece goes
    col += vec3(1.0, 0.97, 0.9) * exp(-abs(d) * 260.0) * (1.0 - smoothstep(0.5, 1.4, t));
    // the next surface's colour washes across from the cut and settles
    float wash = smoothstep(1.3, 2.0, t) * (1.0 - smoothstep(2.0, 2.6, t));
    float reach = (t - 1.3) * 2.5;
    float front = 1.0 - smoothstep(reach - 0.4, reach, into);
    float kept = 1.0 - smoothstep(-0.02, 0.02, d);
    col += uNext * 0.22 * wash * front * kept;
    o = vec4(col, a);
}
