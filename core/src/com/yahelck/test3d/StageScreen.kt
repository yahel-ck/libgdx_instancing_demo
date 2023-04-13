package com.yahelck.test3d

import com.badlogic.gdx.Screen
import com.badlogic.gdx.scenes.scene2d.Stage


open class StageScreen(val stage: Stage = Stage()) : Screen {
    override fun show() {}

    override fun render(delta: Float) {
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        // stage.viewport.update(width, height)
    }

    override fun pause() {}

    override fun resume() {}

    override fun hide() {}

    override fun dispose() {
        stage.dispose()
    }
}

