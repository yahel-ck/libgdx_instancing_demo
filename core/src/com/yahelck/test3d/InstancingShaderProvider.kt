package com.yahelck.test3d

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g3d.Renderable
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider


class InstancingShaderProvider(
    config: PBRShaderConfig
) : PBRShaderProvider(config) {
    override fun createPrefixBase(
        renderable: Renderable,
        config: PBRShaderConfig
    ): String {
        var prefix = super.createPrefixBase(renderable, config)
        if (renderable.meshPart.mesh.isInstanced) {
            prefix += "#define instancedFlag"
        }
        return prefix
    }

    companion object {
        private var defaultVertexShader: String? = null

        fun createDefaultConfig(): PBRShaderConfig {
            val config = PBRShaderConfig()
            config.vertexShader = getDefaultVertexShader()
            config.fragmentShader = getDefaultFragmentShader()
            config.numDirectionalLights
            return config
        }

        fun getDefaultVertexShader(): String {
            if (defaultVertexShader == null)
                defaultVertexShader = Gdx.files.internal("instancing_pbr.vs.glsl").readString()
            return defaultVertexShader!!
        }

        fun createDefault(maxBones: Int): InstancingShaderProvider {
            val config = createDefaultConfig()
            config.numBones = maxBones
            return InstancingShaderProvider(config)
        }
    }
}

