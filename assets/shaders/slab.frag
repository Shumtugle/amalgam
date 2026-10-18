#version 300 es
precision highp float;
// A face of the slab: the top shows the world as it was at the stroke; the cut face shows the
// strata, the surfaces that lie below in the order they will come up.
in vec2 vUv;
in float vFace;
in float vDepth;
in float vAlong;
in float vLight;
out vec4 o;
uniform sampler2D uSnap;
uniform vec3 uStrata[7];    // the surfaces' colours, starting with the next one down
uniform float uThick;       // depth of the slab, space units
uniform float uDim;         // 1 lit, 0 gone into the black
uniform float uAlpha;
uniform vec2 uPoint;        // a point on the cut, space units
uniform vec2 uNormal;       // across the cut
uniform float uAspect;      // screen height over width
uniform vec2 uRes;          // the screen in pixels
uniform float uReach;       // how far from the cut the new surface has spread; the slab is gone there

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 at = gl_FragCoord.xy / uRes * vec2(1.0, uAspect);
    if (abs(dot(at - uPoint, uNormal)) < uReach) discard;
    float light = 0.18 + 0.82 * clamp(vLight, 0.0, 1.0);
    vec3 col;
    if (vFace < 0.5) {
        col = texture(uSnap, vUv).rgb * (0.35 + 0.65 * light);
    } else {
        // seven bands, a little wavy, like rock laid down layer on layer
        float f = vDepth / uThick;
        f += 0.006 * sin(vAlong * 31.0 + f * 9.0) + 0.004 * sin(vAlong * 83.0);
        float k = clamp(floor(f * 7.0), 0.0, 6.0);
        float in_band = fract(f * 7.0);
        vec3 band = uStrata[int(k)];
        // grain along the bedding, finer the deeper
        float g = hash(vec2(floor(vAlong * (220.0 + k * 40.0)), k));
        float streak = 0.82 + 0.18 * g;
        // a dark seam between bands, and the top of each band catching the light
        float seam = smoothstep(0.0, 0.05, in_band) * (1.0 - smoothstep(0.94, 1.0, in_band));
        float lip = 1.0 + 0.35 * (1.0 - smoothstep(0.0, 0.12, in_band));
        col = band * 0.62 * streak * seam * lip * light;
        // the next surface, nearest the top, glows a little: it is the one coming up
        col += band * 0.25 * (1.0 - k / 6.0) * step(k, 0.5) * light;
    }
    o = vec4(col * uDim * uAlpha, uAlpha);
}
