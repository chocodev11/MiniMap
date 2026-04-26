#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 miniScreenPos;
in vec2 miniCenter;
in vec2 miniTexelOffset;
in float miniRadius;
flat in int miniType;

out vec4 fragColor;

void main() {
    vec2 atlasSize = vec2(textureSize(Sampler0, 0));
    vec2 sampleCoord = texCoord0;
    if (miniType >= 1 && miniType <= 4) {
        sampleCoord += miniTexelOffset / atlasSize;
    }

    vec4 color = texture(Sampler0, sampleCoord) * vertexColor * ColorModulator;
    if (color.a < 0.001) {
        discard;
    }

    // Circular clipping for map tiles only.
    if (miniType >= 1 && miniType <= 4) {
        if (distance(miniScreenPos, miniCenter) > miniRadius) {
            discard;
        }
    }

    fragColor = apply_fog(
        color,
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}
