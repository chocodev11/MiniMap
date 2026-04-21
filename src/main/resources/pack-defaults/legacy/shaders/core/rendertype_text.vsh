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

bool is_minimap_protocol(ivec4 c) {
    int t = c.r >> 5;
    return
        t >= 1 && t <= 6 &&
        (c.g & 0x80) == 0x80 &&
        (c.b & 0x40) == 0x40;
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

    bool minimapGlyph = is_minimap_protocol(color8);
    bool minimapShadowGlyph = !minimapGlyph && is_minimap_protocol(colorTimes4);

    ivec4 proto = minimapGlyph ? color8 : colorTimes4;
    int typeId = proto.r >> 5;
    int payloadYawLow5 = proto.r & 31;
    int payloadYawHigh = (proto.g >> 6) & 1;
    int payloadYaw = payloadYawLow5 | (payloadYawHigh << 5);
    int payloadPanX = proto.g & 63;
    int payloadPanY = proto.b & 63;
    bool sideRight = (proto.b & 0x80) == 0x80;

    miniType = 0;
    miniScreenPos = vec2(0.0);
    miniCenter = vec2(0.0);
    miniRadius = 0.0;

    if (minimapGlyph || minimapShadowGlyph) {
        vec2 center = vec2(
            sideRight ? (ScreenSize.x - 80.0) : 80.0,
            80.0
        );
        float tileSize = 64.0;
        float borderSize = 128.0;
        float markerSize = 22.0;
        float clipRadius = 62.0;
        float yawAngle = (float(payloadYaw) / 63.0) * 6.28318530718;
        vec2 panVec = vec2(
            (float(payloadPanX) - 31.5),
            (float(payloadPanY) - 31.5)
        );

        vec2 corner = corner_from_id(gl_VertexID % 4);
        vec2 local = (corner - vec2(0.5)) * tileSize;
        vec2 centerOffset = vec2(0.0);
        vec2 finalPos;

        if (typeId >= 1 && typeId <= 4) {
            // 4 tile quadrants.
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
            // Static circular border overlay.
            finalPos = center + (corner - vec2(0.5)) * borderSize;
            miniType = typeId;
        } else {
            // Marker pans and rotates with map transform.
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
    if (minimapShadowGlyph) {
        // Remove text-shadow copy of minimap glyphs.
        vertexColor = vec4(0.0);
    } else if (minimapGlyph) {
        // Minimap glyphs carry payload in color; keep visual color untinted.
        vertexColor = vec4(1.0) * texelFetch(Sampler2, UV2 / 16, 0);
    } else {
        vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    }
    texCoord0 = UV0;
}
