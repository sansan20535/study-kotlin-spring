package com.study.db.product.fixture

import com.study.db.product.Product

object ProductFixture {
	fun create(
		name: String = "테스트 상품",
		stock: Int = 10,
	): Product = Product(
		name = name,
		stock = stock,
	)
}
