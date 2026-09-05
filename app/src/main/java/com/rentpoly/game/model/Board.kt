package com.rentpoly.game.model

/** Colour groups, in board order. */
enum class Group(val color: Int, val title: String) {
    BROWN(0xFF8B4513.toInt(), "Коричневая"),
    LIGHT_BLUE(0xFF87CEEB.toInt(), "Голубая"),
    PINK(0xFFD63384.toInt(), "Розовая"),
    ORANGE(0xFFF28C28.toInt(), "Оранжевая"),
    RED(0xFFD62828.toInt(), "Красная"),
    YELLOW(0xFFF2C14E.toInt(), "Жёлтая"),
    GREEN(0xFF2E8B57.toInt(), "Зелёная"),
    BLUE(0xFF1F4E9C.toInt(), "Синяя"),
    RAILWAY(0xFF444444.toInt(), "Вокзалы"),
    UTILITY(0xFF7A7A7A.toInt(), "Коммунальные"),
}

enum class CellType { GO, PROPERTY, RAILWAY, UTILITY, TAX, CHANCE, CHEST, JAIL, FREE_PARKING, GO_TO_JAIL }

/**
 * One square of the board. Rent for properties is indexed by house count
 * (0..4) and 5 for a hotel.
 */
class Cell(
    val index: Int,
    val name: String,
    val type: CellType,
    val group: Group? = null,
    val price: Int = 0,
    val rent: IntArray = IntArray(0),
    val houseCost: Int = 0,
    val tax: Int = 0,
    /** Short glyph for the board. */
    val icon: String = "",
) {
    val ownable: Boolean get() = type == CellType.PROPERTY || type == CellType.RAILWAY || type == CellType.UTILITY
    val mortgageValue: Int get() = price / 2
}

/** The classic Russian-language layout: Moscow streets, four stations, two utilities. */
object Board {
    const val SIZE = 40
    const val GO = 0
    const val JAIL = 10
    const val GO_TO_JAIL = 30
    const val GO_SALARY = 200
    const val JAIL_FINE = 50

    private fun p(i: Int, name: String, g: Group, price: Int, house: Int, vararg rent: Int) =
        Cell(i, name, CellType.PROPERTY, g, price, rent, house)

    val cells: List<Cell> = listOf(
        Cell(0, "Старт", CellType.GO, icon = "➡"),
        p(1, "Житная ул.", Group.BROWN, 60, 50, 2, 10, 30, 90, 160, 250),
        Cell(2, "Казна", CellType.CHEST, icon = "💰"),
        p(3, "Нагатинская ул.", Group.BROWN, 60, 50, 4, 20, 60, 180, 320, 450),
        Cell(4, "Подоходный налог", CellType.TAX, tax = 200, icon = "💸"),
        Cell(5, "Рижская ж/д", CellType.RAILWAY, Group.RAILWAY, 200, icon = "🚂"),
        p(6, "Варшавское ш.", Group.LIGHT_BLUE, 100, 50, 6, 30, 90, 270, 400, 550),
        Cell(7, "Шанс", CellType.CHANCE, icon = "❓"),
        p(8, "ул. Огарёва", Group.LIGHT_BLUE, 100, 50, 6, 30, 90, 270, 400, 550),
        p(9, "Первая Парковая", Group.LIGHT_BLUE, 120, 50, 8, 40, 100, 300, 450, 600),
        Cell(10, "Тюрьма", CellType.JAIL, icon = "🔒"),
        p(11, "ул. Полянка", Group.PINK, 140, 100, 10, 50, 150, 450, 625, 750),
        Cell(12, "Электростанция", CellType.UTILITY, Group.UTILITY, 150, icon = "⚡"),
        p(13, "Сретенка", Group.PINK, 140, 100, 10, 50, 150, 450, 625, 750),
        p(14, "Ростовская наб.", Group.PINK, 160, 100, 12, 60, 180, 500, 700, 900),
        Cell(15, "Курская ж/д", CellType.RAILWAY, Group.RAILWAY, 200, icon = "🚂"),
        p(16, "Рязанский пр.", Group.ORANGE, 180, 100, 14, 70, 200, 550, 750, 950),
        Cell(17, "Казна", CellType.CHEST, icon = "💰"),
        p(18, "ул. Вавилова", Group.ORANGE, 180, 100, 14, 70, 200, 550, 750, 950),
        p(19, "Рублёвское ш.", Group.ORANGE, 200, 100, 16, 80, 220, 600, 800, 1000),
        Cell(20, "Стоянка", CellType.FREE_PARKING, icon = "🅿"),
        p(21, "Тверская ул.", Group.RED, 220, 150, 18, 90, 250, 700, 875, 1050),
        Cell(22, "Шанс", CellType.CHANCE, icon = "❓"),
        p(23, "Пушкинская ул.", Group.RED, 220, 150, 18, 90, 250, 700, 875, 1050),
        p(24, "пл. Маяковского", Group.RED, 240, 150, 20, 100, 300, 750, 925, 1100),
        Cell(25, "Казанская ж/д", CellType.RAILWAY, Group.RAILWAY, 200, icon = "🚂"),
        p(26, "Грузинский Вал", Group.YELLOW, 260, 150, 22, 110, 330, 800, 975, 1150),
        p(27, "ул. Чайковского", Group.YELLOW, 260, 150, 22, 110, 330, 800, 975, 1150),
        Cell(28, "Водопровод", CellType.UTILITY, Group.UTILITY, 150, icon = "🚰"),
        p(29, "Смоленская пл.", Group.YELLOW, 280, 150, 24, 120, 360, 850, 1025, 1200),
        Cell(30, "В тюрьму", CellType.GO_TO_JAIL, icon = "👮"),
        p(31, "ул. Щусева", Group.GREEN, 300, 200, 26, 130, 390, 900, 1100, 1275),
        p(32, "Гоголевский б-р", Group.GREEN, 300, 200, 26, 130, 390, 900, 1100, 1275),
        Cell(33, "Казна", CellType.CHEST, icon = "💰"),
        p(34, "Кутузовский пр.", Group.GREEN, 320, 200, 28, 150, 450, 1000, 1200, 1400),
        Cell(35, "Ленинградская ж/д", CellType.RAILWAY, Group.RAILWAY, 200, icon = "🚂"),
        Cell(36, "Шанс", CellType.CHANCE, icon = "❓"),
        p(37, "Малая Бронная", Group.BLUE, 350, 200, 35, 175, 500, 1100, 1300, 1500),
        Cell(38, "Налог на роскошь", CellType.TAX, tax = 100, icon = "💎"),
        p(39, "Арбат", Group.BLUE, 400, 200, 50, 200, 600, 1400, 1700, 2000),
    )

    fun cell(i: Int): Cell = cells[i]

    fun groupCells(group: Group): List<Cell> = cells.filter { it.group == group }
}
