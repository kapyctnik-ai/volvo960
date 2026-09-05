package com.rentpoly.game

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rentpoly.game.databinding.ActivityGameBinding
import com.rentpoly.game.model.Board
import com.rentpoly.game.model.CellType
import com.rentpoly.game.model.Game
import com.rentpoly.game.model.GameEvent
import com.rentpoly.game.model.GameSave
import com.rentpoly.game.model.Phase
import com.rentpoly.game.model.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One screen: the board on top, the players and the controls underneath.
 *
 * The engine produces events; this class plays them — dice tumble, tokens
 * walk, cards pop up — and only when the queue is empty does it look at the
 * phase and decide which buttons to show. Bots take their turns through the
 * same pump, one step at a time, so their moves animate like the player's.
 */
class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOTS = "bots"
        const val EXTRA_CONTINUE = "continue"
        private const val PREFS = "rentpoly"
        private const val KEY_SAVE = "save"

        fun hasSave(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SAVE)
    }

    private lateinit var binding: ActivityGameBinding
    private lateinit var game: Game
    private val playerViews = ArrayList<TextView>()
    private var pumping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val restored = if (intent.getBooleanExtra(EXTRA_CONTINUE, false)) loadGame() else null
        game = restored ?: newGame(intent.getIntExtra(EXTRA_BOTS, 1))
        binding.board.game = game
        binding.board.onCellTapped = { showCell(it) }
        buildPlayerStrip()

        binding.buttonMain.setOnClickListener { onMain() }
        binding.buttonSecondary.setOnClickListener { onSecondary() }
        binding.buttonManage.setOnClickListener { showManage() }
        binding.buttonMenu.setOnClickListener { showMenu() }

        refresh()
        pump()
    }

    private fun newGame(bots: Int): Game {
        val roster = listOf(
            Triple("Аня", "🎩", Color.parseColor("#E63946")),
            Triple("Боря", "🐶", Color.parseColor("#2A9D8F")),
            Triple("Вера", "🚢", Color.parseColor("#F4A261")),
        )
        val players = ArrayList<Player>()
        players += Player(0, "Ты", Color.parseColor("#1D6FE0"), "🚗", isHuman = true)
        for (i in 0 until bots.coerceIn(1, 3)) {
            val (name, token, color) = roster[i]
            players += Player(players.size, "Бот $name", color, token, isHuman = false)
        }
        return Game(players)
    }

    private fun saveGame() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (game.phase is Phase.Over) prefs.edit().remove(KEY_SAVE).apply()
        else prefs.edit().putString(KEY_SAVE, GameSave.toJson(game)).apply()
    }

    private fun loadGame(): Game? =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SAVE, null)?.let { GameSave.fromJson(it) }

    // ------------------------------------------------------------ event pump

    private fun pump() {
        if (pumping) return
        pumping = true
        lifecycleScope.launch {
            try {
                while (true) {
                    while (game.events.isNotEmpty()) play(game.events.removeFirst())
                    refresh()
                    saveGame()
                    val p = game.player
                    if (p.isHuman || game.phase is Phase.Over) break
                    delay(if (game.phase == Phase.TurnEnd) 350 else 700)
                    game.botStep()
                }
            } finally {
                pumping = false
                refresh()
            }
        }
    }

    private suspend fun play(e: GameEvent) {
        when (e) {
            is GameEvent.Rolled -> {
                binding.board.centreText = "${game.players[e.player].name}: ${e.d1} + ${e.d2}"
                await { done -> binding.board.animateDice(e.d1, e.d2, done) }
            }
            is GameEvent.Moved -> await { done -> binding.board.animateWalk(e.player, e.from, e.steps, done) }
            is GameEvent.Jumped -> {
                binding.board.placeToken(e.player, e.to)
                delay(300)
            }
            is GameEvent.Text -> {
                binding.board.centreText = e.text
                binding.textLog.text = game.log.takeLast(3).joinToString("\n")
                refreshPlayers()
            }
            is GameEvent.CardDrawn -> {
                refreshPlayers()
                dialog(e.deck, e.text)
            }
            is GameEvent.Bought, is GameEvent.Paid -> {
                refreshPlayers()
                binding.board.invalidate()
            }
            is GameEvent.Bankrupt -> {
                refreshPlayers()
                binding.board.invalidate()
                dialog("Банкрот", "${game.players[e.player].name} выбывает из игры.")
            }
            is GameEvent.Winner -> {
                refreshPlayers()
                val w = game.players[e.player]
                val text = if (w.isHuman) "Ты победил! Всё имущество города — твоё." else "${w.name} побеждает. В следующий раз."
                AlertDialog.Builder(this)
                    .setTitle("Игра окончена")
                    .setMessage(text)
                    .setCancelable(false)
                    .setPositiveButton("В меню") { _, _ -> finish() }
                    .show()
            }
        }
    }

    private suspend fun await(start: (() -> Unit) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            start { if (cont.isActive) cont.resume(Unit) }
        }

    private suspend fun dialog(title: String, message: String) {
        val done = CompletableDeferred<Unit>()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> done.complete(Unit) }
            .show()
        done.await()
    }

    // ------------------------------------------------------------ controls

    private fun onMain() {
        if (pumping) return
        val p = game.player
        if (!p.isHuman) return
        when (val ph = game.phase) {
            Phase.Roll -> game.roll()
            is Phase.Buy -> game.buy()
            is Phase.Jail -> if (ph.mustPay || p.money >= Board.JAIL_FINE) game.jailPay() else game.jailRoll()
            is Phase.RaiseMoney -> game.raiseMoneyDone()
            Phase.TurnEnd -> game.endTurn()
            is Phase.Over -> finish()
        }
        pump()
    }

    private fun onSecondary() {
        if (pumping) return
        val p = game.player
        if (!p.isHuman) return
        when (val ph = game.phase) {
            is Phase.Buy -> game.decline()
            is Phase.Jail -> if (ph.canUseCard) game.jailUseCard() else game.jailRoll()
            else -> return
        }
        pump()
    }

    private fun refresh() {
        val p = game.player
        val human = p.isHuman
        binding.buttonMain.isEnabled = human && !pumping
        binding.buttonSecondary.isEnabled = human && !pumping
        binding.buttonSecondary.visibility = View.GONE
        when (val ph = game.phase) {
            Phase.Roll -> binding.buttonMain.text = "🎲 Бросить"
            is Phase.Buy -> {
                val cell = Board.cell(ph.cell)
                binding.buttonMain.text = "Купить за ${cell.price}"
                binding.buttonSecondary.text = "Отказаться"
                binding.buttonSecondary.visibility = View.VISIBLE
            }
            is Phase.Jail -> {
                binding.buttonMain.text = if (ph.mustPay) "Заплатить ${Board.JAIL_FINE}" else "Заплатить ${Board.JAIL_FINE}"
                binding.buttonSecondary.text = if (ph.canUseCard) "Карта освобождения" else "Бросить на дубль"
                binding.buttonSecondary.visibility = View.VISIBLE
            }
            is Phase.RaiseMoney -> binding.buttonMain.text = "Нужно ещё ${ph.needed} — готово"
            Phase.TurnEnd -> binding.buttonMain.text = "Закончить ход"
            is Phase.Over -> binding.buttonMain.text = "В меню"
        }
        if (!human && game.phase !is Phase.Over) binding.buttonMain.text = "Ходит ${p.name}…"
        binding.buttonManage.isEnabled = game.players[0].let { !it.bankrupt }
        binding.textLog.text = game.log.takeLast(3).joinToString("\n")
        refreshPlayers()
        binding.board.invalidate()
    }

    private fun buildPlayerStrip() {
        binding.playerStrip.removeAllViews()
        playerViews.clear()
        for (p in game.players) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(8, 10, 8, 10)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
            }
            binding.playerStrip.addView(tv)
            playerViews += tv
        }
        refreshPlayers()
    }

    private fun refreshPlayers() {
        for ((i, p) in game.players.withIndex()) {
            val tv = playerViews.getOrNull(i) ?: continue
            val props = game.holdingsOf(p).size
            val jail = if (p.inJail) " 🔒" else ""
            tv.text = if (p.bankrupt) "${p.token} ${p.name}\nбанкрот" else "${p.token} ${p.name}$jail\n${p.money} · $props 🏠"
            tv.alpha = if (p.bankrupt) 0.4f else 1f
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f
                setColor(p.color)
                if (i == game.current && !p.bankrupt) setStroke(6, Color.WHITE)
            }
            tv.background = bg
        }
    }

    // ------------------------------------------------------------ dialogs

    private fun showCell(index: Int) {
        val cell = Board.cell(index)
        val h = game.holdings[index]
        val sb = StringBuilder()
        when (cell.type) {
            CellType.PROPERTY -> {
                sb.append("Цена ${cell.price}, дом ${cell.houseCost}\n")
                sb.append("Аренда: ${cell.rent[0]} (${cell.rent[0] * 2} с полной группой)\n")
                sb.append("1 дом ${cell.rent[1]} · 2 дома ${cell.rent[2]} · 3 дома ${cell.rent[3]}\n")
                sb.append("4 дома ${cell.rent[4]} · отель ${cell.rent[5]}\n")
            }
            CellType.RAILWAY -> sb.append("Цена ${cell.price}\nАренда: 25 / 50 / 100 / 200 за 1–4 вокзала\n")
            CellType.UTILITY -> sb.append("Цена ${cell.price}\nАренда: 4× или 10× сумма кубиков\n")
            CellType.TAX -> sb.append("Заплатите ${cell.tax}\n")
            CellType.CHANCE, CellType.CHEST -> sb.append("Возьмите карту\n")
            CellType.JAIL -> sb.append("Просто посещение — или отсидка\n")
            CellType.GO -> sb.append("Проход даёт ${Board.GO_SALARY}\n")
            CellType.FREE_PARKING -> sb.append("Ничего не происходит\n")
            CellType.GO_TO_JAIL -> sb.append("Прямиком в тюрьму\n")
        }
        if (cell.ownable) {
            sb.append(
                when {
                    h.owner == -1 -> "Свободно"
                    else -> "Владелец: ${game.players[h.owner].name}" +
                        (if (h.mortgaged) " (заложено)" else "") +
                        (if (h.houses in 1..4) ", домов: ${h.houses}" else if (h.houses == 5) ", отель" else "")
                }
            )
        }
        val b = AlertDialog.Builder(this).setTitle(cell.name).setMessage(sb.toString())
        val me = game.players[0]
        val mine = h.owner == me.id
        val myTurn = game.player.isHuman && (game.phase == Phase.TurnEnd || game.phase == Phase.Roll || game.phase is Phase.RaiseMoney)
        if (mine && myTurn && !pumping) {
            when {
                game.canBuild(index) -> b.setPositiveButton("Построить за ${cell.houseCost}") { _, _ -> game.build(index); refresh(); saveGame() }
                game.canUnmortgage(index) -> b.setPositiveButton("Выкупить за ${game.unmortgageCost(index)}") { _, _ -> game.unmortgage(index); refresh(); saveGame() }
            }
            when {
                game.canSellHouse(index) -> b.setNeutralButton("Продать дом (+${cell.houseCost / 2})") { _, _ -> game.sellHouse(index); refresh(); saveGame() }
                game.canMortgage(index) -> b.setNeutralButton("Заложить (+${cell.mortgageValue})") { _, _ -> game.mortgage(index); refresh(); saveGame() }
            }
        }
        b.setNegativeButton("Закрыть", null).show()
    }

    private fun showManage() {
        val me = game.players[0]
        val mine = game.holdingsOf(me)
        if (mine.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Имущество").setMessage("Пока ничего нет. Покупай, когда встанешь на свободное поле.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = mine.map { h ->
            val c = Board.cell(h.cell)
            val state = when {
                h.mortgaged -> "заложено"
                h.houses == 5 -> "отель"
                h.houses > 0 -> "домов: ${h.houses}"
                else -> "аренда ${game.rentFor(h.cell, 7)}"
            }
            "${c.name} — $state"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Имущество · ${me.money}")
            .setItems(labels) { _, which -> showCell(mine[which].cell) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle("Rentpoly")
            .setItems(arrayOf("Правила", "Сдаться и выйти в меню", "Продолжить")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this).setTitle("Правила")
                        .setMessage(
                            "Бросай кубики, ходи по кругу. Свободное поле можно купить; на чужом платишь аренду. " +
                                "Собери все поля одного цвета — аренда удваивается и можно строить дома (до 4) и отель.\n\n" +
                                "Дубль — ходишь ещё раз; три дубля подряд — тюрьма. В тюрьме: заплати 50, используй карту или выбрось дубль (три попытки).\n\n" +
                                "Проход через Старт даёт 200. Нечем платить — продавай дома и закладывай поля (тап по своему полю). " +
                                "Кто остался один — победил."
                        ).setPositiveButton("OK", null).show()
                    1 -> {
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SAVE).apply()
                        finish()
                    }
                }
            }
            .show()
    }
}
