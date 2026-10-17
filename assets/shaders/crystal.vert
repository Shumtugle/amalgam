#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
layout(location = 2) in vec2 aTag;   // x: film thickness, y: shade
uniform mat4 uMvp;
uniform mat4 uModel;
uniform vec2 uShift;                 // where the crystal sits on screen, in clip space
out vec3 vN;
out vec3 vW;
out vec2 vTag;
void main() {
    vW = (uModel * vec4(aPos, 1.0)).xyz;
    vN = mat3(uModel) * aNor;
    vTag = aTag;
    vec4 p = uMvp * vec4(aPos, 1.0);
    p.xy += uShift * p.w;
    gl_Position = p;
}
