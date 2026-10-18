#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
layout(location = 2) in vec2 aTag;   // x: film thickness, y: shade
uniform mat4 uMvp;
uniform mat4 uModel;
uniform vec2 uShift;                 // where the crystal sits on screen, in clip space
uniform float uSink;                 // 0 up, 1 under the surface: the whole cluster, after a cut
uniform float uSinkDepth;            // how far under it goes, cluster units
out vec3 vN;
out vec3 vW;
out vec3 vP;
out vec2 vTag;
void main() {
    vP = aPos;
    vec3 p = aPos - vec3(0.0, uSink * uSinkDepth, 0.0);
    vW = (uModel * vec4(p, 1.0)).xyz;
    vN = mat3(uModel) * aNor;
    vTag = aTag;
    vec4 q = uMvp * vec4(p, 1.0);
    q.xy += uShift * q.w;
    gl_Position = q;
}
