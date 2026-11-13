package com.loopers.infrastructure.coupon

import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.CouponCampaignRepository
import com.loopers.domain.coupon.IssuedCoupon
import com.loopers.domain.coupon.IssuedCouponRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Check
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Entity
@Table(name = "coupon_campaigns")
@Check(constraints = "id > 0 and issuance_limit > 0 and issued_count >= 0 and issued_count <= issuance_limit")
class CouponCampaignJpaEntity(
    @Column(nullable = false)
    val name: String,
    @Column(name = "benefit_description", nullable = false)
    val benefitDescription: String,
    @Column(name = "issuance_limit", nullable = false)
    val issuanceLimit: Long,
) {
    @Id
    var id: Long = 0

    @Column(name = "issued_count", nullable = false)
    var issuedCount: Long = 0

    @Column(name = "issuance_enabled", nullable = false)
    var issuanceEnabled: Boolean = true

    fun domain() = CouponCampaign(name, benefitDescription, issuanceLimit, id, issuedCount, issuanceEnabled)
}

@Entity
@Table(
    name = "issued_coupons",
    uniqueConstraints = [UniqueConstraint(name = "uk_issued_coupon_user_campaign", columnNames = ["user_id", "campaign_id"])],
    indexes = [Index(name = "ix_issued_coupon_user_id", columnList = "user_id,id")],
)
@Check(constraints = "id > 0 and campaign_id > 0 and user_id > 0")
class IssuedCouponJpaEntity(
    @Column(name = "campaign_id", nullable = false, updatable = false)
    val campaignId: Long,
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,
    @Column(name = "benefit_description", nullable = false, updatable = false)
    val benefitDescription: String,
    @Column(name = "issued_at", nullable = false, updatable = false)
    val issuedAt: Instant,
) {
    @Id
    var id: Long = 0

    fun domain() = IssuedCoupon(campaignId, userId, benefitDescription, issuedAt, id)
}

@Repository
@Transactional(readOnly = true)
class JpaCouponCampaignRepository(private val em: EntityManager) : CouponCampaignRepository {
    override fun find(id: Long): CouponCampaign? = em.find(CouponCampaignJpaEntity::class.java, id)?.domain()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun findForUpdate(id: Long): CouponCampaign? =
        em.find(CouponCampaignJpaEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE)?.domain()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun insert(campaign: CouponCampaign): CouponCampaign {
        require(campaign.id > 0L && campaign.issuedCount == 0L)
        val entity = CouponCampaignJpaEntity(campaign.name, campaign.benefitDescription, campaign.issuanceLimit)
        entity.id = campaign.id
        entity.issuanceEnabled = campaign.issuanceEnabled

        em.persist(entity)
        em.flush()

        return entity.domain()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun save(campaign: CouponCampaign) {
        val entity = checkNotNull(em.find(CouponCampaignJpaEntity::class.java, campaign.id))
        entity.issuedCount = campaign.issuedCount
        entity.issuanceEnabled = campaign.issuanceEnabled

        em.flush()
    }
}

@Repository
@Transactional(readOnly = true)
class JpaIssuedCouponRepository(private val em: EntityManager) : IssuedCouponRepository {
    override fun findById(id: Long): IssuedCoupon? = em.find(IssuedCouponJpaEntity::class.java, id)?.domain()

    override fun find(userId: Long, campaignId: Long): IssuedCoupon? = em.createQuery(
        "select c from IssuedCouponJpaEntity c where c.userId = :user and c.campaignId = :campaign",
        IssuedCouponJpaEntity::class.java,
    ).setParameter("user", userId).setParameter("campaign", campaignId).resultList.firstOrNull()?.domain()

    override fun findByUser(userId: Long): List<IssuedCoupon> = em.createQuery(
        "select c from IssuedCouponJpaEntity c where c.userId = :user order by c.issuedAt desc, c.id desc",
        IssuedCouponJpaEntity::class.java,
    ).setParameter("user", userId).resultList.map { it.domain() }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun insert(coupon: IssuedCoupon): IssuedCoupon {
        require(coupon.id > 0L)
        val entity = IssuedCouponJpaEntity(coupon.campaignId, coupon.userId, coupon.benefitDescription, coupon.issuedAt)

        entity.id = coupon.id

        em.persist(entity)
        em.flush()
        em.refresh(entity)

        return entity.domain()
    }
}
