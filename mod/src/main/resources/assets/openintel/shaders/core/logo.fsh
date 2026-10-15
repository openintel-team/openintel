#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

float cover(float distance) {
    float aa = max(length(vec2(dFdx(distance), dFdy(distance))) * 0.7, 0.0001);
    return 1.0 - smoothstep(-aa, aa, distance);
}

void main() {
    vec2 p = texCoord0 * 2.0 - 1.0;
    float ring = abs(length(vec2(p.x * 1.06, p.y)) - 0.78) - 0.085;
    float lens = max(length(p - vec2(0.0, 0.87)), length(p + vec2(0.0, 0.87))) - 1.14;
    float eye = min(abs(lens) - 0.036, length(p) - 0.115);
    float distance = min(ring, eye);
    float alpha = cover(distance) * vertexColor.a * ColorModulator.a * texture(Sampler0, texCoord0).a;
    if (alpha == 0.0) discard;
    fragColor = vec4(0.0, 0.0, 0.0, alpha);
}
