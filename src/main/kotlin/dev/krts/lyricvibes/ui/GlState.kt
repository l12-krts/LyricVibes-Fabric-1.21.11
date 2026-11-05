package dev.krts.lyricvibes.ui

import org.lwjgl.opengl.GL33C.*

object GlState {
    private val caps = intArrayOf(GL_BLEND, GL_CULL_FACE, GL_DEPTH_TEST, GL_STENCIL_TEST, GL_SCISSOR_TEST)
    private val capOn = BooleanArray(caps.size)
    private val singles = intArrayOf(
        GL_CURRENT_PROGRAM, GL_ACTIVE_TEXTURE, GL_ARRAY_BUFFER_BINDING, GL_UNIFORM_BUFFER_BINDING,
        GL_BLEND_SRC_RGB, GL_BLEND_DST_RGB, GL_BLEND_SRC_ALPHA, GL_BLEND_DST_ALPHA,
        GL_BLEND_EQUATION_RGB, GL_BLEND_EQUATION_ALPHA, GL_CULL_FACE_MODE, GL_FRONT_FACE,
        GL_DEPTH_WRITEMASK, GL_STENCIL_WRITEMASK, GL_STENCIL_FUNC, GL_STENCIL_REF, GL_STENCIL_VALUE_MASK,
        GL_STENCIL_FAIL, GL_STENCIL_PASS_DEPTH_FAIL, GL_STENCIL_PASS_DEPTH_PASS,
        GL_DRAW_FRAMEBUFFER_BINDING, GL_READ_FRAMEBUFFER_BINDING, GL_VERTEX_ARRAY_BINDING,
        GL_UNPACK_ALIGNMENT, GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS,
    )
    private val vals = IntArray(singles.size)
    private val viewport = IntArray(4)
    private val scissor = IntArray(4)
    private val colorMask = IntArray(4)
    private var texture0 = 0
    private var sampler0 = 0
    private var ubo0 = 0
    private var ubo0Start = 0L
    private var ubo0Size = 0L

    private fun v(name: Int) = vals[singles.indexOf(name)]

    fun save() {
        for (i in caps.indices) capOn[i] = glIsEnabled(caps[i])
        for (i in singles.indices) vals[i] = glGetInteger(singles[i])
        glGetIntegerv(GL_VIEWPORT, viewport)
        glGetIntegerv(GL_SCISSOR_BOX, scissor)
        glGetIntegerv(GL_COLOR_WRITEMASK, colorMask)
        glActiveTexture(GL_TEXTURE0)
        texture0 = glGetInteger(GL_TEXTURE_BINDING_2D)
        sampler0 = glGetInteger(GL_SAMPLER_BINDING)
        ubo0 = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 0)
        ubo0Start = glGetInteger64i(GL_UNIFORM_BUFFER_START, 0)
        ubo0Size = glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 0)
    }

    fun prepare(width: Int, height: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        glActiveTexture(GL_TEXTURE0)
        glBindSampler(0, 0)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_SCISSOR_TEST)
        glColorMask(true, true, true, true)
    }

    fun restore() {
        glBindVertexArray(v(GL_VERTEX_ARRAY_BINDING))
        glUseProgram(v(GL_CURRENT_PROGRAM))
        glBindBuffer(GL_ARRAY_BUFFER, v(GL_ARRAY_BUFFER_BINDING))
        if (ubo0Size > 0) glBindBufferRange(GL_UNIFORM_BUFFER, 0, ubo0, ubo0Start, ubo0Size) else glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo0)
        glBindBuffer(GL_UNIFORM_BUFFER, v(GL_UNIFORM_BUFFER_BINDING))
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture0)
        glBindSampler(0, sampler0)
        glActiveTexture(v(GL_ACTIVE_TEXTURE))
        for (i in caps.indices) if (capOn[i]) glEnable(caps[i]) else glDisable(caps[i])
        glBlendFuncSeparate(v(GL_BLEND_SRC_RGB), v(GL_BLEND_DST_RGB), v(GL_BLEND_SRC_ALPHA), v(GL_BLEND_DST_ALPHA))
        glBlendEquationSeparate(v(GL_BLEND_EQUATION_RGB), v(GL_BLEND_EQUATION_ALPHA))
        glCullFace(v(GL_CULL_FACE_MODE))
        glFrontFace(v(GL_FRONT_FACE))
        glDepthMask(v(GL_DEPTH_WRITEMASK) != 0)
        glStencilMask(v(GL_STENCIL_WRITEMASK))
        glStencilFunc(v(GL_STENCIL_FUNC), v(GL_STENCIL_REF), v(GL_STENCIL_VALUE_MASK))
        glStencilOp(v(GL_STENCIL_FAIL), v(GL_STENCIL_PASS_DEPTH_FAIL), v(GL_STENCIL_PASS_DEPTH_PASS))
        glColorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0)
        glScissor(scissor[0], scissor[1], scissor[2], scissor[3])
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, v(GL_DRAW_FRAMEBUFFER_BINDING))
        glBindFramebuffer(GL_READ_FRAMEBUFFER, v(GL_READ_FRAMEBUFFER_BINDING))
        glPixelStorei(GL_UNPACK_ALIGNMENT, v(GL_UNPACK_ALIGNMENT))
        glPixelStorei(GL_UNPACK_ROW_LENGTH, v(GL_UNPACK_ROW_LENGTH))
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, v(GL_UNPACK_SKIP_PIXELS))
        glPixelStorei(GL_UNPACK_SKIP_ROWS, v(GL_UNPACK_SKIP_ROWS))
    }
}
