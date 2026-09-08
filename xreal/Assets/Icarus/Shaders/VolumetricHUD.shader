Shader "ICARUS/VolumetricHUD"
{
    Properties
    {
        _Heat ("Live Coolant Heat", Range(0,1)) = 0
        _Pulse ("Live RPM Pulse", Range(0,1)) = 0
    }
    SubShader
    {
        Tags { "Queue"="Transparent" "RenderType"="Transparent" }
        Cull Off ZWrite Off ZTest LEqual
        Blend SrcAlpha One
        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #pragma multi_compile_instancing
            #include "UnityCG.cginc"

            struct appdata
            {
                float4 vertex : POSITION;
                float2 uv : TEXCOORD0;
                UNITY_VERTEX_INPUT_INSTANCE_ID
            };
            struct v2f
            {
                float4 pos : SV_POSITION;
                float2 uv : TEXCOORD0;
                UNITY_VERTEX_OUTPUT_STEREO
            };

            float _Heat;
            float _Pulse;

            v2f vert(appdata v)
            {
                v2f o;
                UNITY_SETUP_INSTANCE_ID(v);
                UNITY_INITIALIZE_VERTEX_OUTPUT_STEREO(o);
                o.pos = UnityObjectToClipPos(v.vertex);
                o.uv = v.uv;
                return o;
            }

            float hash31(float3 p)
            {
                p = frac(p * 0.1031);
                p += dot(p, p.yzx + 33.33);
                return frac((p.x + p.y) * p.z);
            }

            float density(float3 p, float time)
            {
                float sphere = saturate(1.0 - length(p * float3(1.0, 1.25, 0.8)));
                float bands = 0.5 + 0.5 * sin(p.y * 18.0 - time * 1.7 + sin(p.x * 9.0));
                float noise = hash31(floor((p + time * 0.035) * 13.0));
                float ring = exp(-18.0 * abs(length(p.xz) - (0.42 + 0.035 * sin(time))));
                return sphere * (0.16 + 0.28 * bands + 0.12 * noise) + ring * 0.20;
            }

            fixed4 frag(v2f i) : SV_Target
            {
                UNITY_SETUP_STEREO_EYE_INDEX_POST_VERTEX(i);
                float2 p = (i.uv - 0.5) * float2(2.0, 1.25);
                float time = _Time.y * (0.55 + _Pulse * 0.5);
                float3 accum = 0;
                float alpha = 0;
                const int STEPS = 28;
                [unroll]
                for (int s = 0; s < STEPS; s++)
                {
                    float z = -0.9 + 1.8 * (s / (float)(STEPS - 1));
                    float3 q = float3(p.x, p.y, z);
                    float d = density(q, time);
                    float3 cool = float3(0.08, 0.70, 1.0);
                    float3 warm = float3(1.0, 0.35, 0.06);
                    float3 c = lerp(cool, warm, _Heat) * d;
                    float a = d * 0.08;
                    accum += c * (1.0 - alpha);
                    alpha += a * (1.0 - alpha);
                }
                float vignette = saturate(1.0 - dot(p, p) * 0.35);
                return fixed4(accum * vignette, alpha * 0.55 * vignette);
            }
            ENDCG
        }
    }
}
