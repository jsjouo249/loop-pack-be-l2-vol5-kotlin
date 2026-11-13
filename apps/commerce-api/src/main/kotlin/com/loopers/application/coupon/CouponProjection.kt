package com.loopers.application.coupon

import com.loopers.domain.coupon.CouponCampaignRepository
import com.loopers.domain.coupon.CouponIssuedEvent
import com.loopers.domain.coupon.CouponIssueLog
import com.loopers.domain.coupon.IssuedCouponRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicBoolean

@Service
class CouponProjectionTransaction(
    private val campaigns: CouponCampaignRepository,
    private val coupons: IssuedCouponRepository,
) {
    /** 캠페인 잠금 아래 저장된 동일 사실을 재확인하고, projection 수와 쿠폰을 한 TX로 반영한다. */
    @Transactional(timeout = 5, rollbackFor = [Exception::class])
    fun project(event: CouponIssuedEvent) {
        val campaign = campaigns.findForUpdate(event.campaign.id) ?: campaigns.insert(event.campaign)
        check(campaign.name == event.campaign.name && campaign.benefitDescription == event.campaign.benefitDescription)
        check(campaign.issuanceLimit == event.campaign.issuanceLimit)

        val existing = coupons.findById(event.coupon.id)
        if (existing != null) {
            check(existing == event.coupon) { "Coupon projection conflicts with Redis fact" }
            return
        }
        check(
            coupons.find(event.coupon.userId, event.coupon.campaignId) == null,
        ) { "Ownership projection conflicts with Redis fact" }

        campaign.issue()
        campaigns.save(campaign)
        coupons.insert(event.coupon)
    }
}

@Service
class CouponProjectionWorker(
    private val events: CouponIssueLog,
    private val transactions: CouponProjectionTransaction,
    private val admission: CouponAdmissionService,
) {
    private val running = AtomicBoolean()
    private val log = LoggerFactory.getLogger(javaClass)

    /** 고정 로컬 consumer의 pending을 우선 재처리한다. DB commit 이후만 ACK해 응답 유실에도 재반영할 수 있다. */
    fun runOnce(limit: Int = 20): Int {
        require(limit in 1..100)
        check(!TransactionSynchronizationManager.isActualTransactionActive())
        if (!running.compareAndSet(false, true)) return 0

        try {
            val records = try {
                events.readPendingOrNew(limit)
            } catch (failure: Exception) {
                admission.close()
                throw failure
            }
            var completed = 0
            for (record in records) {
                try {
                    transactions.project(record.event)
                } catch (failure: Exception) {
                    log.error("coupon_projection unresolved recordId={}", record.recordId, failure)
                    break
                }

                try {
                    events.acknowledge(record.recordId)
                    completed++
                } catch (failure: Exception) {
                    admission.close()
                    log.error("coupon_projection ack_unknown recordId={}", record.recordId, failure)
                    break
                }
            }

            return completed
        } finally {
            running.set(false)
        }
    }
}
