package com.yahelck.test3d

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController
import com.badlogic.gdx.graphics.glutils.InstanceBufferObject
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.BufferUtils
import net.mgsx.gltf.loaders.gltf.GLTFLoader
import net.mgsx.gltf.scene3d.attributes.FogAttribute
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute
import net.mgsx.gltf.scene3d.lights.DirectionalLightEx
import net.mgsx.gltf.scene3d.lights.DirectionalShadowLight
import net.mgsx.gltf.scene3d.scene.Scene
import net.mgsx.gltf.scene3d.scene.SceneAsset
import net.mgsx.gltf.scene3d.scene.SceneManager
import net.mgsx.gltf.scene3d.scene.SceneSkybox
import net.mgsx.gltf.scene3d.shaders.PBRDepthShaderProvider
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider
import net.mgsx.gltf.scene3d.utils.IBLBuilder
import java.nio.IntBuffer
import kotlin.math.sign
import kotlin.random.Random


class Test3DGame : Game() {
    private val camera = PerspectiveCamera()
    private var cameraController: CameraInputController? = null
    private val rand = Random(123456789L)

    private var sceneManager: SceneManager? = null
    private var sceneAsset: SceneAsset? = null
    private var brdfLUT: Texture? = null

    private var font: BitmapFont? = null
    private var debugLabel: Label? = null

    override fun create() {
        setupCamera()
        cameraController = CameraInputController(camera)
        cameraController?.target?.set(camera.position)
        Gdx.input.inputProcessor = cameraController
        val sceneManager = createSceneManager()
        sceneManager.camera = camera
        loadModels()
        if (USE_INSTANCED_RENDERING) {
            sceneManager.addScene(getRandomInstancedScene())
        } else {
            repeat(INSTANCE_COUNT) {
                sceneManager.addScene(getRandomScene())
            }
        }
        createScreen()
    }

    private fun setupCamera() {
        camera.apply {
            fieldOfView = 100f
            viewportWidth = Gdx.graphics.width.toFloat()
            viewportHeight = Gdx.graphics.height.toFloat()
            near = 0.2f
            far = 1000f
            position.set(0f, 0f, 0f)
            update()
        }
    }

    private fun createSceneManager(): SceneManager {
        val lightDir = Vector3(-0.2f, -1f, -1f).nor()
        val shadowBoundSide = SPAWN_BOX_SIDE * 1.1f
        val shadowLight = DirectionalShadowLight(4096, 4096, shadowBoundSide,  shadowBoundSide, 0f, shadowBoundSide)
        shadowLight.set(1f, 1f, 0.9f, lightDir)
        shadowLight.intensity = 3f

        // setup quick IBL (image based lighting)
        val fogColor = Color(1f, 1f, 1f, 1f)
        val iblBuilder = IBLBuilder.createOutdoor(shadowLight).apply {
            farGroundColor.set(fogColor) // .set(0.94f, 0.40f, 0.36f, 1f)
            nearGroundColor.set(0.99f, 0.5f, 0.4f, 1f)
            farSkyColor.set(fogColor) // .set(.9f, .95f, 1f, 1f)
            nearSkyColor.set(.7f, .8f, 1f, 1f)
        }
        val diffuseCubemap = iblBuilder.buildIrradianceMap(256)
        val specularCubemap = iblBuilder.buildRadianceMap(10)
        val envCubemap = iblBuilder.buildEnvMap(1024)
        iblBuilder.dispose()

        brdfLUT = Texture(Gdx.files.classpath("net/mgsx/gltf/shaders/brdfLUT.png"))

        val shaderProviderConfig = PBRShaderConfig().apply {
            vertexShader = Gdx.files.internal("shaders/instancing_pbr.vs.glsl").readString()
            fragmentShader = PBRShaderProvider.getDefaultFragmentShader()
            numBones = 4
            numDirectionalLights = 1
            numPointLights = 0
            numSpotLights = 0
        }
        val shaderProvider = PBRShaderProvider.createDefault(shaderProviderConfig)

        val depthProviderConfig = DepthShader.Config().apply {
            vertexShader = Gdx.files.internal("shaders/instancing_depth.vs.glsl").readString()
            fragmentShader = PBRDepthShaderProvider.getDefaultFragmentShader();
            numBones = 4
            numDirectionalLights = 1
            numPointLights = 0
            numSpotLights = 0
        }
        val depthProvider = PBRShaderProvider.createDefaultDepth(depthProviderConfig)

        val sceneManager = SceneManager(shaderProvider, depthProvider).apply {
            setAmbientLight(0.9f)
            environment.apply {
                set(PBRTextureAttribute(PBRTextureAttribute.BRDFLUTTexture, brdfLUT))
                set(PBRCubemapAttribute.createSpecularEnv(specularCubemap))
                set(PBRCubemapAttribute.createDiffuseEnv(diffuseCubemap))
                set(ColorAttribute(ColorAttribute.Fog, fogColor))
                set(FogAttribute(FogAttribute.FogEquation).set(3f, 130f, 0.8f))
                add(shadowLight)
            }
            skyBox = SceneSkybox(envCubemap)
        }

        this.sceneManager = sceneManager
        return sceneManager
    }

    private fun loadModels() {
        sceneAsset = loadScene()
    }

    private fun getRandomScene(): Scene {
        val scene = Scene(sceneAsset!!.scene)
        setRandomTransform(scene.modelInstance.transform)
        return scene
    }

    private fun getRandomInstancedScene(): Scene {
        val scene = Scene(sceneAsset!!.scene)
        enableInstancedRendering(scene.modelInstance)
        return scene
    }

    private fun enableInstancedRendering(model: ModelInstance) {
        val mat = Matrix4()
        val instances =  InstanceBufferObject(true, INSTANCE_COUNT, *getMatrix4Attributes())
        instances.numInstances = INSTANCE_COUNT

        val transforms = FloatArray(INSTANCE_COUNT * MAT_FLOAT_COUNT)
        repeat(INSTANCE_COUNT) { index ->
            setRandomTransform(mat)
            mat.values.copyInto(transforms, index * MAT_FLOAT_COUNT)
        }

        instances.setInstanceData(transforms, 0, transforms.size)
        model.instances = instances
    }

    private fun setRandomTransform(out: Matrix4) {
        out.setToRotation(
            rand.nextFloat(),
            rand.nextFloat(),
            rand.nextFloat(),
            rand.nextFloat() * 360f
        )
        val x = getRandomCoordinate()
        val y = getRandomCoordinate()
        val z = getRandomCoordinate()
        out.translate(x, y, z)
    }

    private fun getRandomCoordinate(): Float {
        val scl = rand.nextFloat() - 0.5f
        return SPAWN_BOX_INNER_SPACE / 2f * scl.sign + scl * (SPAWN_BOX_SIDE - SPAWN_BOX_INNER_SPACE)
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
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        cameraController?.apply {
            //v.set(camera.direction).nor().scl(CAMERA_SPEED * Gdx.graphics.deltaTime)
            //target.add(v)
            //camera.position.add(v)
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
        const val CAMERA_SPEED = 16f
        const val SPAWN_BOX_SIDE = 20
        const val SPAWN_BOX_INNER_SPACE = 6
        const val INSTANCE_COUNT: Int = SPAWN_BOX_SIDE * SPAWN_BOX_SIDE * SPAWN_BOX_SIDE / 200
        const val DRAW_COMMAND_INT_COUNT: Int = 5
        const val DRAW_COMMAND_BYTE_COUNT: Int = DRAW_COMMAND_INT_COUNT * 4
        const val USE_INSTANCED_RENDERING = false

        private fun IntBuffer.putDrawCommand(count: Int, instanceCount: Int, firstIndex: Int, baseVertex: Int) {
            // Last value must be zero (reserved)
            this.put(
                intArrayOf(
                    count, instanceCount, firstIndex, baseVertex, 0
                )
            )
        }

        private fun getMatrix4Attributes(): Array<VertexAttribute> {
            return Array(4) { index ->
                VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_worldTrans", index)
            }
        }

        private fun loadScene(): SceneAsset {
            return GLTFLoader().load(Gdx.files.internal("stone_pillar.gltf"))
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

