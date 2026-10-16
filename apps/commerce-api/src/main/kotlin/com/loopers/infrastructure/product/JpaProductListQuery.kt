package com.loopers.infrastructure.product

import com.loopers.domain.product.ProductListItem
import com.loopers.domain.product.ProductListQuery
import com.loopers.domain.product.ProductSort
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class JpaProductListQuery(private val jpa: ProductJpaRepository, private val em: EntityManager) : ProductListQuery {
    override fun findPage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<ProductListItem> {
        // Preserve covering-index page selection, then fetch only the selected rows and their display data.
        val ids = jpa.activePageIds(brandId, offset, limit, sort)
        if (ids.isEmpty()) return emptyList()
        val found = em.createQuery(
            "select new com.loopers.domain.product.ProductListItem(" +
                "p.id,p.name,p.price,p.stock,b.id,b.name,p.likeCount,p.createdAt) " +
                "from ProductJpaEntity p join BrandJpaEntity b on b.id=p.brandId " +
                "where p.id in :ids and p.deletedAt is null and b.deletedAt is null",
            ProductListItem::class.java,
        ).setParameter("ids", ids).resultList.associateBy { it.id }
        return ids.map { checkNotNull(found[it]) }
    }

    override fun findVisibleIds(ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        return em.createQuery(
            "select p.id from ProductJpaEntity p join BrandJpaEntity b on b.id=p.brandId " +
                "where p.id in :ids and p.deletedAt is null and b.deletedAt is null",
            java.lang.Long::class.java,
        ).setParameter("ids", ids).resultList.map { it.toLong() }.toSet()
    }
}
