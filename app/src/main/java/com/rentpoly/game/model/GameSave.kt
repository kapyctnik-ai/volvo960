package com.rentpoly.game.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises a game to JSON and back, so closing the app mid-game costs
 * nothing. Only durable state is saved — the event queue and the animation
 * are not state, they are what is happening right now.
 */
object GameSave {
    fun toJson(g: Game): String {
        val root = JSONObject()
        root.put("current", g.current)
        root.put("turn", g.turnNumber)
        root.put("phase", phaseTag(g.phase))
        (g.phase as? Phase.Buy)?.let { root.put("phaseCell", it.cell) }
        (g.phase as? Phase.RaiseMoney)?.let {
            root.put("phaseNeeded", it.needed)
            root.put("phaseCreditor", it.creditor)
        }
        val players = JSONArray()
        for (p in g.players) {
            players.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("color", p.color); put("token", p.token)
                put("human", p.isHuman); put("money", p.money); put("pos", p.position)
                put("jail", p.inJail); put("jailTurns", p.jailTurns); put("jailCards", p.jailCards)
                put("bankrupt", p.bankrupt); put("doubles", p.doublesInRow)
            })
        }
        root.put("players", players)
        val holdings = JSONArray()
        for (h in g.holdings) {
            holdings.put(JSONObject().apply {
                put("cell", h.cell); put("owner", h.owner); put("houses", h.houses); put("mortgaged", h.mortgaged)
            })
        }
        root.put("holdings", holdings)
        root.put("log", JSONArray(g.log))
        return root.toString()
    }

    private fun phaseTag(p: Phase): String = when (p) {
        Phase.Roll -> "roll"
        is Phase.Jail -> "jail"
        is Phase.Buy -> "buy"
        is Phase.RaiseMoney -> "raise"
        Phase.TurnEnd -> "end"
        is Phase.Over -> "over"
    }

    fun fromJson(text: String): Game? = runCatching {
        val root = JSONObject(text)
        val pj = root.getJSONArray("players")
        val players = (0 until pj.length()).map { i ->
            val o = pj.getJSONObject(i)
            Player(o.getInt("id"), o.getString("name"), o.getInt("color"), o.getString("token"), o.getBoolean("human")).apply {
                money = o.getInt("money"); position = o.getInt("pos"); inJail = o.getBoolean("jail")
                jailTurns = o.getInt("jailTurns"); jailCards = o.getInt("jailCards")
                bankrupt = o.getBoolean("bankrupt"); doublesInRow = o.getInt("doubles")
            }
        }
        val game = Game(players)
        val hj = root.getJSONArray("holdings")
        for (i in 0 until hj.length()) {
            val o = hj.getJSONObject(i)
            val h = game.holdings[o.getInt("cell")]
            h.owner = o.getInt("owner"); h.houses = o.getInt("houses"); h.mortgaged = o.getBoolean("mortgaged")
        }
        val lj = root.optJSONArray("log")
        if (lj != null) for (i in 0 until lj.length()) game.log += lj.getString(i)
        val phase = when (root.getString("phase")) {
            "jail" -> {
                val p = players[root.getInt("current")]
                Phase.Jail(canUseCard = p.jailCards > 0, mustPay = p.jailTurns >= 3)
            }
            "buy" -> Phase.Buy(root.getInt("phaseCell"))
            "raise" -> Phase.RaiseMoney(root.getInt("phaseNeeded"), root.getInt("phaseCreditor"))
            "end" -> Phase.TurnEnd
            "over" -> null
            else -> Phase.Roll
        } ?: return null
        game.restore(root.getInt("current"), root.getInt("turn"), phase)
        game
    }.getOrNull()
}
