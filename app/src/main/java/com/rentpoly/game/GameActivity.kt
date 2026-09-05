package com.rentpoly.game

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
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
import com.rentpoly.game.ui.PlayerCardView
import com.rentpoly.game.ui.Sfx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One screen: the board on top, the players and the controls underneath.
 *
 * The engine produces events; this class plays them — dice tumble, tokens
 * hop, money floats up off the board, cards flip out of the deck — with a
 * sound and a nudge of haptics for each. Only when the queue is empty does it
 * look at the phase and decide which buttons to show. Bots take their turns
 * through the same pump, one step at a time, so their moves play like yours.
 */
class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOTS = "bots"
        const val EXTRA_CONTINUE = "continue"
        private const val PREFS = "rentpoly"
        private const val KEY_SAVE = "save"
        private const val KEY_SOUND = "sound"

        private val GREEN = Color.parseColor("#2ECC71")
        private val RED = Color.parseColor("#FF5252")
        private val GOLD = Color.parseColor("#F1C40F")
        private val CHANCE = Color.parseColor("#E67E22")
        private val CHEST = Color.parseColor("#2980B9")

        fun hasSave(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SAVE)
    }

    private lateinit var binding: ActivityGameBinding
    private lateinit var game: Game
    private lateinit var sfx: Sfx
    private val cards = ArrayList<PlayerCardView>()
    private var pumping = false
    /** Whose Roll phase we last chimed for, so the chime fires once per turn. */
    private var chimedTurn = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sfx = Sfx(this)
        sfx.enabled = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOUND, true)

        val restored = if (intent.getBooleanExtra(EXTRA_CONTINUE, false)) loadGame() else null
        game = restored ?: newGame(intent.getIntExtra(EXTRA_BOTS, 1))
        binding.board.game = game
        binding.board.onCellTapped = { showCell(it) }
        binding.board.onHop = { sfx.play(Sfx.Sound.HOP, 0.7f, 0.95f + (Math.random() * 0.1f).toFloat()) }
        binding.board.syncTokens()
        buildPlayerStrip()

        binding.buttonMain.setOnClickListener { sfx.tap(12); onMain() }
        binding.buttonSecondary.setOnClickListener { sfx.tap(12); onSecondary() }
        binding.buttonManage.setOnClickListener { showManage() }
        binding.buttonMenu.setOnClickListener { showMenu() }

        refresh(animateMoney = false)
        pump()
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
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
        refresh()
        lifecycleScope.launch {
            try {
                while (true) {
                    while (game.events.isNotEmpty()) play(game.events.removeFirst())
                    refresh()
                    saveGame()
                    val p = game.player
                    if (p.isHuman || game.phase is Phase.Over) break
                    delay(if (game.phase == Phase.TurnEnd) 400 else 750)
                    game.botStep()
                }
            } finally {
                pumping = false
                refresh()
                chimeIfMyTurn()
            }
        }
    }

    private fun chimeIfMyTurn() {
        if (game.phase is Phase.Over) return
        val p = game.player
        if (!p.isHuman) return
        val key = game.turnNumber * 8 + game.current
        if (key == chimedTurn) return
        chimedTurn = key
        sfx.play(Sfx.Sound.TURN, 0.8f)
        sfx.tap(25)
    }

    private suspend fun play(e: GameEvent) {
        when (e) {
            is GameEvent.Rolled -> {
                binding.board.centreText = "${game.players[e.player].name}: ${e.d1} + ${e.d2}"
                sfx.play(Sfx.Sound.DICE)
                sfx.tap(30)
                await { done -> binding.board.animateDice(e.d1, e.d2, done) }
                if (e.d1 == e.d2) {
                    binding.board.floatAtPlayer(e.player, "Дубль!", GOLD)
                    sfx.play(Sfx.Sound.CARD, 0.6f, 1.3f)
                }
            }
            is GameEvent.Moved -> await { done -> binding.board.animateWalk(e.player, e.from, e.steps, done) }
            is GameEvent.Jumped -> {
                sfx.play(Sfx.Sound.JAIL)
                sfx.tap(60)
                binding.board.placeToken(e.player, e.to)
                binding.board.glow(e.to, RED)
                binding.board.floatAt(e.to, "В тюрьму!", RED)
                delay(500)
            }
            is GameEvent.Text -> {
                binding.board.centreText = e.text
                binding.textLog.text = game.log.takeLast(3).joinToString("\n")
                refreshPlayers()
            }
            is GameEvent.CardDrawn -> {
                sfx.play(Sfx.Sound.CARD)
                sfx.tap(20)
                val color = if (e.deck.contains("Шанс")) CHANCE else CHEST
                await { done -> binding.board.showCard(e.deck, e.text, color, done) }
                refreshPlayers()
            }
            is GameEvent.Bought -> {
                sfx.play(Sfx.Sound.BUY)
                sfx.tap(20)
                binding.board.glow(e.cell, game.players[e.player].color)
                binding.board.floatAt(e.cell, "−${Board.cell(e.cell).price}", RED)
                refreshPlayers()
                binding.board.invalidate()
                delay(350)
            }
            is GameEvent.Paid -> {
                sfx.play(Sfx.Sound.PAY)
                binding.board.floatAtPlayer(e.from, "−${e.amount}", RED)
                if (e.to >= 0) {
                    lifecycleScope.launch {
                        delay(260)
                        sfx.play(Sfx.Sound.CASH, 0.8f)
                        binding.board.floatAtPlayer(e.to, "+${e.amount}", GREEN)
                    }
                }
                refreshPlayers()
                binding.board.invalidate()
                delay(450)
            }
            is GameEvent.Gained -> {
                sfx.play(Sfx.Sound.CASH)
                binding.board.floatAtPlayer(e.player, "+${e.amount}", GREEN)
                refreshPlayers()
                delay(350)
            }
            is GameEvent.Bankrupt -> {
                sfx.play(Sfx.Sound.LOSE, 0.9f)
                sfx.tap(80)
                refreshPlayers()
                binding.board.syncTokens()
                binding.board.invalidate()
                await { done ->
                    binding.board.showCard("Банкрот", "${game.players[e.player].name} выбывает из игры.", Color.parseColor("#7F8C8D"), done)
                }
            }
            is GameEvent.Winner -> {
                refreshPlayers()
                val w = game.players[e.player]
                val won = w.isHuman
                sfx.play(if (won) Sfx.Sound.WIN else Sfx.Sound.LOSE)
                sfx.tap(if (won) 120 else 60)
                val text = if (won) "Ты победил! Всё имущество города — твоё." else "${w.name} побеждает. В следующий раз."
                await { done -> binding.board.showCard(if (won) "Победа!" else "Игра окончена", text, if (won) GOLD else RED, done) }
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

    private fun refresh(animateMoney: Boolean = true) {
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
                binding.buttonMain.text = "Заплатить ${Board.JAIL_FINE}"
                binding.buttonSecondary.text = if (ph.canUseCard) "Карта освобождения" else "Бросить на дубль"
                binding.buttonSecondary.visibility = View.VISIBLE
            }
            is Phase.RaiseMoney -> binding.buttonMain.text = "Нужно ещё ${ph.needed} — готово"
            Phase.TurnEnd -> binding.buttonMain.text = "Закончить ход"
            is Phase.Over -> binding.buttonMain.text = "В меню"
        }
        if (!human && game.phase !is Phase.Over) binding.buttonMain.text = "Ходит ${p.name}…"
        binding.buttonManage.isEnabled = !game.players[0].bankrupt
        binding.textLog.text = game.log.takeLast(3).joinToString("\n")
        refreshPlayers(animateMoney)
        binding.board.invalidate()
    }

    private fun buildPlayerStrip() {
        binding.playerStrip.removeAllViews()
        cards.clear()
        val h = (resources.displayMetrics.density * 64).toInt()
        for (p in game.players) {
            val v = PlayerCardView(this).apply {
                color = p.color
                token = p.token
                name = p.name
                layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply { setMargins(3, 4, 3, 4) }
            }
            binding.playerStrip.addView(v)
            cards += v
        }
        refreshPlayers(animateMoney = false)
    }

    private fun refreshPlayers(animateMoney: Boolean = true) {
        for ((i, p) in game.players.withIndex()) {
            val v = cards.getOrNull(i) ?: continue
            v.properties = game.holdingsOf(p).size
            v.inJail = p.inJail
            v.bankrupt = p.bankrupt
            v.current = i == game.current && !p.bankrupt
            v.setMoney(p.money, animateMoney)
            v.invalidate()
        }
    }

    // ------------------------------------------------------------ dialogs

    private fun afterManage(cell: Int, sound: Sfx.Sound) {
        sfx.play(sound)
        sfx.tap(15)
        binding.board.glow(cell, game.players[0].color)
        refresh()
        saveGame()
    }

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
                game.canBuild(index) -> b.setPositiveButton("Построить за ${cell.houseCost}") { _, _ -> game.build(index); afterManage(index, Sfx.Sound.BUILD) }
                game.canUnmortgage(index) -> b.setPositiveButton("Выкупить за ${game.unmortgageCost(index)}") { _, _ -> game.unmortgage(index); afterManage(index, Sfx.Sound.BUY) }
            }
            when {
                game.canSellHouse(index) -> b.setNeutralButton("Продать дом (+${cell.houseCost / 2})") { _, _ -> game.sellHouse(index); afterManage(index, Sfx.Sound.CASH) }
                game.canMortgage(index) -> b.setNeutralButton("Заложить (+${cell.mortgageValue})") { _, _ -> game.mortgage(index); afterManage(index, Sfx.Sound.PAY) }
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
        val soundLabel = if (sfx.enabled) "Звук: вкл" else "Звук: выкл"
        AlertDialog.Builder(this)
            .setTitle("Rentpoly")
            .setItems(arrayOf("Правила", soundLabel, "Сдаться и выйти в меню", "Продолжить")) { _, which ->
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
                        sfx.enabled = !sfx.enabled
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SOUND, sfx.enabled).apply()
                        if (sfx.enabled) sfx.play(Sfx.Sound.TURN, 0.8f)
                    }
                    2 -> {
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SAVE).apply()
                        finish()
                    }
                }
            }
            .show()
    }
}
