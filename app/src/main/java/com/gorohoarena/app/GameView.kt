package com.gorohoarena.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private var gameThread: Thread? = null
    @Volatile private var running = false
    private val holderRef: SurfaceHolder = holder

    // ---- world state ----
    private data class Player(var x: Float, var y: Float, var hp: Int = 100, val maxHp: Int = 100, val speed: Float = 260f)
    private data class Zombie(var x: Float, var y: Float, var hp: Int, val maxHp: Int, val speed: Float, var wobble: Float = 0f)
    private data class Bullet(var x: Float, var y: Float, val vx: Float, val vy: Float)
    private data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Int)

    private lateinit var player: Player
    private val zombies = mutableListOf<Zombie>()
    private val bullets = mutableListOf<Bullet>()
    private val particles = mutableListOf<Particle>()

    private var score = 0
    private var wave = 1
    private var zombiesLeftInWave = 6
    private var spawnTimer = 0f
    private var fireTimer = 0f
    private var gameOver = false

    private var w = 0f
    private var h = 0f

    // ---- input ----
    private var stickCenterX = 0f
    private var stickCenterY = 0f
    private var stickActiveId = -1
    private var moveX = 0f
    private var moveY = 0f
    private val stickRadius = 130f

    private var fireActiveId = -1
    private var fireCenterX = 0f
    private var fireCenterY = 0f
    private val fireRadius = 95f

    // ---- paint ----
    private val bgPaint = Paint()
    private val playerPaint = Paint().apply { color = Color.rgb(0x2b, 0x3a, 0x52); isAntiAlias = true }
    private val gunPaint = Paint().apply { color = Color.rgb(0x1c, 0x19, 0x14) }
    private val zombieBodyPaint = Paint().apply { color = Color.rgb(0x5f, 0x8f, 0x52); isAntiAlias = true }
    private val zombieEyePaint = Paint().apply { color = Color.rgb(0xee, 0xf2, 0xe0); isAntiAlias = true }
    private val hpBackPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val hpFillPaint = Paint().apply { color = Color.rgb(0xc1, 0x65, 0x4a) }
    private val bulletPaint = Paint().apply { color = Color.rgb(0x7f, 0xa6, 0x87); isAntiAlias = true }
    private val stickBasePaint = Paint().apply { color = Color.argb(50, 230, 225, 215) }
    private val stickKnobPaint = Paint().apply { color = Color.argb(140, 230, 225, 215) }
    private val fireBtnPaint = Paint().apply { color = Color.argb(215, 193, 101, 74); isAntiAlias = true }
    private val fireTextPaint = Paint().apply {
        color = Color.rgb(0xff, 0xf2, 0xea); isAntiAlias = true; textSize = 30f; textAlign = Paint.Align.CENTER
    }
    private val hudPaint = Paint().apply {
        color = Color.argb(190, 20, 16, 10); isAntiAlias = true
    }
    private val hudTextPaint = Paint().apply {
        isAntiAlias = true; textSize = 42f; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        holderRef.addCallback(this)
        resetGame()
    }

    private fun resetGame() {
        player = Player(0.5f, 0.78f)
        zombies.clear(); bullets.clear(); particles.clear()
        score = 0; wave = 1; zombiesLeftInWave = 6
        spawnTimer = 0f; fireTimer = 0f; gameOver = false
    }

    // ---- surface lifecycle ----
    override fun surfaceCreated(holder: SurfaceHolder) {
        w = width.toFloat(); h = height.toFloat()
        stickCenterX = 160f; stickCenterY = h - 220f
        fireCenterX = w - 150f; fireCenterY = h - 210f
        running = true
        gameThread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        stickCenterX = 160f; stickCenterY = h - 220f
        fireCenterX = w - 150f; fireCenterY = h - 210f
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        gameThread?.join()
    }

    fun resumeGame() {
        if (!running && holderRef.surface.isValid) {
            running = true
            gameThread = Thread(this).also { it.start() }
        }
    }

    fun pauseGame() {
        running = false
        try { gameThread?.join() } catch (e: InterruptedException) { /* ignore */ }
    }

    // ---- touch input ----
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = event.getX(idx); val y = event.getY(idx)
                val id = event.getPointerId(idx)
                if (gameOver) {
                    resetGame()
                    return true
                }
                if (x < w * 0.55f && stickActiveId == -1) {
                    stickActiveId = id
                } else if (fireActiveId == -1) {
                    fireActiveId = id
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i); val y = event.getY(i)
                    if (id == stickActiveId) {
                        var dx = x - stickCenterX
                        var dy = y - stickCenterY
                        val dist = hypot(dx, dy)
                        if (dist > stickRadius) { dx = dx / dist * stickRadius; dy = dy / dist * stickRadius }
                        moveX = dx / stickRadius
                        moveY = dy / stickRadius
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                if (id == stickActiveId) { stickActiveId = -1; moveX = 0f; moveY = 0f }
                if (id == fireActiveId) { fireActiveId = -1 }
            }
            MotionEvent.ACTION_CANCEL -> {
                stickActiveId = -1; fireActiveId = -1; moveX = 0f; moveY = 0f
            }
        }
        return true
    }

    // ---- game loop ----
    override fun run() {
        var lastTime = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - lastTime) / 1_000_000_000f
            lastTime = now
            dt = min(dt, 0.05f)

            if (!gameOver) update(dt)

            val canvas = holderRef.lockCanvas() ?: continue
            try {
                draw(canvas)
            } finally {
                holderRef.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun update(dt: Float) {
        // player movement
        player.x += moveX * (player.speed / w) * dt
        player.y += moveY * (player.speed * 0.6f / h) * dt
        player.x = player.x.coerceIn(0.08f, 0.92f)
        player.y = player.y.coerceIn(0.55f, 0.9f)

        // firing
        fireTimer -= dt
        if (fireActiveId != -1 && fireTimer <= 0f) {
            bullets.add(Bullet(player.x, player.y - 0.03f, 0f, -0.9f))
            fireTimer = 0.12f
        }

        // spawning
        spawnTimer -= dt
        if (zombiesLeftInWave > 0 && spawnTimer <= 0f) {
            val lane = Random.nextFloat()
            val hp = 3 + wave / 2
            zombies.add(Zombie(0.15f + lane * 0.7f, -0.05f, hp, hp, 0.03f + Random.nextFloat() * 0.02f + wave * 0.003f))
            zombiesLeftInWave--
            spawnTimer = max(0.35f, 1.1f - wave * 0.05f)
        }
        if (zombiesLeftInWave <= 0 && zombies.isEmpty()) {
            wave++
            zombiesLeftInWave = 5 + wave * 2
        }

        // bullets move
        val bulletIt = bullets.iterator()
        while (bulletIt.hasNext()) {
            val b = bulletIt.next()
            b.y += b.vy * dt
            if (b.y < -0.1f) bulletIt.remove()
        }

        // zombies move
        for (z in zombies) {
            z.y += z.speed * dt
            z.wobble += dt * 4f
            z.x += sin(z.wobble) * 0.0015f
        }

        // bullet vs zombie collisions
        val bIter = bullets.iterator()
        while (bIter.hasNext()) {
            val b = bIter.next()
            var hitSomething = false
            for (z in zombies) {
                if (z.hp <= 0) continue
                val dx = (b.x - z.x) * (w / h)
                val dy = b.y - z.y
                if (hypot(dx, dy) < 0.035f) {
                    z.hp -= 1
                    addParticles(z.x, z.y, Color.rgb(0x7f, 0xa6, 0x87))
                    if (z.hp <= 0) {
                        score += 10
                    }
                    hitSomething = true
                    break
                }
            }
            if (hitSomething) bIter.remove()
        }
        zombies.removeAll { it.hp <= 0 }

        // zombie reaches player
        val zIter = zombies.iterator()
        while (zIter.hasNext()) {
            val z = zIter.next()
            if (z.y > 0.92f) {
                player.hp -= 12
                addParticles(player.x, player.y, Color.rgb(0xc1, 0x65, 0x4a))
                zIter.remove()
            }
        }

        // particles
        val pIter = particles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            p.x += p.vx * dt; p.y += p.vy * dt; p.life -= dt
            if (p.life <= 0f) pIter.remove()
        }

        if (player.hp <= 0) {
            player.hp = 0
            gameOver = true
        }
    }

    private fun addParticles(x: Float, y: Float, color: Int) {
        repeat(8) {
            particles.add(
                Particle(
                    x, y,
                    (Random.nextFloat() - 0.5f) * 0.4f,
                    (Random.nextFloat() - 0.5f) * 0.4f,
                    0.4f,
                    color
                )
            )
        }
    }

    // ---- rendering ----
    private fun draw(canvas: Canvas) {
        // sky/ground gradient background
        val skyH = h * 0.55f
        canvas.drawColor(Color.rgb(0x7e, 0xc8, 0xf2))
        canvas.drawRect(0f, skyH, w, h, Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, skyH, 0f, h,
                Color.rgb(0xd9, 0xc7, 0x9a), Color.rgb(0xc9, 0xa7, 0x6b),
                android.graphics.Shader.TileMode.CLAMP
            )
        })

        // lane guides
        val lanePaint = Paint().apply { color = Color.argb(38, 255, 255, 255); strokeWidth = 3f }
        for (f in floatArrayOf(0.2f, 0.5f, 0.8f)) {
            canvas.drawLine(f * w, h * 0.5f, f * w, h, lanePaint)
        }

        // zombies
        for (z in zombies) {
            val x = z.x * w; val y = z.y * h
            val size = 46f + z.y * 40f
            canvas.drawOval(RectF(x - size * 0.55f, y - size * 0.7f, x + size * 0.55f, y + size * 0.7f), zombieBodyPaint)
            canvas.drawCircle(x - size * 0.2f, y - size * 0.1f, size * 0.12f, zombieEyePaint)
            canvas.drawCircle(x + size * 0.2f, y - size * 0.1f, size * 0.12f, zombieEyePaint)
            val barW = size * 0.9f
            canvas.drawRect(x - barW / 2, y - size * 0.9f, x + barW / 2, y - size * 0.9f + 6f, hpBackPaint)
            canvas.drawRect(x - barW / 2, y - size * 0.9f, x - barW / 2 + barW * (z.hp / z.maxHp.toFloat()), y - size * 0.9f + 6f, hpFillPaint)
        }

        // bullets
        for (b in bullets) {
            canvas.drawCircle(b.x * w, b.y * h, 9f, bulletPaint)
        }

        // particles
        for (p in particles) {
            val paint = Paint().apply { color = p.color; alpha = (255 * max(0f, p.life / 0.4f)).toInt(); isAntiAlias = true }
            canvas.drawCircle(p.x * w, p.y * h, 6f, paint)
        }

        // player
        val px = player.x * w; val py = player.y * h
        canvas.drawOval(RectF(px - 36f, py - 46f, px + 36f, py + 46f), playerPaint)
        canvas.drawCircle(px, py - 32f, 20f, Paint().apply { color = Color.rgb(0xc9, 0xa7, 0x6b); isAntiAlias = true })
        canvas.drawRect(px - 6f, py - 90f, px + 6f, py - 40f, gunPaint)

        drawHud(canvas)
        drawControls(canvas)

        if (gameOver) drawGameOver(canvas)
    }

    private fun drawHud(canvas: Canvas) {
        val pad = 16f
        hudTextPaint.color = Color.rgb(0xe0, 0x57, 0x4a)
        canvas.drawRoundRect(RectF(pad, pad, pad + 190f, pad + 56f), 10f, 10f, hudPaint)
        canvas.drawText("\u2764 ${player.hp}", pad + 16f, pad + 38f, hudTextPaint)

        hudTextPaint.color = Color.rgb(0x7f, 0xa6, 0x87)
        val waveText = "\u0412\u043e\u043b\u043d\u0430 $wave"
        val waveW = hudTextPaint.measureText(waveText) + 32f
        canvas.drawRoundRect(RectF(w / 2 - waveW / 2, pad, w / 2 + waveW / 2, pad + 56f), 10f, 10f, hudPaint)
        canvas.drawText(waveText, w / 2 - waveW / 2 + 16f, pad + 38f, hudTextPaint)

        hudTextPaint.color = Color.rgb(0xc9, 0xa2, 0x27)
        val scoreText = "$ $score"
        val scoreW = hudTextPaint.measureText(scoreText) + 32f
        canvas.drawRoundRect(RectF(w - pad - scoreW, pad, w - pad, pad + 56f), 10f, 10f, hudPaint)
        canvas.drawText(scoreText, w - pad - scoreW + 16f, pad + 38f, hudTextPaint)
    }

    private fun drawControls(canvas: Canvas) {
        canvas.drawCircle(stickCenterX, stickCenterY, stickRadius, stickBasePaint)
        val knobX = stickCenterX + moveX * stickRadius
        val knobY = stickCenterY + moveY * stickRadius
        canvas.drawCircle(knobX, knobY, 55f, stickKnobPaint)

        val fp = if (fireActiveId != -1) Paint(fireBtnPaint).apply { alpha = 255 } else fireBtnPaint
        canvas.drawCircle(fireCenterX, fireCenterY, fireRadius, fp)
        canvas.drawText("\u041e\u0413\u041e\u041d\u042c", fireCenterX, fireCenterY + 10f, fireTextPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, Paint().apply { color = Color.argb(210, 10, 8, 6) })
        val title = Paint().apply {
            color = Color.rgb(0xc9, 0xa2, 0x27); textAlign = Paint.Align.CENTER
            textSize = 64f; isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("\u0418\u0433\u0440\u0430 \u043e\u043a\u043e\u043d\u0447\u0435\u043d\u0430", w / 2, h / 2 - 40f, title)
        val sub = Paint().apply {
            color = Color.rgb(0xb9, 0xb0, 0xa3); textAlign = Paint.Align.CENTER
            textSize = 34f; isAntiAlias = true
        }
        canvas.drawText("\$$score \u2022 \u0432\u043e\u043b\u043d\u0430 $wave", w / 2, h / 2 + 10f, sub)
        canvas.drawText("\u041a\u043e\u0441\u043d\u0438\u0441\u044c \u044d\u043a\u0440\u0430\u043d, \u0447\u0442\u043e\u0431\u044b \u043d\u0430\u0447\u0430\u0442\u044c \u0437\u0430\u043d\u043e\u0432\u043e", w / 2, h / 2 + 60f, sub)
    }
}
