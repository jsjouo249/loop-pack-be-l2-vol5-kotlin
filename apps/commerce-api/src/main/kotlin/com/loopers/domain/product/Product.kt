package com.loopers.domain.product

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.commerce.DeletableEntity
import com.loopers.domain.commerce.EntityState

class Product private constructor(
    val brandId: Long,
    name: String,
    price: Long,
    stock: Int,
    state: EntityState,
) : DeletableEntity(state) {
    constructor(brandId: Long, name: String, price: Long, stock: Int) : this(brandId, name, price, stock, EntityState())

    var name: String = normalizedName(name)
        private set

    var price: Money = Money(price)
        private set

    var stock: Stock = validStock(stock)
        private set

    init {
        if (brandId <= 0) throw CommerceException(CommerceFailure.INVALID_REQUEST)
    }

    fun changeDetails(name: String, price: Long) {
        assertActive()
        val newName = normalizedName(name)
        val newPrice = Money(price)
        this.name = newName
        this.price = newPrice
    }

    fun adjustStock(finalQuantity: Int) {
        assertActive()
        if (finalQuantity < 0) throw CommerceException(CommerceFailure.INVALID_STOCK)
        stock = stock.adjustTo(finalQuantity)
    }

    fun decreaseStock(quantity: Int) {
        assertActive()
        stock = stock.decrease(quantity)
    }

    fun assertCanDecrease(quantity: Int) {
        assertActive()
        stock.decrease(quantity)
    }

    private fun assertActive() {
        if (deletedAt != null) throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
    }

    companion object {
        fun reconstitute(brandId: Long, name: String, price: Long, stock: Int, state: EntityState): Product =
            Product(brandId, name, price, stock, state)

        private fun validStock(value: Int): Stock {
            if (value < 0) throw CommerceException(CommerceFailure.INVALID_STOCK)
            return Stock(value)
        }

        private fun normalizedName(value: String): String {
            val normalized = value.trim()
            if (normalized.isEmpty() || normalized.length > 255) throw CommerceException(CommerceFailure.INVALID_NAME)
            return normalized
        }
    }
}
