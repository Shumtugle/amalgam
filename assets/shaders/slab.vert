#version 300 es
// The world as a slab during a cut. Its top is the picture of the screen taken at the stroke;
// below the top lie the strata. A piece turns on the cut as on a hinge, seen in perspective
// from a point straight above the middle of the screen.
layout(location = 0) in vec4 aPos;  // x, y in space units (width 1), z below the top (negative), face: 0 top, 1 cut
uniform vec2 uPoint;        // a point on the cut, space units
uniform vec2 uNormal;       // across the cut, towards the piece that falls away
uniform float uAngle;       // turn about the cut, radians; positive brings the cut face of the
                            // staying piece up to face the eye
uniform float uDrop;        // how far the piece has fallen, space units
uniform float uAspect;      // screen height over width
uniform float uEyeHeight;   // height of the eye over the top, space units
uniform float uSide;        // 1 for the piece that stays, -1 for the one that falls: its cut
                            // face looks the other way
out vec2 vUv;
out float vFace;
out float vDepth;           // below the top, space units
out float vAlong;           // along the cut, space units
out float vLight;
void main() {
    vec2 t = vec2(-uNormal.y, uNormal.x);
    vec2 r = aPos.xy - uPoint;
    float d = dot(r, uNormal), s = dot(r, t), z = aPos.z;
    float c = cos(uAngle), n = sin(uAngle);
    float d2 = d * c - z * n;
    float z2 = d * n + z * c - uDrop;
    vec2 p = uPoint + t * s + uNormal * d2;
    // how much the face looks up at the eye
    vLight = aPos.w < 0.5 ? c : uSide * n;
    vUv = vec2(aPos.x, aPos.y / uAspect);
    vFace = aPos.w;
    vDepth = -z;
    vAlong = s;
    float w = (uEyeHeight - z2) / uEyeHeight;
    float depth = clamp(-z2 / 6.0, 0.0, 1.0) * 2.0 - 1.0;
    gl_Position = vec4(2.0 * (p.x - 0.5), 2.0 * (p.y - 0.5 * uAspect) / uAspect, depth * w, w);
}
