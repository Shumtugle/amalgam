package com.shumtugle.amalgam;

/** Column-major 4x4 matrices, laid out the way the shaders read them. */
final class Mat {
    private Mat() {}

    static void identity(float[] m) {
        for (int i = 0; i < 16; i++) m[i] = (i % 5 == 0) ? 1f : 0f;
    }

    static void perspective(float[] m, float fovyDeg, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovyDeg) * 0.5));
        for (int i = 0; i < 16; i++) m[i] = 0;
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = 2f * far * near / (near - far);
    }

    static void lookAt(float[] m, float ex, float ey, float ez, float tx, float ty, float tz) {
        float fx = tx - ex, fy = ty - ey, fz = tz - ez;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        // world up is +y; the camera never looks straight down
        float sx = fy * 0 - fz * 1, sy = fz * 0 - fx * 0, sz = fx * 1 - fy * 0;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sl; sy /= sl; sz /= sl;
        float ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;
        m[0] = sx; m[4] = sy; m[8] = sz;
        m[1] = ux; m[5] = uy; m[9] = uz;
        m[2] = -fx; m[6] = -fy; m[10] = -fz;
        m[3] = 0; m[7] = 0; m[11] = 0;
        m[12] = -(sx * ex + sy * ey + sz * ez);
        m[13] = -(ux * ex + uy * ey + uz * ez);
        m[14] = fx * ex + fy * ey + fz * ez;
        m[15] = 1;
    }

    /** Rotation by angle around the y axis passing through (cx, cy, cz). */
    static void spinAround(float[] m, float angle, float cx, float cy, float cz) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        identity(m);
        m[0] = c; m[8] = s;
        m[2] = -s; m[10] = c;
        m[12] = cx - (c * cx + s * cz);
        m[13] = 0;
        m[14] = cz - (-s * cx + c * cz);
    }

    /** Rotation around the y axis and a uniform scale, both about the point (cx, cy, cz). */
    static void spinScaleAround(float[] m, float angle, float scale, float cx, float cy, float cz) {
        float c = (float) Math.cos(angle) * scale, s = (float) Math.sin(angle) * scale;
        identity(m);
        m[0] = c; m[8] = s;
        m[2] = -s; m[10] = c;
        m[5] = scale;
        m[12] = cx - (c * cx + s * cz);
        m[13] = cy - scale * cy;
        m[14] = cz - (-s * cx + c * cz);
    }

    /** out = a * b; out must not alias either input. */
    static void mul(float[] out, float[] a, float[] b) {
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                out[c * 4 + r] = s;
            }
    }
}
