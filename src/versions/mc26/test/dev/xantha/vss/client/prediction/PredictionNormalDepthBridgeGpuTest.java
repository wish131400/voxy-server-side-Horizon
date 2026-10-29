package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

/** Rasterizes the actual final Voxy shader across independent source/target clip conventions. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
@EnabledIfSystemProperty(named = "vss.voxyJar", matches = ".+")
class PredictionNormalDepthBridgeGpuTest {
    @Test void actualFinalBlitPreservesDepthAcrossAllClipRangesAndZDirections() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(8, 8, "VSS final depth contract", 0, 0);
        assertNotEquals(0L, window);
        try (var jar = new ZipFile(System.getProperty("vss.voxyJar"))) {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            int vao = glCreateVertexArrays();
            int sourceTexture = texture(), destinationTexture = texture();
            int framebuffer = glCreateFramebuffers();
            glNamedFramebufferTexture(framebuffer, GL_DEPTH_ATTACHMENT, destinationTexture, 0);
            glNamedFramebufferDrawBuffer(framebuffer, GL_NONE);
            glNamedFramebufferReadBuffer(framebuffer, GL_NONE);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER));
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glBindVertexArray(vao);
            glBindTextureUnit(0, sourceTexture);
            glViewport(0, 0, 1, 1);
            glEnable(GL_DEPTH_TEST);
            glDepthMask(true);
            glDepthFunc(GL_ALWAYS);
            glDisable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            String path = "voxy:post/blit_texture_depth_cutout.frag";
            String original = read(jar, path);
            String patched = PredictionNormalDepthBridge.patch(path, original);
            assertNotEquals(original, patched, "The actual published Voxy final shader must match the bridge contract");
            String source = expand(jar, patched);
            for (boolean sourceZero : new boolean[]{false, true}) for (boolean sourceReverse : new boolean[]{false, true}) {
                String defines = (sourceZero ? "#define USE_ZERO_ONE_DEPTH\n" : "")
                        + (sourceReverse ? "#define USE_REVERSE_Z\n" : "");
                int vertex = shader(GL_VERTEX_SHADER, """
                        #version 450 core
                        out vec2 UV;
                        void main() {
                            vec2 p = gl_VertexID == 0 ? vec2(-1,-1) : gl_VertexID == 1 ? vec2(3,-1) : vec2(-1,3);
                            UV = p * 0.5 + 0.5;
                            gl_Position = vec4(p,0,1);
                        }
                        """);
                int fragment = shader(GL_FRAGMENT_SHADER, source.replaceFirst("\n", "\n" + defines));
                int program = glCreateProgram();
                glAttachShader(program, vertex);
                glAttachShader(program, fragment);
                glLinkProgram(program);
                assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
                glUseProgram(program);
                glUniform1i(glGetUniformLocation(program, "VssNormalDepthBridge"), 1);
                glUniform2f(glGetUniformLocation(program, "VssSourceDepthTransform"), sourceZero ? 1 : 2, sourceZero ? 0 : -1);
                Matrix4f sourceProjection = projection(sourceZero, sourceReverse, 1000);
                glUniformMatrix4fv(1, false, sourceProjection.invert(new Matrix4f()).get(new float[16]));
                for (boolean targetZero : new boolean[]{false, true}) for (boolean targetReverse : new boolean[]{false, true}) {
                    Matrix4f targetProjection = projection(targetZero, targetReverse, 2000);
                    glUniformMatrix4fv(2, false, targetProjection.get(new float[16]));
                    float epsilon = 1f / 16777215f;
                    glUniform4f(glGetUniformLocation(program, "VssDestinationDepthTransform"),
                            targetZero ? 1 : .5f, targetZero ? 0 : .5f,
                            targetReverse ? epsilon : 0, targetReverse ? 1 : 1 - epsilon);
                    glClipControl(GL_LOWER_LEFT, targetZero ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
                    for (float distance : new float[]{.11f, 1, 200, 900}) {
                        var input = sourceProjection.transform(new Vector4f(0, 0, -distance, 1));
                        float sourceDepth = input.z / input.w;
                        if (!sourceZero) sourceDepth = sourceDepth * .5f + .5f;
                        glTextureSubImage2D(sourceTexture, 0, 0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, new float[]{sourceDepth});
                        glClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, new float[]{.123f});
                        glDrawArrays(GL_TRIANGLES, 0, 3);
                        float[] actual = new float[1];
                        glReadPixels(0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, actual);
                        var output = targetProjection.transform(new Vector4f(0, 0, -distance, 1));
                        float expected = output.z / output.w;
                        if (!targetZero) expected = expected * .5f + .5f;
                        expected = Math.clamp(expected, targetReverse ? epsilon : 0, targetReverse ? 1 : 1 - epsilon);
                        assertEquals(expected, actual[0], 2e-5f, "source=" + sourceZero + "/" + sourceReverse
                                + " target=" + targetZero + "/" + targetReverse + " distance=" + distance);
                    }
                }
                glDeleteProgram(program);
                glDeleteShader(vertex);
                glDeleteShader(fragment);
            }
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(sourceTexture);
            glDeleteTextures(destinationTexture);
            glDeleteVertexArrays(vao);
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static Matrix4f projection(boolean zero, boolean reverse, float far) {
        return new Matrix4f().perspective((float) Math.toRadians(70), 1, reverse ? far : .1f, reverse ? .1f : far, zero);
    }
    private static int texture() {
        int texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, GL_DEPTH_COMPONENT32F, 1, 1);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }
    private static String read(ZipFile jar, String id) throws Exception {
        String[] parts = id.split(":", 2);
        var entry = jar.getEntry("assets/" + parts[0] + "/shaders/" + parts[1]);
        assertNotNull(entry, id);
        return new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
    private static String expand(ZipFile jar, String source) throws Exception {
        var matcher = Pattern.compile("(?m)^#import <([^>]+)>[ \t]*$").matcher(source);
        var result = new StringBuilder();
        int previous = 0;
        while (matcher.find()) {
            result.append(source, previous, matcher.start()).append(expand(jar, read(jar, matcher.group(1))));
            previous = matcher.end();
        }
        return result.append(source.substring(previous)).toString();
    }
    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
        return shader;
    }
}
