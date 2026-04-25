#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <color_utils.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 miniScreenPos;
out vec2 miniCenter;
out float miniRadius;
flat out int miniType;

const bool DEBUG_SHADER = __DEBUG_SHADER__;

bool is_modern_signature(ivec4 c) {
    return (c.r & 0x80) == 0x80 && (c.g & 0x80) == 0x80;
}

int decode_modern_opcode(float positionY) {
    float screenScale = 1.0 / (ProjMat[1][1] / 2.0);
    // Mirrors the research shader: large negative buckets encode glyph opcodes.
    int raw = int(round(screenScale - positionY));
    if (raw > -100000) {
        return 0;
    }

    int opcode = (raw + 100000) / -10000;
    if (opcode >= 1 && opcode <= 6) {
        return opcode;
    }
    return 0;
}

vec4 debug_color(int opcode, int corner) {
    vec3 base = vec3(1.0, 1.0, 1.0);
    if (opcode == 1) base = vec3(1.0, 0.0, 0.0);
    if (opcode == 2) base = vec3(0.0, 1.0, 0.0);
    if (opcode == 3) base = vec3(0.0, 0.25, 1.0);
    if (opcode == 4) base = vec3(1.0, 0.5, 0.0);
    if (opcode == 5) base = vec3(0.0, 1.0, 1.0);
    if (opcode == 6) base = vec3(1.0, 0.0, 1.0);

    float shade = 1.0;
    if (corner == 1) shade = 0.75;
    if (corner == 2) shade = 0.50;
    if (corner == 3) shade = 0.30;
    return vec4(base * shade, 1.0);
}

vec2 corner_from_id(int id) {
    if (id == 0) return vec2(0.0, 0.0);
    if (id == 1) return vec2(0.0, 1.0);
    if (id == 2) return vec2(1.0, 1.0);
    return vec2(1.0, 0.0);
}

vec2 rotate2d(vec2 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return vec2(c * p.x + s * p.y, -s * p.x + c * p.y);
}

void main() {
    vec3 position = Position;
    ivec4 color8 = _7a81e42fddee2f93(Color);
    ivec4 colorTimes4 = min(color8 * 4, ivec4(255));

    int decodedOpcode = decode_modern_opcode(Position.y);
    bool opcodeMatch = decodedOpcode >= 1 && decodedOpcode <= 6;
    bool modernGlyph = opcodeMatch && is_modern_signature(color8);
    bool modernShadowGlyph = !modernGlyph && opcodeMatch && is_modern_signature(colorTimes4);

    ivec4 proto = modernGlyph ? color8 : colorTimes4;
    int typeId = decodedOpcode;

    int payloadPanX = proto.r & 0x7F;
    int payloadPanY = proto.g & 0x7F;
    float yawNorm = float(proto.b) / 255.0;

    miniType = 0;
    miniScreenPos = vec2(0.0);
    miniCenter = vec2(0.0);
    miniRadius = 0.0;

    if (modernGlyph || modernShadowGlyph) {
        bool sideRight = __SIDE_RIGHT__;
        vec2 center = vec2(sideRight ? (ScreenSize.x - 80.0) : 80.0, 80.0);

        float tileSize = 64.0;
        float borderSize = 128.0;
        float markerSize = 22.0;
        float clipRadius = 62.0;

        float yawAngle = yawNorm * 6.28318530718;
        vec2 panVec = vec2(
            float(payloadPanX) - 63.5,
            float(payloadPanY) - 63.5
        );

        vec2 corner = corner_from_id(gl_VertexID % 4);
        vec2 local = (corner - vec2(0.5)) * tileSize;
        vec2 centerOffset = vec2(0.0);
        vec2 finalPos;

        if (typeId >= 1 && typeId <= 4) {
            if (typeId == 1) centerOffset = vec2(-tileSize * 0.5, -tileSize * 0.5);
            if (typeId == 2) centerOffset = vec2( tileSize * 0.5, -tileSize * 0.5);
            if (typeId == 3) centerOffset = vec2(-tileSize * 0.5,  tileSize * 0.5);
            if (typeId == 4) centerOffset = vec2( tileSize * 0.5,  tileSize * 0.5);

            vec2 rotated = rotate2d(centerOffset + local + panVec, yawAngle);
            finalPos = center + rotated;
            miniType = typeId;
            miniCenter = center;
            miniRadius = clipRadius;
        } else if (typeId == 5) {
            finalPos = center + (corner - vec2(0.5)) * borderSize;
            miniType = typeId;
        } else {
            vec2 markerOffset = rotate2d(panVec, yawAngle);
            vec2 markerLocal = rotate2d((corner - vec2(0.5)) * markerSize, yawAngle);
            finalPos = center + markerOffset + markerLocal;
            miniType = typeId;
        }

        position.xy = finalPos;
        miniScreenPos = finalPos;
    }

    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);

    if (DEBUG_SHADER && (modernGlyph || modernShadowGlyph)) {
        vertexColor = debug_color(typeId, gl_VertexID % 4);
    } else if (modernShadowGlyph) {
        vertexColor = vec4(0.0);
    } else if (modernGlyph) {
        vertexColor = vec4(1.0) * texelFetch(Sampler2, UV2 / 16, 0);
    } else {
        vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    }

    texCoord0 = UV0;
}
