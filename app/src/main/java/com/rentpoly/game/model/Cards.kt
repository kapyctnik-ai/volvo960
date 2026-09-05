package com.rentpoly.game.model

/** What a drawn card does. Amounts are positive for gains, negative for payments. */
sealed class CardAction {
    data class Money(val amount: Int) : CardAction()
    data class MoveTo(val cell: Int, val collectGo: Boolean = true) : CardAction()
    data class MoveBy(val steps: Int) : CardAction()
    object GoToJail : CardAction()
    object GetOutOfJail : CardAction()
    /** Every other player pays (or receives, if negative) this much. */
    data class FromEachPlayer(val amount: Int) : CardAction()
    data class Repairs(val perHouse: Int, val perHotel: Int) : CardAction()
    object NearestRailway : CardAction()
}

class Card(val text: String, val action: CardAction)

object Cards {
    val chance: List<Card> = listOf(
        Card("Отправляйтесь на Старт. Получите 200.", CardAction.MoveTo(0)),
        Card("Отправляйтесь на Арбат.", CardAction.MoveTo(39)),
        Card("Отправляйтесь на Смоленскую площадь. Если пройдёте Старт — получите 200.", CardAction.MoveTo(29)),
        Card("Отправляйтесь на Тверскую улицу. Если пройдёте Старт — получите 200.", CardAction.MoveTo(21)),
        Card("Отправляйтесь на Рижскую ж/д. Если пройдёте Старт — получите 200.", CardAction.MoveTo(5)),
        Card("Отправляйтесь на ближайший вокзал.", CardAction.NearestRailway),
        Card("Банк выплачивает вам дивиденды: 50.", CardAction.Money(50)),
        Card("Штраф за превышение скорости: заплатите 15.", CardAction.Money(-15)),
        Card("Вернитесь на три поля назад.", CardAction.MoveBy(-3)),
        Card("Отправляйтесь в тюрьму. Не проходите Старт, не получайте 200.", CardAction.GoToJail),
        Card("Ремонт недвижимости: 25 за каждый дом, 100 за каждый отель.", CardAction.Repairs(25, 100)),
        Card("Освобождение из тюрьмы. Карту можно сохранить.", CardAction.GetOutOfJail),
        Card("Ваши облигации выросли в цене: получите 150.", CardAction.Money(150)),
        Card("Вы избраны председателем правления: заплатите каждому игроку 50.", CardAction.FromEachPlayer(-50)),
    )

    val chest: List<Card> = listOf(
        Card("Ошибка банка в вашу пользу: получите 200.", CardAction.Money(200)),
        Card("Оплата услуг врача: заплатите 50.", CardAction.Money(-50)),
        Card("Возврат налога: получите 20.", CardAction.Money(20)),
        Card("У вас день рождения: каждый игрок дарит вам 10.", CardAction.FromEachPlayer(10)),
        Card("Вы получили наследство: 100.", CardAction.Money(100)),
        Card("Отправляйтесь на Старт. Получите 200.", CardAction.MoveTo(0)),
        Card("Отправляйтесь в тюрьму. Не проходите Старт, не получайте 200.", CardAction.GoToJail),
        Card("Освобождение из тюрьмы. Карту можно сохранить.", CardAction.GetOutOfJail),
        Card("Гонорар за консультацию: получите 25.", CardAction.Money(25)),
        Card("Школьный сбор: заплатите 50.", CardAction.Money(-50)),
        Card("Продажа акций: получите 50.", CardAction.Money(50)),
        Card("Второе место на конкурсе красоты: получите 10.", CardAction.Money(10)),
        Card("Уличный ремонт: 40 за каждый дом, 115 за каждый отель.", CardAction.Repairs(40, 115)),
        Card("Страховая выплата: получите 100.", CardAction.Money(100)),
    )
}
