package com.yahelck.test3d

import com.badlogic.gdx.graphics.g3d.Renderable
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute
import net.mgsx.gltf.scene3d.model.WeightVector
import net.mgsx.gltf.scene3d.shaders.PBRShader
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig

class InstancingShader(
    renderable: Renderable,
    config: PBRShaderConfig,
    prefix: String
) : PBRShader(renderable, config, prefix) {

}