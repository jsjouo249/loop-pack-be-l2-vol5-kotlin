package com.loopers.application.transaction

import com.loopers.domain.product.ProductRepository
import com.loopers.infrastructure.product.ProductJpaEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class PartialStorageTest : CommerceTransactionTestSupport() {
    @Autowired private lateinit var repository: ProductRepository

    @Test
    fun `details update refreshes managed entity and preserves a newer stock value`() {
        val product = product(1000, 5)
        tx {
            val stale = repository.findActive(product)!!
            jdbc.update("update products set stock=3 where id=?", product)
            stale.changeDetails("변경", 2000)
            val saved = repository.updateDetails(stale)!!
            assertThat(saved.stock.remaining).isEqualTo(3)
            assertThat(em.find(ProductJpaEntity::class.java, product).stock).isEqualTo(3)
            em.flush()
            em.clear()
            assertThat(repository.findActive(product)!!.stock.remaining).isEqualTo(3)
        }
        assertThat(stock(product)).isEqualTo(3)
    }

    @Test
    fun `stock and deletion updates preserve newer unrelated fields`() {
        val product = product(1000, 5)
        tx {
            val stale = repository.findActive(product)!!
            jdbc.update("update products set name='현재', price=3000 where id=?", product)
            stale.adjustStock(9)
            val saved = repository.setStock(stale)!!
            assertThat(saved.name).isEqualTo("현재")
            assertThat(saved.price.amount).isEqualTo(3000)
            stale.delete()
            assertThat(repository.markDeleted(stale)).isTrue()
            em.flush()
            em.clear()
            val current = repository.findAny(product)!!
            assertThat(current.name).isEqualTo("현재")
            assertThat(current.price.amount).isEqualTo(3000)
            assertThat(current.stock.remaining).isEqualTo(9)
            assertThat(current.deletedAt).isNotNull()
        }
    }

    @Test
    fun `same values match active row without changing timestamps`() {
        val brand = brands.create("브랜드")
        val product = products.create(brand.id, "상품", 1000, 5)
        val persisted = products.get(product.id)
        assertThat(products.update(product.id, "상품", 1000).updatedAt).isEqualTo(persisted.updatedAt)
        assertThat(products.adjustStock(product.id, 5).updatedAt).isEqualTo(persisted.updatedAt)
        val savedBrand = brands.get(brand.id)
        assertThat(brands.update(brand.id, "브랜드").updatedAt).isEqualTo(savedBrand.updatedAt)
        assertThat(products.update(product.id, "상품2", 1000).updatedAt).isAfter(persisted.updatedAt)
        assertThat(products.get(product.id).createdAt).isEqualTo(persisted.createdAt)
    }

    @Test
    fun `stale commands cannot overwrite a deleted row`() {
        val product = product(1000, 5)
        tx {
            val stale = repository.findActive(product)!!
            jdbc.update("update products set deleted_at=current_timestamp(6) where id=?", product)
            stale.changeDetails("복원", 2000)
            stale.adjustStock(9)
            assertThat(repository.updateDetails(stale)).isNull()
            assertThat(repository.setStock(stale)).isNull()
            stale.delete()
            assertThat(repository.markDeleted(stale)).isFalse()
            em.flush()
            em.clear()
            assertThat(repository.findAny(product)!!.name).isEqualTo("상품")
            assertThat(repository.findAny(product)!!.stock.remaining).isEqualTo(5)
        }
    }

    @Test
    fun `no op details update returns stock committed after its repeatable read snapshot`() {
        val product = product(1000, 5)
        tx {
            val stale = repository.findActive(product)!!
            dataSource.connection.use { other ->
                other.prepareStatement("update products set stock=3 where id=?").use {
                    it.setLong(1, product)
                    assertThat(it.executeUpdate()).isEqualTo(1)
                }
            }
            stale.changeDetails("상품", 1000)
            val saved = repository.updateDetails(stale)!!
            assertThat(saved.stock.remaining).isEqualTo(3)
            assertThat(em.find(ProductJpaEntity::class.java, product).stock).isEqualTo(3)
            em.flush()
            em.clear()
        }
        assertThat(stock(product)).isEqualTo(3)
    }
}
