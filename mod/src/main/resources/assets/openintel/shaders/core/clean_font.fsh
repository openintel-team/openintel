#version 330
#extension GL_ARB_separate_shader_objects : require

// Member order must match 1.21.11 position_tex_color.vsh. A mismatched block
// fails to link during startup pipeline compile and leaves a black window.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

float coverageAt(vec2 uv, vec2 dx, vec2 dy) {
#ifdef FONT_INTENSITY
    return textureGrad(Sampler0, uv, dx, dy).r;
#else
    return textureGrad(Sampler0, uv, dx, dy).a;
#endif
}

void main() {
    vec2 dx = dFdx(texCoord0);
    vec2 dy = dFdy(texCoord0);
    float coverage = (
        coverageAt(texCoord0 + 0.125 * (-dx - dy), dx, dy) +
        coverageAt(texCoord0 + 0.125 * ( dx - dy), dx, dy) +
        coverageAt(texCoord0 + 0.125 * (-dx + dy), dx, dy) +
        coverageAt(texCoord0 + 0.125 * ( dx + dy), dx, dy)
    ) * 0.25;
    vec4 color = vertexColor * ColorModulator;
    color.a *= coverage;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color;
}
