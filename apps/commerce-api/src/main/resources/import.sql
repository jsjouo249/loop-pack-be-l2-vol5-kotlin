alter table products add constraint fk_products_brand foreign key (brand_id) references brands (id) on delete restrict;
alter table orders add constraint fk_orders_user foreign key (user_id) references users (id) on delete restrict;
alter table order_items add constraint fk_order_items_product foreign key (product_id) references products (id) on delete restrict;

alter table payment_attempts add constraint fk_payment_order foreign key (order_id) references orders (id) on delete restrict;
alter table stock_reservations add constraint fk_reservation_attempt foreign key (order_id) references payment_attempts (order_id) on delete restrict;
alter table stock_reservations add constraint fk_reservation_product foreign key (product_id) references products (id) on delete restrict;
alter table payment_issues add constraint fk_payment_issue foreign key (order_id) references payment_attempts (order_id) on delete restrict;
