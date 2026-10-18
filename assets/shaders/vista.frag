#version 300 es
precision highp float;
// Visions seen through the black liquid: deep space, drawn entirely from noise and geometry.
// Added on top of the glass pass; the ripples of the liquid bend what shows through.
in vec2 vUv;
out vec4 o;
uniform sampler2D uField;  // the liquid: slopes bend the view
uniform int uScene;        // which vision
uniform float uAlpha;      // fade in and out
uniform float uTime;       // seconds since the vision opened
uniform float uAspect;     // screen height over width

const float TAU = 6.2831853;

float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

vec2 hash2(vec2 p) {
    return vec2(hash(p), hash(p + 17.31));
}

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) {
        s += a * noise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return s;
}

// A field of small stars that twinkle slowly.
vec3 stars(vec2 p, float density) {
    vec3 c = vec3(0.0);
    for (int layer = 0; layer < 2; layer++) {
        float scale = layer == 0 ? 60.0 : 140.0;
        vec2 g = p * scale;
        vec2 id = floor(g), f = fract(g) - 0.5;
        vec2 h = hash2(id + float(layer) * 31.0);
        if (h.x > density) continue;
        vec2 off = (h - 0.5) * 0.7;
        float d = length(f - off);
        float tw = 0.6 + 0.4 * sin(uTime * (1.0 + h.y * 3.0) + h.x * TAU);
        float b = smoothstep(0.08, 0.0, d) * tw * (layer == 0 ? 1.0 : 0.55);
        c += mix(vec3(0.7, 0.8, 1.0), vec3(1.0, 0.9, 0.75), h.y) * b;
    }
    return c;
}

// A lit sphere: returns shade in x (0 dark side, 1 lit), coverage in y, rim in z.
vec3 sphere(vec2 p, vec2 centre, float r, vec3 light, out vec3 normal) {
    vec2 d = (p - centre) / r;
    float q = dot(d, d);
    normal = vec3(d, sqrt(max(0.0, 1.0 - q)));
    float cover = smoothstep(1.0, 0.985, q);
    float lit = max(0.0, dot(normal, normalize(light)));
    float rim = smoothstep(0.75, 1.0, q) * cover;
    return vec3(lit, cover, rim);
}

vec3 warp(vec2 p) {
    float r = length(p), a = atan(p.y, p.x) / TAU + 0.5;
    vec3 c = vec3(0.0);
    for (int layer = 0; layer < 3; layer++) {
        float n = 90.0 + float(layer) * 70.0;
        float cell = floor(a * n);
        float h = hash(vec2(cell, float(layer) * 7.0));
        float across = abs(fract(a * n) - 0.5) * 2.0;
        // streaks rush inwards towards the black point, stretching as they near it
        float z = fract(h * 5.0 + uTime * (0.45 + h * 0.6));
        float head = (1.0 - z) * 1.5;
        float len = 0.06 + 0.5 * (1.0 - z) * (1.0 - z);
        float along = smoothstep(head + len, head, r) * smoothstep(head - 0.02, head, r);
        float width = smoothstep(0.9, 0.0, across) * step(0.35, h);
        c += vec3(0.75, 0.85, 1.0) * along * width * (0.6 + 0.8 * z);
    }
    c *= smoothstep(0.03, 0.12, r);
    c += vec3(0.3, 0.45, 1.0) * 0.18 * exp(-r * 3.0) * smoothstep(0.03, 0.08, r);
    return c * 1.4;
}

vec3 blueGalaxy(vec2 p) {
    vec3 c = stars(p, 0.45) * 1.1;
    // a blazing band behind the planet
    vec2 n = normalize(vec2(0.45, 1.0));
    float d = dot(p, n) - 0.35 - 0.06 * sin(p.x * 3.0);
    float w = fbm(p * 3.0 + vec2(uTime * 0.03, 0.0));
    float band = exp(-d * d / (0.012 + 0.02 * w)) * (0.5 + w);
    c += vec3(0.1, 0.35, 1.6) * band * 1.6 + vec3(0.6, 0.85, 1.4) * pow(band, 3.0) * 1.3;
    // asteroids drifting across
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        vec2 h = hash2(vec2(fi, 3.0));
        vec2 pos = vec2(fract(h.x + uTime * (0.02 + 0.03 * h.y)) * 1.4 - 0.7, (h.y - 0.3) * uAspect);
        float rr = 0.006 + 0.012 * hash(vec2(fi, 9.0));
        float a = smoothstep(rr, rr * 0.6, length(p - pos));
        c = mix(c, vec3(0.05, 0.05, 0.06) + vec3(0.25, 0.3, 0.4) * max(0.0, (p.y - pos.y) / rr) * 0.3, a);
    }
    // the half-lit planet in front
    vec3 nrm;
    vec3 s = sphere(p, vec2(-0.08, -0.42), 0.36, vec3(0.95, 0.55, -0.05), nrm);
    float bands = fbm(vec2(nrm.x * 2.0, nrm.y * 9.0) + uTime * 0.02);
    vec3 surf = mix(vec3(0.35, 0.5, 0.08), vec3(0.85, 0.9, 0.3), bands);
    // lit half, a faint night side, and a thin blue edge of atmosphere
    float day = smoothstep(-0.12, 0.55, dot(nrm, normalize(vec3(0.95, 0.55, -0.05))));
    vec3 pl = surf * (day * 1.3 + 0.004) + vec3(0.2, 0.45, 1.0) * s.z * (0.08 + day);
    c = mix(c, pl, s.y);
    return c;
}

vec3 supernova(vec2 p) {
    vec2 centre = vec2(0.12, 0.25);
    vec2 q = p - centre;
    float r = length(q), a = atan(q.y, q.x);
    float grow = 0.18 + 0.05 * uTime;
    float wobble = fbm(vec2(a * 2.5, r * 4.0 - uTime * 0.25)) ;
    float shell = exp(-pow((r - grow * (0.7 + 0.6 * wobble)) / (0.1 + 0.05 * wobble), 2.0));
    float core = exp(-r * r / 0.004);
    float cloud = fbm(q * 5.0 + vec2(uTime * 0.1)) * smoothstep(grow * 1.6, 0.0, r);
    vec3 c = stars(p, 0.15) * 0.5;
    c += vec3(1.0, 0.3, 0.03) * (shell * 1.5 + cloud * 1.1);
    c += vec3(1.0, 0.7, 0.2) * pow(shell, 3.0) * 1.5 + vec3(1.0, 0.95, 0.8) * core * 3.0;
    // rocks flung outwards
    for (int i = 0; i < 18; i++) {
        float fi = float(i);
        vec2 h = hash2(vec2(fi, 21.0));
        float ang = h.x * TAU, dist = (0.12 + 0.6 * h.y) * (0.5 + 0.12 * uTime);
        vec2 pos = centre + vec2(cos(ang), sin(ang)) * dist;
        float rr = 0.012 + 0.026 * hash(vec2(fi, 5.0));
        vec2 dv = (p - pos) / rr;
        float rock = smoothstep(1.0, 0.7, length(dv) + 0.25 * noise(dv * 2.0 + fi));
        float edge = max(0.0, dot(normalize(dv + 1e-4), -normalize(pos - centre)));
        c = mix(c, vec3(0.04, 0.03, 0.02) + vec3(1.0, 0.5, 0.1) * edge * 0.6, rock);
    }
    // a dark world in the lower left, rimmed by the blast
    vec3 nrm;
    vec3 s = sphere(p, vec2(-0.5, -0.78), 0.42, vec3(0.6, 1.0, 0.3), nrm);
    c = mix(c, vec3(0.01) + vec3(1.0, 0.5, 0.15) * s.z * s.x * 1.5, s.y);
    return c;
}

vec3 lava(vec2 p) {
    vec3 c = stars(p, 0.12) * 0.5;
    vec3 nrm;
    vec3 s = sphere(p, vec2(0.0, 0.0), 0.44, vec3(-0.5, 0.6, 0.6), nrm);
    // map the sphere to a slowly turning surface and crack it into cells
    vec2 uv = vec2(atan(nrm.x, nrm.z) + uTime * 0.05, nrm.y) * 3.0;
    vec2 i = floor(uv), f = fract(uv);
    float d1 = 9.0, d2 = 9.0;
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 pt = g + hash2(i + g) * 0.8 + 0.1;
            float d = length(f - pt);
            if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
        }
    float crack = smoothstep(0.09, 0.0, d2 - d1);
    float fine = smoothstep(0.62, 0.66, fbm(uv * 2.5));
    crack *= 0.55 + 0.9 * fbm(uv * 1.7 + 4.0);
    float heat = crack + fine * 0.25;
    float throb = 0.85 + 0.15 * sin(uTime * 1.3 + fbm(uv) * 6.0);
    vec3 crust = vec3(0.07, 0.04, 0.03) * (0.4 + 0.6 * s.x) * (0.7 + 0.6 * fbm(uv * 4.0));
    vec3 magma = mix(vec3(1.0, 0.2, 0.02), vec3(1.0, 0.85, 0.3), crack) * 2.2 * throb;
    vec3 pl = crust + magma * heat;
    c = mix(c, pl, s.y);
    // a red glow round the limb
    float r = length(p) / 0.44;
    c += vec3(1.0, 0.25, 0.05) * exp(-max(0.0, r - 1.0) * 14.0) * smoothstep(0.95, 1.02, r) * 0.9;
    return c;
}

vec3 nebula(vec2 p) {
    vec2 q = p * 2.2;
    vec2 w = vec2(fbm(q + uTime * 0.02), fbm(q + vec2(5.2, 1.3) - uTime * 0.015));
    float f = fbm(q + 2.2 * w);
    float g = fbm(q * 1.6 + 3.0 * w + 7.0);
    vec3 c = stars(p, 0.3) * 0.6;
    vec3 green = vec3(0.1, 0.9, 0.45), red = vec3(1.0, 0.18, 0.12), pink = vec3(1.0, 0.4, 0.75);
    vec3 gas = mix(mix(green, red, smoothstep(0.35, 0.65, g)), pink, smoothstep(0.55, 0.85, w.x));
    // a cloud with a shape of its own, not a wallpaper: thick in the middle, torn at the edges
    float shape = smoothstep(0.7, 0.0, length(p * vec2(1.7, 0.8)) + 0.6 * (w.y - 0.5));
    float dens = smoothstep(0.3, 0.85, f) * shape;
    c = c * (1.0 - dens) + gas * dens * 1.3 + vec3(1.0, 0.9, 0.9) * pow(dens, 4.0) * 0.6;
    return c;
}

vec3 star(vec2 p) {
    vec2 centre = vec2(0.0, 0.15);
    vec2 q = p - centre;
    float r = length(q);
    vec3 c = stars(p, 0.25) * 0.7;
    float haze = fbm(q * 3.0 + uTime * 0.03);
    c += mix(vec3(0.05, 0.75, 0.45), vec3(1.0, 0.72, 0.2), smoothstep(0.35, 0.7, haze)) * exp(-r * 5.5) * smoothstep(0.3, 0.8, haze + 0.25 * exp(-r * 6.0)) * 1.3;
    float core = exp(-r * r / 0.0012) * 4.0 + exp(-r * 9.0) * 1.2;
    // diffraction spikes: a cross, and a fainter one turned by half a right angle
    float sp = exp(-abs(q.x) * 260.0) * exp(-abs(q.y) * 4.0) + exp(-abs(q.y) * 260.0) * exp(-abs(q.x) * 4.0);
    vec2 d = vec2(q.x + q.y, q.x - q.y) * 0.7071;
    sp += 0.35 * (exp(-abs(d.x) * 320.0) * exp(-abs(d.y) * 9.0) + exp(-abs(d.y) * 320.0) * exp(-abs(d.x) * 9.0));
    c += vec3(1.0, 0.97, 0.88) * (core + sp * (0.9 + 0.1 * sin(uTime * 2.0)));
    // dark worlds around it, each with a thin lit edge facing the star
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        vec2 pos = vec2(-0.32 + 0.38 * fi, -0.55 + 0.5 * hash(vec2(fi, 2.0)) * (fi == 1.0 ? -1.0 : 1.0));
        float rr = 0.05 + 0.06 * hash(vec2(fi, 8.0));
        vec3 nrm;
        vec3 s = sphere(p, pos, rr, vec3(centre - pos, 0.2), nrm);
        c = mix(c, vec3(0.005) + vec3(0.9, 0.8, 0.5) * pow(s.x, 6.0) * 0.9, s.y);
    }
    return c;
}

vec3 edgeGalaxy(vec2 p) {
    float t = -0.18;
    vec2 q = mat2(cos(t), -sin(t), sin(t), cos(t)) * p;
    vec3 c = stars(p, 0.3) * 0.7;
    float mist = fbm(p * 2.0 + uTime * 0.02);
    c += vec3(0.03, 0.4, 0.36) * smoothstep(0.5, 0.85, mist) * exp(-dot(q, q) * 5.0) * 0.9;
    float disc = exp(-length(q / vec2(0.5, 0.055)) * 2.0);
    float halo = exp(-length(q / vec2(0.24, 0.15)) * 2.0);
    float core = exp(-length(q / vec2(0.07, 0.035)) * 2.5);
    float knots = fbm(q * vec2(9.0, 30.0)) * disc;
    vec3 gal = vec3(0.45, 0.7, 1.0) * disc * 0.9 + vec3(0.8, 0.9, 1.0) * halo * 0.35 + vec3(1.0) * core * 2.8;
    gal += vec3(0.5, 0.75, 1.0) * knots * 0.5;
    // the dark lane of dust across the middle
    float lane = exp(-pow(q.y / (0.009 + 0.006 * fbm(q * 14.0)), 2.0)) * smoothstep(0.5, 0.1, abs(q.x));
    gal *= 1.0 - 0.9 * lane;
    c += gal;
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        vec2 pos = vec2(0.3 - 0.62 * fi, -0.4 + 0.85 * fi);
        vec3 nrm;
        vec3 s = sphere(p, pos, 0.035 + 0.02 * fi, vec3(-pos, 0.3), nrm);
        c = mix(c, vec3(0.01) + vec3(0.3, 0.8, 0.75) * pow(s.x, 2.0) * 0.8, s.y);
    }
    return c;
}

void main() {
    vec4 t = texture(uField, vUv);
    vec2 slope = (t.xy - 0.50196) * (255.0 / 300.0);
    vec2 p = (vUv - 0.5 + slope * 0.03) * vec2(1.0, uAspect);
    vec3 c;
    if (uScene == 0) c = warp(p);
    else if (uScene == 1) c = blueGalaxy(p);
    else if (uScene == 2) c = supernova(p);
    else if (uScene == 3) c = lava(p);
    else if (uScene == 4) c = nebula(p);
    else if (uScene == 5) c = star(p);
    else c = edgeGalaxy(p);
    c = max(c - 0.01, 0.0);   // keep empty space truly black after the gamma lift
    c = c / (1.0 + c);
    o = vec4(pow(c, vec3(1.0 / 2.2)) * uAlpha, 1.0);
}
