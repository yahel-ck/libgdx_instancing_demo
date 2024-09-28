package com.yahelck.test3d

import com.badlogic.gdx.Application.ApplicationType
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
import net.mgsx.gltf.loaders.gltf.GLTFLoader
import net.mgsx.gltf.scene3d.attributes.FogAttribute
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute
import net.mgsx.gltf.scene3d.attributes.PBRFloatAttribute
import net.mgsx.gltf.scene3d.attributes.PBRFloatAttribute.ShadowBias
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute
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
    private val tmpV = Vector3()

    private var sceneManager: SceneManager? = null
    private var pillarSceneAsset: SceneAsset? = null
    private var cubeSceneAsset: SceneAsset? = null
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
        addScene(pillarSceneAsset!!, INSTANCE_COUNT, getFloorTransforms(INSTANCE_COUNT))
        addScene(cubeSceneAsset!!, 10, getPillarTransforms(10))

        createScreen()

        Gdx.app.log("TestInstance", "Finished create")
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
        val lightDir = Vector3(0.4f, -1f, 0.8f).nor()
        val shadowBoundSide = SPAWN_BOX_SIDE * 5f
        val shadowLight = DirectionalShadowLight(4096, 4096, shadowBoundSide, shadowBoundSide, 0f, shadowBoundSide)
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
                set(PBRFloatAttribute(ShadowBias, 1 / 256f))
                add(shadowLight)
            }
            skyBox = if (Gdx.app.type == ApplicationType.Android && Gdx.app.version <= 32)
                SceneSkybox(envCubemap, PBRShaderConfig.SRGB.NONE, null, true)
            else SceneSkybox(envCubemap)
        }

        this.sceneManager = sceneManager
        return sceneManager
    }

    private fun loadModels() {
        pillarSceneAsset = loadScene("models/stone_pillar.gltf")
        cubeSceneAsset = loadScene("models/stone_cube.gltf")
    }

    private fun addScene(
        sceneAsset: SceneAsset,
        count: Int,
        transforms: Iterator<Matrix4>,
        instanced: Boolean = USE_INSTANCED_RENDERING,
    ) {
        val sceneManager = this.sceneManager!!
        if (instanced) {
            sceneManager.addScene(createInstancedScene(sceneAsset, count, transforms))
        } else {
            for (mat in transforms) {
                val scene = Scene(sceneAsset.scene)
                scene.modelInstance.transform.set(mat)
                sceneManager.addScene(scene)
            }
        }
    }

    private fun createInstancedScene(sceneAsset: SceneAsset, count: Int, transforms: Iterator<Matrix4>): Scene {
        val instances = InstanceBufferObject(true, count, *getMatrix4Attributes())
        val floats = FloatArray(count * 16)

        Gdx.app.log("TestInstance", "Start copying transforms")
        var i = 0
        for (mat in transforms) {
            mat.values.copyInto(floats, i * 16, 0, 16)
            i += 1
        }
        instances.setInstanceData(floats, 0, floats.size)
        Gdx.app.log("TestInstance", "Finished copying transforms")

        val scene = Scene(sceneAsset.scene)
        scene.modelInstance.instances = instances
        return scene
    }

    private fun getRandomTransforms(count: Int) = iterator {
        val mat = Matrix4()
        repeat(count) {
            setRandomTransform(mat)
            yield(mat)
        }
    }

    private fun getPillarTransforms(count: Int) = iterator {
        val mat = Matrix4()
        repeat(count) { i ->
            mat.idt()
            mat.setTranslation((i / 2 - count / 2f) * 5f, -5f, 9.9f * (i % 2 - 0.5f))
            yield(mat)
        }
    }

    private fun getFloorTransforms(count: Int) = iterator {
        val mat = Matrix4()
        repeat(count) { i ->
            mat.setToRotation(Vector3.X, 90f)
            mat.setTranslation((i / 2 - count / 2f) * 2f, -10f, 9.9f * (i % 2 - 0.5f))
            yield(mat)
        }
    }

    private fun setRandomTransform(out: Matrix4) {
        out.setToRotation(
            rand.nextFloat() - 0.5f,
            rand.nextFloat() - 0.5f,
            rand.nextFloat() - 0.5f,
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

    var timer = 1f

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)

        cameraController?.apply {
            //tmpV.set(camera.direction).nor().scl(CAMERA_SPEED * Gdx.graphics.deltaTime)
            //target.add(tmpV)
            //camera.position.add(tmpV)
            target.set(camera.position)
            camera.update()
        }

        sceneManager?.apply {
            update(Gdx.graphics.deltaTime)
            render()
        }

        timer -= Gdx.graphics.deltaTime
        if (timer <= 0f) {
            debugLabel?.setText("${sumInstances()} instances, ${Gdx.graphics.framesPerSecond} fps, ${camera.position}")
            timer += 1f
        }

        super.render()
    }

    private fun sumInstances(): Int {
        val sceneManager = sceneManager ?: return 0
        var count = 0
        sceneManager.renderableProviders.forEach {
            count += if (it is Scene) it.modelInstance.instances?.numInstances ?: 1
            else 1
        }
        return count
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        sceneManager?.updateViewport(
            width.toFloat(), height.toFloat()
        )
    }

    override fun dispose() {
        pillarSceneAsset?.dispose()
    }

    companion object {
        const val MAT_FLOAT_COUNT = 4 * 4
        const val CAMERA_SPEED = 3f
        const val SPAWN_BOX_SIDE = 20
        const val SPAWN_BOX_INNER_SPACE = 4
        const val INSTANCE_COUNT: Int = SPAWN_BOX_SIDE * SPAWN_BOX_SIDE * SPAWN_BOX_SIDE / 200
        const val DRAW_COMMAND_INT_COUNT: Int = 5
        const val DRAW_COMMAND_BYTE_COUNT: Int = DRAW_COMMAND_INT_COUNT * 4
        const val USE_INSTANCED_RENDERING = true
        const val SPAWN_FLOOR = true

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

        private fun loadScene(gltfPath: String): SceneAsset {
            return GLTFLoader().load(Gdx.files.internal(gltfPath))
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

