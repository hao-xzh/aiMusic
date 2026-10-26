// Translated from Android AssistantWakeEffects.kt, baseline d1d8128.
export const assistantLightShader = `precision mediump float;

uniform vec2 resolution;
uniform float progress;
uniform float intensity;

void main() {
    vec2 position = vec2(gl_FragCoord.x, resolution.y - gl_FragCoord.y);
    vec2 uv = position / resolution;
    float height = 1.0 - uv.y;
    float drift = 0.045 * sin(uv.y * 7.0 - progress * 5.0)
                + 0.025 * sin(uv.y * 13.0 + progress * 3.0);
    float flow = 0.16 * sin(uv.x * 6.0 + uv.y * 3.0 - progress * 4.0)
               + 0.09 * sin(uv.x * 9.0 - uv.y * 5.0 + progress * 2.0);
    float hue = uv.x * 0.54 + height * 0.16 + flow + progress * 0.12;
    vec3 color = 0.52 + 0.40 * cos(6.2831853 * (hue + vec3(0.0, 0.3333, 0.6667)));
    float travel = height + 0.24 * abs(uv.x - 0.5) * 2.0;
    float front = progress * 1.85;
    float revealed = 1.0 - smoothstep(front - 0.12, front + 0.14, travel);
    float edge = min(uv.x, 1.0 - uv.x);
    float width = 0.10 + 0.04 * sin(height * 5.0 + progress * 3.0);
    float halo = exp(-pow(edge / width, 2.0));
    float rim = exp(-edge / 0.022);
    float packet = exp(-pow((travel - front + 0.12 + drift) / 0.30, 2.0));
    float swirl = 0.5 + 0.5 * sin(uv.x * 4.0 + uv.y * 2.0 - progress * 1.5 + flow);
    float bottom = exp(-height / 0.10);
    float alpha = intensity * revealed
                * (0.16 * halo + 0.16 * rim
                   + 0.14 * packet * (0.20 + 0.80 * halo) * (0.55 + 0.45 * swirl)
                   + 0.10 * bottom);
    alpha = clamp(alpha, 0.0, 0.44);
    // The page displacement alone disappears on smooth/blurred artwork.
    // Keep the Android water normal, highlight and shadow in the GPU overlay too.
    float a = uv.x * 8.0 + progress * 3.0;
    float b = uv.x * 15.0 - progress * 2.0;
    float bend = 0.045 * sin(a) + 0.022 * sin(b);
    float slopeX = 0.36 * cos(a) + 0.33 * cos(b);
    float u = height - progress * 1.45 + 0.10 + bend;
    float modulation = uv.x * 5.0 - progress * 4.0;
    float phase = u * 19.0 + 0.45 * sin(modulation);
    float envelope = exp(-u * u * 28.0);
    float wave = sin(phase), crest = cos(phase);
    float dx = envelope * (crest * (19.0 * slopeX + 2.25 * cos(modulation))
                          - 56.0 * u * slopeX * wave);
    float dy = envelope * (-19.0 * crest + 56.0 * u * wave);
    vec3 normal = normalize(vec3(-dx * 0.05, -dy * 0.045, 1.0));
    float fade = smoothstep(0.0, 0.08, progress) * intensity;
    float specular = pow(max(dot(normal, normalize(vec3(-0.35, -0.55, 1.0))), 0.0), 24.0)
                     * envelope * fade * 0.055;
    float shadow = (1.0 - normal.z) * 0.10 * envelope * fade;
    vec3 water = vec3(0.65, 0.86, 1.0) * specular;
    gl_FragColor = vec4(color * alpha + water * (1.0 - alpha),
                        alpha + (specular + shadow) * (1.0 - alpha));
}
`;
