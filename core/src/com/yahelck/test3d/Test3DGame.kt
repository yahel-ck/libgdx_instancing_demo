package com.yahelck.test3d

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.model.Node
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import net.mgsx.gltf.loaders.gltf.GLTFLoader
import net.mgsx.gltf.scene3d.attributes.FogAttribute
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute
import net.mgsx.gltf.scene3d.lights.DirectionalLightEx
import net.mgsx.gltf.scene3d.scene.Scene
import net.mgsx.gltf.scene3d.scene.SceneAsset
import net.mgsx.gltf.scene3d.scene.SceneManager
import net.mgsx.gltf.scene3d.scene.SceneSkybox
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider
import net.mgsx.gltf.scene3d.utils.IBLBuilder
import kotlin.random.Random


class Test3DGame : Game() {
    private val camera = PerspectiveCamera()
    private var cameraController: CameraInputController? = null
    private val rand = Random(System.nanoTime())
    private val v = Vector3()

    private var sceneManager: SceneManager? = null
    private var sceneAsset: SceneAsset? = null
    private var brdfLUT: Texture? = null

    private var font: BitmapFont? = null
    private var debugLabel: Label? = null

    override fun create() {
        setupCamera()
        cameraController = CameraInputController(camera)
        Gdx.input.inputProcessor = cameraController
        createSceneManager()
        sceneManager!!.camera = camera
        createModels()
        sceneManager!!.addScene(Scene(sceneAsset!!.scene))
        createScreen()
    }

    private fun setupCamera() {
        camera.apply {
            fieldOfView = 80f
            viewportWidth = Gdx.graphics.width.toFloat()
            viewportHeight = Gdx.graphics.height.toFloat()
            near = 0.2f
            far = 1000f
            position.set(0f, 0f, 0f)
            update()
        }
    }

    private fun createSceneManager() {
        // setup quick IBL (image based lighting)
        val light = DirectionalLightEx().set(1f, 1f, 1f, -1f, -0.8f, 0.2f)
        val fogColor = Color(1f, 1f, 1f, 1f)
        val iblBuilder = IBLBuilder.createOutdoor(light).apply {
            farGroundColor.set(fogColor) // .set(0.94f, 0.40f, 0.36f, 1f)
            nearGroundColor.set(0.71f, 0.42f, 0.34f, 1f)
            farSkyColor.set(fogColor) // .set(.9f, .95f, 1f, 1f)
            nearSkyColor.set(.7f, .8f, 1f, 1f)
        }
        val diffuseCubemap = iblBuilder.buildIrradianceMap(256)
        val specularCubemap = iblBuilder.buildRadianceMap(10)
        val envCubemap = iblBuilder.buildEnvMap(1024)
        iblBuilder.dispose()

        brdfLUT = Texture(Gdx.files.classpath("net/mgsx/gltf/shaders/brdfLUT.png"))

        val shaderProviderConfig = PBRShaderConfig().apply {
            vertexShader = Gdx.files.internal("instancing_pbr.vs.glsl").readString()
            fragmentShader = PBRShaderProvider.getDefaultFragmentShader()
            numBones = 24
        }

        sceneManager = SceneManager(
            PBRShaderProvider.createDefault(shaderProviderConfig),
            PBRShaderProvider.createDefaultDepth(24)
        ).apply {
            setAmbientLight(1f)
            environment.apply {
                set(PBRTextureAttribute(PBRTextureAttribute.BRDFLUTTexture, brdfLUT))
                set(PBRCubemapAttribute.createSpecularEnv(specularCubemap))
                set(PBRCubemapAttribute.createDiffuseEnv(diffuseCubemap))
                set(ColorAttribute(ColorAttribute.Fog, fogColor))
                set(FogAttribute(FogAttribute.FogEquation).set(3f, 130f, 0.8f))
            }
            skyBox = SceneSkybox(envCubemap)
        }
    }

    private fun createModels() {
        sceneAsset = loadScene()
        val model: Model = sceneAsset!!.scene.model
        model.calculateTransforms()
        val modelTransform = model.globalTransform

        val mat = Matrix4()
        val transforms = FloatArray(INSTANCE_COUNT * MAT_FLOAT_COUNT)
        repeat(INSTANCE_COUNT) { index ->
            setRandomTransform(mat)
            mat.mul(modelTransform)
            mat.values.copyInto(transforms, index * MAT_FLOAT_COUNT)
        }

        model.meshes.forEach {
            it.enableInstancedRendering(true, INSTANCE_COUNT, *getMatrix4Attributes())
            it.setInstanceData(transforms)
            Gdx.app.log("InstancedRendering", "Mesh attributes: ${it.vertexAttributes}")
        }
    }

    private fun setRandomTransform(out: Matrix4) {
        out.setToRotation(
            rand.nextFloat(),
            rand.nextFloat(),
            rand.nextFloat(),
            rand.nextFloat() * 360f
        )
        val x = (rand.nextFloat() - 0.5f) * SPAWN_BOX_SIDE
        val y = (rand.nextFloat() - 0.5f) * SPAWN_BOX_SIDE
        val z = (rand.nextFloat() - 0.5f) * SPAWN_BOX_SIDE
        out.translate(x, y, z)
    }

    private fun createScreen() {
        font = loadFont()
        debugLabel = Label("", Label.LabelStyle(font, Color.BLACK)).apply {
            wrap = false
            setAlignment(Align.left, Align.left)
        }
        screen = StageScreen().apply {
            stage.addActor(Table().apply {
                setFillParent(true)
                align(Align.bottomLeft)
                add(debugLabel).pad(0.05f * Gdx.graphics.width)
            })
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        cameraController?.apply {
            // v.set(camera.direction).nor().scl(CAMERA_SPEED * Gdx.graphics.deltaTime)
            // target.add(v)
            // camera.position.add(v)
            camera.update()
        }
        sceneManager?.apply {
            update(Gdx.graphics.deltaTime)
            render()
        }
        debugLabel?.setText("$INSTANCE_COUNT instances, ${Gdx.graphics.framesPerSecond} fps, ${camera.position}")
        super.render()
    }

    override fun dispose() {
        sceneAsset?.dispose()
    }

    companion object {
        const val MAT_FLOAT_COUNT = 4 * 4
        const val CAMERA_SPEED = 5f
        const val SPAWN_BOX_SIDE = 200
        const val INSTANCE_COUNT: Int = SPAWN_BOX_SIDE * SPAWN_BOX_SIDE * SPAWN_BOX_SIDE / 2000

        private fun getMatrix4Attributes(): Array<VertexAttribute> {
            return Array(4) { index ->
                VertexAttribute(VertexAttributes.Usage.Generic, 4, "u_worldTrans", index)
            }
        }

        private fun loadScene(): SceneAsset {
            return GLTFLoader().load(Gdx.files.internal("stone_pillar.gltf"))
        }

        private val Model.globalTransform: Matrix4
            get() {
                var leafNode: Node? = null
                nodes.forEachRecursive {
                    if (it.parts.size > 1 || (it.parts.size == 1 && leafNode != null)) {
                        throw UnsupportedOperationException(
                            "Can't find unique globalTransform for this model (it has more than one NodeParts)"
                        )
                    } else if (it.parts.size > 0) {
                        leafNode = it
                    }
                }
                if (leafNode == null)
                    throw UnsupportedOperationException("Can't find globalTransform for this model (it has no NodeParts)")
                else
                    return leafNode!!.globalTransform
            }

        private fun Iterable<Node>.forEachRecursive(block: (Node) -> Unit) {
            this.forEach {
                block(it)
                it.children.forEachRecursive(block)
            }
        }

        private fun loadFont(): BitmapFont {
            val generator = FreeTypeFontGenerator(Gdx.files.internal("SourceCodePro-Black.ttf"))
            val parameter = FreeTypeFontParameter()
            parameter.size = (Gdx.graphics.width * 0.03f).toInt()
            val font12 = generator.generateFont(parameter)
            generator.dispose()
            return font12
        }
    }
}

