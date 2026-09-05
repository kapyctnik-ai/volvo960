package com.rentpoly.game.model

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What the current player has to do next. The UI renders exactly one of these. */
sealed class Phase {
    /** Roll the dice. */
    object Roll : Phase()
    /** In jail: pay, use a card, or try for doubles. */
    data class Jail(val canUseCard: Boolean, val mustPay: Boolean) : Phase()
    /** Landed on something unowned. */
    data class Buy(val cell: Int) : Phase()
    /** Owes more than they have; sell and mortgage, then continue. */
    data class RaiseMoney(val needed: Int, val creditor: Int) : Phase()
    /** Free to build, mortgage, trade — then end the turn. */
    object TurnEnd : Phase()
    data class Over(val winner: Int) : Phase()
}

/** Things that happened, in order, for the UI to animate and narrate. */
sealed class GameEvent {
    data class Rolled(val player: Int, val d1: Int, val d2: Int) : GameEvent()
    /** Walk [steps] squares (negative = backwards) from [from]. */
    data class Moved(val player: Int, val from: Int, val to: Int, val steps: Int) : GameEvent()
    /** Straight to [to], no walking — jail. */
    data class Jumped(val player: Int, val to: Int) : GameEvent()
    data class Text(val text: String) : GameEvent()
    data class CardDrawn(val deck: String, val text: String) : GameEvent()
    data class Bought(val player: Int, val cell: Int) : GameEvent()
    data class Paid(val from: Int, val to: Int, val amount: Int, val why: String) : GameEvent()
    /** Money that came from the bank: Start salary, a lucky card. */
    data class Gained(val player: Int, val amount: Int, val why: String) : GameEvent()
    data class Bankrupt(val player: Int) : GameEvent()
    data class Winner(val player: Int) : GameEvent()
}

/**
 * The whole game, rules included. Pure state and logic: no Android in here, so
 * it can be driven by a test as easily as by a screen.
 *
 * Every public action mutates state and appends [GameEvent]s; the UI drains
 * them, animates, and then looks at [phase] to know what to offer next. Bots
 * are driven the same way, one [botStep] per phase, so their turns animate
 * like everyone else's.
 */
class Game(
    val players: List<Player>,
    seed: Long = System.currentTimeMillis(),
) {
    val holdings: Array<Holding> = Array(Board.SIZE) { Holding(it) }
    var current: Int = 0
        private set
    var phase: Phase = Phase.Roll
        private set
    val events = ArrayDeque<GameEvent>()
    val log = ArrayList<String>()
    var lastDice: Pair<Int, Int> = 0 to 0
        private set
    var turnNumber: Int = 1
        private set

    private val rng = Random(seed)
    private val chanceDeck = ArrayDeque(Cards.chance.shuffled(rng))
    private val chestDeck = ArrayDeque(Cards.chest.shuffled(rng))

    /** Set while someone owes rent they could not cover; settled after raising money. */
    private var pendingDebt: Pair<Int, Int>? = null   // creditor, amount
    private var rolledDoubles = false

    /** Used by [GameSave] only: puts a loaded game back where it was. */
    fun restore(current: Int, turn: Int, phase: Phase) {
        this.current = current
        this.turnNumber = turn
        this.phase = phase
        // A debt being raised is re-derived from the phase; the creditor and
        // the full amount are what the phase carries.
        (phase as? Phase.RaiseMoney)?.let { pendingDebt = it.creditor to (players[current].money + it.needed) }
    }

    val player: Player get() = players[current]
    val alive: List<Player> get() = players.filter { !it.bankrupt }

    private fun say(text: String) {
        log += text
        if (log.size > 60) log.removeAt(0)
        events += GameEvent.Text(text)
    }

    // ---------------------------------------------------------------- turn flow

    fun roll() {
        if (phase != Phase.Roll) return
        val d1 = rng.nextInt(1, 7)
        val d2 = rng.nextInt(1, 7)
        lastDice = d1 to d2
        rolledDoubles = d1 == d2
        events += GameEvent.Rolled(current, d1, d2)
        val p = player
        if (rolledDoubles) p.doublesInRow++ else p.doublesInRow = 0
        if (p.doublesInRow >= 3) {
            say("${p.name}: три дубля подряд — в тюрьму!")
            sendToJail(p)
            finishLanding()
            return
        }
        move(p, d1 + d2)
        land(p, p.position, fromCard = false)
        finishLanding()
    }

    /** Jail: pay the fine and roll normally. */
    fun jailPay() {
        val jail = phase as? Phase.Jail ?: return
        val p = player
        if (p.money < Board.JAIL_FINE) {
            // Cannot pay: the only option left is to roll.
            jailRoll()
            return
        }
        transfer(p, null, Board.JAIL_FINE, "выход из тюрьмы")
        p.inJail = false
        p.jailTurns = 0
        say("${p.name} платит ${Board.JAIL_FINE} и выходит из тюрьмы.")
        phase = Phase.Roll
        if (jail.mustPay) roll()
    }

    fun jailUseCard() {
        val jail = phase as? Phase.Jail ?: return
        if (!jail.canUseCard) return
        val p = player
        p.jailCards--
        p.inJail = false
        p.jailTurns = 0
        say("${p.name} использует карту освобождения.")
        phase = Phase.Roll
    }

    /** Jail: roll for doubles; the third failed attempt costs the fine. */
    fun jailRoll() {
        if (phase !is Phase.Jail) return
        val p = player
        val d1 = rng.nextInt(1, 7)
        val d2 = rng.nextInt(1, 7)
        lastDice = d1 to d2
        rolledDoubles = false
        events += GameEvent.Rolled(current, d1, d2)
        if (d1 == d2) {
            p.inJail = false
            p.jailTurns = 0
            say("${p.name} выбрасывает дубль и выходит из тюрьмы!")
            move(p, d1 + d2)
            land(p, p.position, fromCard = false)
            finishLanding()
            return
        }
        p.jailTurns++
        if (p.jailTurns >= 3) {
            say("${p.name}: третья попытка не удалась — штраф ${Board.JAIL_FINE}.")
            if (!pay(p, null, Board.JAIL_FINE, "выход из тюрьмы")) return
            p.inJail = false
            p.jailTurns = 0
            move(p, d1 + d2)
            land(p, p.position, fromCard = false)
            finishLanding()
            return
        }
        say("${p.name} остаётся в тюрьме (${p.jailTurns}/3).")
        phase = Phase.TurnEnd
    }

    fun buy() {
        val b = phase as? Phase.Buy ?: return
        val p = player
        val cell = Board.cell(b.cell)
        if (p.money < cell.price) {
            say("${p.name}: не хватает денег на ${cell.name}.")
            phase = Phase.TurnEnd
            afterLanding()
            return
        }
        transfer(p, null, cell.price, "покупка")
        holdings[b.cell].owner = p.id
        events += GameEvent.Bought(p.id, b.cell)
        say("${p.name} покупает ${cell.name} за ${cell.price}.")
        afterLanding()
    }

    fun decline() {
        if (phase !is Phase.Buy) return
        say("${player.name} отказывается от покупки.")
        afterLanding()
    }

    fun endTurn() {
        if (phase != Phase.TurnEnd) return
        nextPlayer()
    }

    /** Called once the player believes they have raised enough. */
    fun raiseMoneyDone() {
        val rm = phase as? Phase.RaiseMoney ?: return
        val p = player
        val debt = pendingDebt
        if (debt == null) {
            phase = Phase.TurnEnd
            return
        }
        val (creditor, amount) = debt
        if (p.money >= amount) {
            pendingDebt = null
            transfer(p, creditor.takeIf { it >= 0 }?.let { players[it] }, amount, "долг")
            afterLanding()
        } else if (liquidValue(p) <= 0) {
            bankrupt(p, creditor)
        } else {
            phase = Phase.RaiseMoney(amount - p.money, rm.creditor)
        }
    }

    // ---------------------------------------------------------------- movement

    private fun move(p: Player, steps: Int) {
        val from = p.position
        var to = (from + steps) % Board.SIZE
        if (to < 0) to += Board.SIZE
        val salary = steps > 0 && to < from
        if (salary) {
            p.money += Board.GO_SALARY
            say("${p.name} проходит Старт: +${Board.GO_SALARY}.")
        }
        p.position = to
        events += GameEvent.Moved(p.id, from, to, steps)
        if (salary) events += GameEvent.Gained(p.id, Board.GO_SALARY, "Старт")
    }

    private fun moveTo(p: Player, cell: Int, collectGo: Boolean) {
        val from = p.position
        val steps = ((cell - from) + Board.SIZE) % Board.SIZE
        val salary = collectGo && cell <= from
        if (salary) {
            p.money += Board.GO_SALARY
            say("${p.name} проходит Старт: +${Board.GO_SALARY}.")
        }
        p.position = cell
        events += GameEvent.Moved(p.id, from, cell, steps)
        if (salary) events += GameEvent.Gained(p.id, Board.GO_SALARY, "Старт")
    }

    private fun sendToJail(p: Player) {
        p.position = Board.JAIL
        p.inJail = true
        p.jailTurns = 0
        p.doublesInRow = 0
        rolledDoubles = false
        events += GameEvent.Jumped(p.id, Board.JAIL)
    }

    // ---------------------------------------------------------------- landing

    private fun land(p: Player, index: Int, fromCard: Boolean) {
        val cell = Board.cell(index)
        when (cell.type) {
            CellType.PROPERTY, CellType.RAILWAY, CellType.UTILITY -> {
                val h = holdings[index]
                when {
                    h.owner == -1 -> {
                        phase = Phase.Buy(index)
                        say("${p.name} на ${cell.name} — свободно, цена ${cell.price}.")
                        return
                    }
                    h.owner == p.id -> say("${p.name} на своей ${cell.name}.")
                    h.mortgaged -> say("${cell.name} заложена — аренды нет.")
                    else -> {
                        val rent = rentFor(index, lastDice.first + lastDice.second)
                        say("${p.name} платит аренду ${rent} игроку ${players[h.owner].name} за ${cell.name}.")
                        pay(p, players[h.owner], rent, "аренда: ${cell.name}")
                    }
                }
            }
            CellType.TAX -> {
                say("${p.name}: ${cell.name} — ${cell.tax}.")
                pay(p, null, cell.tax, cell.name)
            }
            CellType.CHANCE -> drawCard(p, chanceDeck, "Шанс")
            CellType.CHEST -> drawCard(p, chestDeck, "Казна")
            CellType.GO_TO_JAIL -> {
                say("${p.name} отправляется в тюрьму!")
                sendToJail(p)
            }
            CellType.GO -> if (fromCard) Unit else say("${p.name} на Старте.")
            CellType.JAIL -> say("${p.name} просто навещает тюрьму.")
            CellType.FREE_PARKING -> say("${p.name} на бесплатной стоянке.")
        }
    }

    private fun drawCard(p: Player, deck: ArrayDeque<Card>, name: String) {
        val card = deck.removeFirst()
        deck.addLast(card)
        events += GameEvent.CardDrawn(name, card.text)
        say("$name: ${card.text}")
        when (val a = card.action) {
            is CardAction.Money -> if (a.amount >= 0) {
                p.money += a.amount
                events += GameEvent.Gained(p.id, a.amount, name)
            } else {
                pay(p, null, -a.amount, name)
            }
            is CardAction.MoveTo -> {
                moveTo(p, a.cell, a.collectGo)
                land(p, a.cell, fromCard = true)
            }
            is CardAction.MoveBy -> {
                move(p, a.steps)
                land(p, p.position, fromCard = true)
            }
            CardAction.GoToJail -> sendToJail(p)
            CardAction.GetOutOfJail -> p.jailCards++
            is CardAction.FromEachPlayer -> {
                for (other in players) {
                    if (other === p || other.bankrupt) continue
                    if (a.amount > 0) {
                        val give = min(other.money, a.amount)
                        other.money -= give
                        p.money += give
                    } else {
                        val give = min(p.money, -a.amount)
                        p.money -= give
                        other.money += give
                    }
                }
            }
            is CardAction.Repairs -> {
                var bill = 0
                for (h in holdings) if (h.owner == p.id) {
                    bill += if (h.houses == 5) a.perHotel else h.houses * a.perHouse
                }
                if (bill > 0) pay(p, null, bill, "ремонт")
            }
            CardAction.NearestRailway -> {
                var i = p.position
                do { i = (i + 1) % Board.SIZE } while (Board.cell(i).type != CellType.RAILWAY)
                moveTo(p, i, collectGo = true)
                land(p, i, fromCard = true)
            }
        }
    }

    /**
     * What happens after a landing has fully resolved. Doubles give another
     * roll unless the player is in jail or broke.
     */
    private fun finishLanding() {
        if (phase is Phase.Buy || phase is Phase.RaiseMoney || phase is Phase.Over) return
        afterLanding()
    }

    private fun afterLanding() {
        if (phase is Phase.Over) return
        val p = player
        if (p.bankrupt) {
            nextPlayer()
            return
        }
        phase = if (rolledDoubles && !p.inJail) Phase.Roll else Phase.TurnEnd
        if (phase is Phase.Roll) say("${p.name}: дубль — ещё один бросок.")
    }

    private fun nextPlayer() {
        if (alive.size <= 1) {
            val w = alive.firstOrNull()?.id ?: current
            phase = Phase.Over(w)
            events += GameEvent.Winner(w)
            return
        }
        player.doublesInRow = 0
        rolledDoubles = false
        do {
            current = (current + 1) % players.size
        } while (players[current].bankrupt)
        turnNumber++
        val p = player
        phase = if (p.inJail) {
            Phase.Jail(canUseCard = p.jailCards > 0, mustPay = p.jailTurns >= 3)
        } else {
            Phase.Roll
        }
    }

    // ---------------------------------------------------------------- money

    /** Rent due on [index] for a visitor, given the dice total (for utilities). */
    fun rentFor(index: Int, diceTotal: Int): Int {
        val cell = Board.cell(index)
        val h = holdings[index]
        if (h.owner == -1 || h.mortgaged) return 0
        return when (cell.type) {
            CellType.PROPERTY -> {
                val g = cell.group
                if (h.houses > 0) cell.rent[h.houses]
                else if (g != null && ownsGroup(h.owner, g)) cell.rent[0] * 2
                else cell.rent[0]
            }
            CellType.RAILWAY -> {
                val n = holdings.count { it.owner == h.owner && Board.cell(it.cell).type == CellType.RAILWAY }
                25 * (1 shl (n - 1))
            }
            CellType.UTILITY -> {
                val n = holdings.count { it.owner == h.owner && Board.cell(it.cell).type == CellType.UTILITY }
                diceTotal * if (n >= 2) 10 else 4
            }
            else -> 0
        }
    }

    fun ownsGroup(owner: Int, group: Group): Boolean =
        Board.groupCells(group).all { holdings[it.index].owner == owner }

    private fun transfer(from: Player, to: Player?, amount: Int, why: String) {
        from.money -= amount
        if (to != null) to.money += amount
        events += GameEvent.Paid(from.id, to?.id ?: -1, amount, why)
    }

    /**
     * Pays if possible. Otherwise a human is sent to raise the money and a
     * bot liquidates on the spot; whoever still cannot cover it goes bankrupt.
     * Returns whether the payment completed now.
     */
    private fun pay(from: Player, to: Player?, amount: Int, why: String): Boolean {
        if (from.money >= amount) {
            transfer(from, to, amount, why)
            return true
        }
        val creditor = to?.id ?: -1
        if (from.isHuman) {
            pendingDebt = creditor to amount
            phase = Phase.RaiseMoney(amount - from.money, creditor)
            say("${from.name}: нужно найти ещё ${amount - from.money}.")
            return false
        }
        autoLiquidate(from, amount - from.money)
        if (from.money >= amount) {
            transfer(from, to, amount, why)
            return true
        }
        bankrupt(from, creditor)
        return false
    }

    /** Everything a player could still turn into cash. */
    fun liquidValue(p: Player): Int {
        var v = 0
        for (h in holdings) if (h.owner == p.id) {
            val cell = Board.cell(h.cell)
            v += h.houses * cell.houseCost / 2
            if (!h.mortgaged) v += cell.mortgageValue
        }
        return v
    }

    private fun bankrupt(p: Player, creditor: Int) {
        // Houses go back to the bank for half; what is left goes to whoever
        // was owed, or back to the bank.
        for (h in holdings) if (h.owner == p.id) {
            p.money += h.houses * Board.cell(h.cell).houseCost / 2
            h.houses = 0
            if (creditor >= 0) {
                h.owner = creditor
            } else {
                h.owner = -1
                h.mortgaged = false
            }
        }
        if (creditor >= 0) players[creditor].money += max(0, p.money)
        p.money = 0
        p.bankrupt = true
        pendingDebt = null
        events += GameEvent.Bankrupt(p.id)
        say("${p.name} — банкрот!")
        if (alive.size <= 1) {
            val w = alive.firstOrNull()?.id ?: creditor
            phase = Phase.Over(w)
            events += GameEvent.Winner(w)
        } else if (p.id == current) {
            nextPlayer()
        }
    }

    // ---------------------------------------------------------------- property

    fun canBuild(index: Int, p: Player = player): Boolean {
        val cell = Board.cell(index)
        val h = holdings[index]
        if (cell.type != CellType.PROPERTY || h.owner != p.id || h.houses >= 5 || h.mortgaged) return false
        val g = cell.group ?: return false
        if (!ownsGroup(p.id, g)) return false
        val group = Board.groupCells(g)
        if (group.any { holdings[it.index].mortgaged }) return false
        // Build evenly: nothing may be more than one house ahead of its group.
        val minHouses = group.minOf { holdings[it.index].houses }
        return h.houses <= minHouses && p.money >= cell.houseCost
    }

    fun build(index: Int) {
        if (!canBuild(index)) return
        val cell = Board.cell(index)
        transfer(player, null, cell.houseCost, "стройка")
        holdings[index].houses++
        say("${player.name} строит на ${cell.name} (${holdings[index].houses}).")
    }

    fun canSellHouse(index: Int, p: Player = player): Boolean {
        val cell = Board.cell(index)
        val h = holdings[index]
        if (h.owner != p.id || h.houses == 0) return false
        val g = cell.group ?: return false
        val maxHouses = Board.groupCells(g).maxOf { holdings[it.index].houses }
        return h.houses >= maxHouses
    }

    fun sellHouse(index: Int) {
        if (!canSellHouse(index)) return
        val cell = Board.cell(index)
        holdings[index].houses--
        player.money += cell.houseCost / 2
        say("${player.name} продаёт дом на ${cell.name}.")
    }

    fun canMortgage(index: Int, p: Player = player): Boolean {
        val h = holdings[index]
        if (h.owner != p.id || h.mortgaged) return false
        val cell = Board.cell(index)
        val group = cell.group ?: return true
        return Board.groupCells(group).all { holdings[it.index].houses == 0 }
    }

    fun mortgage(index: Int) {
        if (!canMortgage(index)) return
        val cell = Board.cell(index)
        holdings[index].mortgaged = true
        player.money += cell.mortgageValue
        say("${player.name} закладывает ${cell.name} за ${cell.mortgageValue}.")
    }

    fun unmortgageCost(index: Int): Int = Board.cell(index).mortgageValue * 11 / 10

    fun canUnmortgage(index: Int, p: Player = player): Boolean {
        val h = holdings[index]
        return h.owner == p.id && h.mortgaged && p.money >= unmortgageCost(index)
    }

    fun unmortgage(index: Int) {
        if (!canUnmortgage(index)) return
        transfer(player, null, unmortgageCost(index), "выкуп")
        holdings[index].mortgaged = false
        say("${player.name} выкупает ${Board.cell(index).name}.")
    }

    fun holdingsOf(p: Player): List<Holding> = holdings.filter { it.owner == p.id }

    // ---------------------------------------------------------------- bots

    /** Performs the bot's next action for the current phase. */
    fun botStep() {
        val p = player
        if (p.isHuman) return
        when (val ph = phase) {
            Phase.Roll -> roll()
            is Phase.Jail -> when {
                ph.canUseCard -> jailUseCard()
                ph.mustPay -> jailPay()
                p.jailTurns >= 1 && p.money > 300 -> jailPay()
                else -> jailRoll()
            }
            is Phase.Buy -> {
                val cell = Board.cell(ph.cell)
                val completes = cell.group?.let { g ->
                    Board.groupCells(g).all { it.index == ph.cell || holdings[it.index].owner == p.id }
                } ?: false
                val keep = if (completes) 50 else 150
                if (p.money - cell.price >= keep) buy() else decline()
            }
            is Phase.RaiseMoney -> {
                autoLiquidate(p, ph.needed)
                raiseMoneyDone()
            }
            Phase.TurnEnd -> {
                botImprove(p)
                endTurn()
            }
            is Phase.Over -> Unit
        }
    }

    private fun botImprove(p: Player) {
        // Buy back mortgages first when flush, then build on the group with
        // the most rent per house, keeping a cushion for rent.
        var guard = 0
        while (guard++ < 20) {
            val cushion = 350
            val buyBack = holdings.firstOrNull { it.owner == p.id && it.mortgaged && p.money - unmortgageCost(it.cell) >= cushion + 200 }
            if (buyBack != null) {
                unmortgage(buyBack.cell)
                continue
            }
            val site = holdings
                .filter { canBuild(it.cell, p) && p.money - Board.cell(it.cell).houseCost >= cushion }
                .maxByOrNull { Board.cell(it.cell).rent[min(5, it.houses + 1)] }
            if (site == null) break
            build(site.cell)
        }
    }

    private fun autoLiquidate(p: Player, needed: Int) {
        var shortfall = needed
        // Houses first, most expensive first; then mortgages, cheapest first.
        while (shortfall > 0) {
            val house = holdings.filter { canSellHouse(it.cell, p) }
                .maxByOrNull { Board.cell(it.cell).houseCost } ?: break
            holdings[house.cell].houses--
            val got = Board.cell(house.cell).houseCost / 2
            p.money += got
            shortfall -= got
        }
        while (shortfall > 0) {
            val m = holdings.filter { canMortgage(it.cell, p) }
                .minByOrNull { Board.cell(it.cell).mortgageValue } ?: break
            m.mortgaged = true
            val got = Board.cell(m.cell).mortgageValue
            p.money += got
            shortfall -= got
        }
    }
}
