package com.study.db.product

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version

@Entity
@Table(name = "products")
class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    var name: String,

    var stock: Int,

    @Version
    var version: Long = 0,
) {
    fun decreaseStock(quantity: Int) {
        require(quantity > 0) { "차감 수량은 0보다 커야 합니다." }
        require(stock >= quantity) { "재고가 부족합니다." }
        stock -= quantity
    }
}
