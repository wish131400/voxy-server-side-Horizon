package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

/** Actual 1.21.11 final-blit shader; its Minecraft target uses forward [-1,1] depth. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
@EnabledIfSystemProperty(named = "vss.voxyJar", matches = ".+")
class PredictionNormalDepthBridgeGpuTest {
    @Test void actualFinalBlitPreservesMinecraftDepthAndForegroundForPublishedRelease() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(8, 8, "VSS 1.21.11 final depth contract", 0, 0);
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
            // Actual 1.21.11 RenderPipeline.Builder default; unlike 26.x there is
            // no device clip-range or DepthStencilState.DEFAULT reverse-Z API.
            glDepthFunc(GL_LEQUAL);
            glDisable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            String path = "voxy:post/blit_texture_depth_cutout.frag";
            String original = read(jar, path);
            String patched = PredictionNormalDepthBridge.patch(path, original);
            assertNotEquals(original, patched, "The published final shader must match the bridge contract");
            String source = expand(jar, patched);
            // GameRenderer.getProjectionMatrix calls the four-argument JOML
            // perspective overload, which means negative-one-to-one and forward Z.
            Matrix4f targetProjection = new Matrix4f().perspective((float) Math.toRadians(70), 1, .05f, 2000);
            int assertions = 0;
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
                glClipControl(GL_LOWER_LEFT, sourceZero ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
                assertEquals(sourceZero, PredictionNormalDepthBridge.sourceZeroToOne());
                PredictionNormalDepthBridge.bind(PredictionNormalDepthBridge.sourceZeroToOne());
                assertEquals(1, glGetUniformi(program, glGetUniformLocation(program, "VssNormalDepthBridge")));
                Matrix4f sourceProjection = new Matrix4f().perspective((float) Math.toRadians(70), 1,
                        sourceReverse ? 1000 : .05f, sourceReverse ? .05f : 1000, sourceZero);
                glUniformMatrix4fv(1, false, sourceProjection.invert(new Matrix4f()).get(new float[16]));
                glUniformMatrix4fv(2, false, targetProjection.get(new float[16]));
                glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
                for (float distance : new float[]{.11f, 1, 200, 900}) {
                    var input = sourceProjection.transform(new Vector4f(0, 0, -distance, 1));
                    float sourceDepth = input.z / input.w;
                    if (!sourceZero) sourceDepth = sourceDepth * .5f + .5f;
                    glTextureSubImage2D(sourceTexture, 0, 0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, new float[]{sourceDepth});
                    glClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, new float[]{1});
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    float expected = minecraftDepth(targetProjection, distance);
                    assertEquals(expected, depth(), 2e-5f,
                            "source=" + sourceZero + "/" + sourceReverse + " distance=" + distance);
                    float foreground = minecraftDepth(targetProjection, distance * .75f);
                    glClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, new float[]{foreground});
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    assertEquals(foreground, depth(), 2e-6f, "The final blit must preserve closer Minecraft geometry");
                    assertions += 2;
                }
                for (float clearSource : new float[]{0, 1}) {
                    glTextureSubImage2D(sourceTexture, 0, 0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, new float[]{clearSource});
                    glClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, new float[]{.123f});
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    assertEquals(.123f, depth(), 2e-6f, "Unpopulated source depth must leave destination geometry intact");
                    assertions++;
                }
                glDeleteProgram(program);
                glDeleteShader(vertex);
                glDeleteShader(fragment);
            }
            assertEquals(40, assertions);
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(sourceTexture);
            glDeleteTextures(destinationTexture);
            glDeleteVertexArrays(vao);
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static float minecraftDepth(Matrix4f projection, float distance) {
        var clip = projection.transform(new Vector4f(0, 0, -distance, 1));
        return Math.max(0, Math.min(clip.z / clip.w * .5f + .5f, 1 - 1f / 16777215f));
    }
    private static float depth() {
        float[] value = new float[1];
        glReadPixels(0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, value);
        return value[0];
    }
    private static int texture() {
        int texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, GL_DEPTH_COMPONENT32F, 1, 1);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }
    private static String read(ZipFile jar, String id) throws Exception {
        return dev.xantha.vss.testutil.VoxyShaderResources.read(jar, id);
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
