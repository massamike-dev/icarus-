package com.icarusalmighty.app.driving

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * Decorative GPU volume renderer for the Spatial HUD.
 *
 * This layer never synthesizes vehicle measurements. The only vehicle-derived
 * input is a normalized visual heat intensity when a real coolant PID exists.
 * All scan clouds, depth fields, and glow motion are presentation effects.
 */
class VolumetricHudSurface(context: Context) : GLSurfaceView(context) {
    private val volumeRenderer = VolumeRenderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(volumeRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun render(state: DrivingHudState) {
        val heat = state.engineTempF?.let { temperature ->
            ((temperature - 160f) / 100f).coerceIn(0f, 1f)
        } ?: 0f
        volumeRenderer.state.set(
            VisualState(
                live = state.obdConnected,
                heat = heat,
                rpmPulse = state.rpm?.let { (it.coerceIn(0, 6000) / 6000f) } ?: 0f
            )
        )
    }

    private data class VisualState(
        val live: Boolean = false,
        val heat: Float = 0f,
        val rpmPulse: Float = 0f
    )

    private class VolumeRenderer : Renderer {
        val state = AtomicReference(VisualState())
        private var program = 0
        private var width = 1
        private var height = 1
        private var startNanos = System.nanoTime()
        private lateinit var vertices: FloatBuffer

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glClearColor(0.003f, 0.012f, 0.03f, 1f)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            vertices = ByteBuffer
                .allocateDirect(QUAD.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(QUAD); position(0) }
            startNanos = System.nanoTime()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, this.width, this.height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (program == 0) return

            val visual = state.get()
            val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
            GLES30.glUseProgram(program)

            val position = GLES30.glGetAttribLocation(program, "a_position")
            vertices.position(0)
            GLES30.glEnableVertexAttribArray(position)
            GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, vertices)

            GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "u_resolution"), width.toFloat(), height.toFloat())
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_time"), elapsed)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_live"), if (visual.live) 1f else 0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_heat"), visual.heat)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_rpm"), visual.rpmPulse)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(position)
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val result = GLES30.glCreateProgram()
            GLES30.glAttachShader(result, vertex)
            GLES30.glAttachShader(result, fragment)
            GLES30.glLinkProgram(result)
            val linked = IntArray(1)
            GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, linked, 0)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            if (linked[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(result)
                GLES30.glDeleteProgram(result)
                throw IllegalStateException("Spatial HUD shader link failed: $log")
            }
            return result
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("Spatial HUD shader compile failed: $log")
            }
            return shader
        }

        companion object {
            private val QUAD = floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )

            private const val VERTEX_SHADER = """
                #version 300 es
                in vec2 a_position;
                out vec2 v_uv;
                void main() {
                    v_uv = a_position * 0.5 + 0.5;
                    gl_Position = vec4(a_position, 0.0, 1.0);
                }
            """

            private const val FRAGMENT_SHADER = """
                #version 300 es
                precision highp float;

                in vec2 v_uv;
                out vec4 fragColor;

                uniform vec2 u_resolution;
                uniform float u_time;
                uniform float u_live;
                uniform float u_heat;
                uniform float u_rpm;

                float hash31(vec3 p) {
                    p = fract(p * 0.1031);
                    p += dot(p, p.yzx + 33.33);
                    return fract((p.x + p.y) * p.z);
                }

                float noise3(vec3 p) {
                    vec3 i = floor(p);
                    vec3 f = fract(p);
                    f = f * f * (3.0 - 2.0 * f);
                    float n000 = hash31(i + vec3(0.0,0.0,0.0));
                    float n100 = hash31(i + vec3(1.0,0.0,0.0));
                    float n010 = hash31(i + vec3(0.0,1.0,0.0));
                    float n110 = hash31(i + vec3(1.0,1.0,0.0));
                    float n001 = hash31(i + vec3(0.0,0.0,1.0));
                    float n101 = hash31(i + vec3(1.0,0.0,1.0));
                    float n011 = hash31(i + vec3(0.0,1.0,1.0));
                    float n111 = hash31(i + vec3(1.0,1.0,1.0));
                    float nx00 = mix(n000, n100, f.x);
                    float nx10 = mix(n010, n110, f.x);
                    float nx01 = mix(n001, n101, f.x);
                    float nx11 = mix(n011, n111, f.x);
                    return mix(mix(nx00, nx10, f.y), mix(nx01, nx11, f.y), f.z);
                }

                float sdSphere(vec3 p, float r) {
                    return length(p) - r;
                }

                float volumeDensity(vec3 p) {
                    float t = u_time * 0.22;
                    vec3 q = p;
                    q.x += sin(p.z * 2.1 + t) * 0.08;
                    q.y += cos(p.z * 1.7 - t * 1.3) * 0.05;

                    float core = exp(-max(sdSphere(q - vec3(0.0, -0.08, 0.85), 0.42), 0.0) * 8.0);
                    float corridor = exp(-(abs(q.x) * 6.5 + abs(q.y + 0.32) * 3.2))
                        * smoothstep(-0.2, 1.6, q.z)
                        * (1.0 - smoothstep(1.6, 3.3, q.z));
                    float scanShell = exp(-abs(length(q - vec3(0.0, -0.08, 0.85)) - 0.62) * 15.0);
                    float wisps = noise3(q * 3.2 + vec3(0.0, t, -t * 0.35));
                    wisps = smoothstep(0.56, 0.90, wisps) * 0.42;

                    return clamp(core * 0.58 + corridor * 0.48 + scanShell * 0.24 + wisps, 0.0, 1.0);
                }

                void main() {
                    vec2 p = v_uv * 2.0 - 1.0;
                    p.x *= u_resolution.x / max(u_resolution.y, 1.0);

                    vec3 ro = vec3(0.0, 0.02, -2.25);
                    vec3 rd = normalize(vec3(p.x * 0.92, p.y * 0.68, 1.5));

                    vec3 cyan = vec3(0.07, 0.68, 1.0);
                    vec3 deepBlue = vec3(0.01, 0.16, 0.42);
                    vec3 gold = vec3(0.94, 0.60, 0.16);
                    vec3 hot = vec3(1.0, 0.20, 0.04);
                    vec3 heatColor = mix(gold, hot, u_heat);

                    vec3 accumulated = vec3(0.0);
                    float alpha = 0.0;
                    float distanceTravelled = 0.0;
                    float pulse = 0.82 + 0.18 * sin(u_time * (1.4 + u_rpm * 3.0));

                    for (int i = 0; i < 28; ++i) {
                        vec3 samplePos = ro + rd * distanceTravelled;
                        float density = volumeDensity(samplePos) * 0.075;
                        vec3 tint = mix(deepBlue, cyan, clamp(samplePos.z * 0.28 + 0.38, 0.0, 1.0));

                        float engineMask = exp(-length(samplePos - vec3(-0.22, -0.08, 0.85)) * 5.5);
                        if (u_live > 0.5) {
                            tint = mix(tint, heatColor, engineMask * (0.26 + u_heat * 0.62));
                        }

                        density *= pulse;
                        float contribution = density * (1.0 - alpha);
                        accumulated += tint * contribution;
                        alpha += contribution;
                        if (alpha > 0.80) break;
                        distanceTravelled += 0.155;
                    }

                    float horizon = exp(-abs(p.y + 0.31) * 48.0) * 0.16;
                    accumulated += cyan * horizon;
                    accumulated += gold * horizon * 0.22;

                    float vignette = smoothstep(1.55, 0.22, dot(p, p));
                    vec3 background = mix(vec3(0.002, 0.008, 0.025), vec3(0.004, 0.035, 0.09), max(p.y + 0.55, 0.0));
                    vec3 finalColor = background + accumulated * vignette;

                    fragColor = vec4(finalColor, 1.0);
                }
            """
        }
    }
}
