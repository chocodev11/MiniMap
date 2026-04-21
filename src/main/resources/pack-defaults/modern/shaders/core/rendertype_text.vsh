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

const float MODERN_GLYPH_HEIGHT = 8.0;

bool decode_modern_opcode_from_anchor(float anchorY, out int opcode) {
    if (anchorY >= 18.0 && anchorY <= 22.0) {
        opcode = 1;
        return true;
    }
    if (anchorY >= 38.0 && anchorY <= 42.0) {
        opcode = 2;
        return true;
    }
    if (anchorY >= 58.0 && anchorY <= 62.0) {
        opcode = 3;
        return true;
    }
    if (anchorY >= 78.0 && anchorY <= 82.0) {
        opcode = 4;
        return true;
    }
    if (anchorY >= 98.0 && anchorY <= 102.0) {
        opcode = 5;
        return true;
    }
    if (anchorY >= 118.0 && anchorY <= 122.0) {
        opcode = 6;
        return true;
    }

    opcode = 0;
    return false;
}

bool decode_modern_opcode(float positionY, int vertexId, out int opcode) {
    if (positionY < 0.0) {
        opcode = 0;
        return false;
    }

    int quadVertex = vertexId % 4;

    // Text quads carry different Y per corner. Normalize back to a common
    // per-glyph anchor before bucketing, otherwise one glyph can split across
    // multiple opcodes and stretch into lines/triangles.
    float anchorTop03 = positionY + ((quadVertex == 0 || quadVertex == 3) ? MODERN_GLYPH_HEIGHT : 0.0);
    if (decode_modern_opcode_from_anchor(anchorTop03, opcode)) {
        return true;
    }

    float anchorTop12 = positionY + ((quadVertex == 1 || quadVertex == 2) ? MODERN_GLYPH_HEIGHT : 0.0);
    return decode_modern_opcode_from_anchor(anchorTop12, opcode);
}

bool is_modern_signature(ivec4 c) {
    return (c.r & 0x80) == 0x80 && (c.g & 0x80) == 0x80;
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

    int decodedOpcode = 0;
    bool opcodeMatch = decode_modern_opcode(Position.y, gl_VertexID, decodedOpcode);

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

        float yawAngle = yawNorm * -6.28318530718 + 3.14159265359;
        vec2 panVec = vec2(float(payloadPanX), float(payloadPanY));
        panVec = (panVec - vec2(63.5)) * 1.0;

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

    if (modernShadowGlyph) {
        vertexColor = vec4(0.0);
    } else if (modernGlyph) {
        vertexColor = vec4(1.0) * texelFetch(Sampler2, UV2 / 16, 0);
    } else {
        vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    }

    texCoord0 = UV0;
}
