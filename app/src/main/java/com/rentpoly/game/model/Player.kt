package com.rentpoly.game.model

class Player(
    val id: Int,
    val name: String,
    val color: Int,
    val token: String,
    val isHuman: Boolean,
) {
    var money: Int = 1500
    var position: Int = 0
    var inJail: Boolean = false
    var jailTurns: Int = 0
    var jailCards: Int = 0
    var bankrupt: Boolean = false
    /** Consecutive doubles this turn; three in a row is a trip to jail. */
    var doublesInRow: Int = 0

    override fun toString(): String = name
}

/** Ownership record for one ownable cell. */
class Holding(val cell: Int) {
    var owner: Int = -1
    var houses: Int = 0
    var mortgaged: Boolean = false
}
