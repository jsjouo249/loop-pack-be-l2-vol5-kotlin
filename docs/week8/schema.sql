-- 기존 테이블을 변경·초기화하지 않는 8주차 신규 스키마 초안이다. 자동 실행하지 않는다.
create table coupon_campaigns (
    id bigint not null primary key,
    name varchar(255) not null,
    benefit_description varchar(255) not null,
    issuance_enabled boolean not null default true,
    issuance_limit bigint not null,
    issued_count bigint not null default 0,
    constraint ck_coupon_campaign_quota check (
        id > 0 and issuance_limit > 0 and issued_count >= 0 and issued_count <= issuance_limit
    )
);

create table issued_coupons (
    id bigint not null primary key,
    campaign_id bigint not null,
    user_id bigint not null,
    benefit_description varchar(255) not null,
    issued_at datetime(6) not null,
    constraint uk_issued_coupon_user_campaign unique (user_id, campaign_id),
    constraint ck_issued_coupon_identifiers check (id > 0 and campaign_id > 0 and user_id > 0),
    index ix_issued_coupon_user_id (user_id, id)
);
