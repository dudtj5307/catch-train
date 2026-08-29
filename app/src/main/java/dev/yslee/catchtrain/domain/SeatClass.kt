package dev.yslee.catchtrain.domain

/**
 * 좌석 등급. MVP 에서는 일반실/특실까지만 다룬다. (DESIGN.md §18)
 */
enum class SeatClass {
    GENERAL,
    FIRST_CLASS,
    ;

    val label: String
        get() = when (this) {
            GENERAL -> "일반실"
            FIRST_CLASS -> "특실"
        }
}
