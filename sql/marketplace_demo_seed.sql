-- Marketplace demo data. Safe to run repeatedly.
SET NAMES utf8mb4;

SET @seller_id = (
    SELECT id
    FROM ums_user
    WHERE username = 'seller_demo' AND is_deleted = 0
    LIMIT 1
);

INSERT INTO pms_product (
    seller_id, category_id, title, description, price, original_price,
    condition_level, location_text, view_count, favorite_count,
    publish_time, status, created_time, updated_time, is_deleted
)
SELECT @seller_id, 1, 'iPhone 13 128G 星光色 国行',
       '自用手机，电池健康正常，边框有轻微使用痕迹。功能全部正常，配原装数据线，可同城验机。',
       2599.00, 5999.00, 8, '上海·浦东新区', 186, 23,
       NOW() - INTERVAL 18 MINUTE, 1, NOW(), NOW(), 0
WHERE @seller_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM pms_product WHERE title = 'iPhone 13 128G 星光色 国行' AND is_deleted = 0);

INSERT INTO pms_product (
    seller_id, category_id, title, description, price, original_price,
    condition_level, location_text, view_count, favorite_count,
    publish_time, status, created_time, updated_time, is_deleted
)
SELECT @seller_id, 1, '索尼 A6400 微单相机 16-50 套机',
       '快门次数不高，机身无磕碰，屏幕已贴膜。包含原装电池、充电器、肩带和相机包。',
       4380.00, 7299.00, 9, '杭州·西湖区', 92, 17,
       NOW() - INTERVAL 2 HOUR, 1, NOW(), NOW(), 0
WHERE @seller_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM pms_product WHERE title = '索尼 A6400 微单相机 16-50 套机' AND is_deleted = 0);

INSERT INTO pms_product (
    seller_id, category_id, title, description, price, original_price,
    condition_level, location_text, view_count, favorite_count,
    publish_time, status, created_time, updated_time, is_deleted
)
SELECT @seller_id, 2, 'MacBook Air M1 8+256G 深空灰',
       '学习办公使用，键盘和屏幕状态良好，电池循环次数少。已退出账号并恢复系统。',
       3899.00, 7999.00, 8, '南京·鼓楼区', 241, 31,
       NOW() - INTERVAL 5 HOUR, 1, NOW(), NOW(), 0
WHERE @seller_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM pms_product WHERE title = 'MacBook Air M1 8+256G 深空灰' AND is_deleted = 0);

INSERT INTO pms_product (
    seller_id, category_id, title, description, price, original_price,
    condition_level, location_text, view_count, favorite_count,
    publish_time, status, created_time, updated_time, is_deleted
)
SELECT @seller_id, 2, 'RTX 3060 12G 显卡 自用无拆修',
       '正常游戏退役，无拆修无矿，支持现场跑分。包装和防静电袋都在。',
       1399.00, 2999.00, 8, '成都·高新区', 318, 46,
       NOW() - INTERVAL 1 DAY, 1, NOW(), NOW(), 0
WHERE @seller_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM pms_product WHERE title = 'RTX 3060 12G 显卡 自用无拆修' AND is_deleted = 0);

INSERT INTO pms_product (
    seller_id, category_id, title, description, price, original_price,
    condition_level, location_text, view_count, favorite_count,
    publish_time, status, created_time, updated_time, is_deleted
)
SELECT @seller_id, 1, 'Nintendo Switch OLED 白色套装',
       '日版 OLED，屏幕无划痕，摇杆无漂移。含底座、充电器、手柄腕带和收纳包。',
       1599.00, 2599.00, 9, '北京·海淀区', 154, 29,
       NOW() - INTERVAL 2 DAY, 1, NOW(), NOW(), 0
WHERE @seller_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM pms_product WHERE title = 'Nintendo Switch OLED 白色套装' AND is_deleted = 0);

INSERT INTO pms_product (
    seller_id, category_id, title, description, price, original_price,
    condition_level, location_text, view_count, favorite_count,
    publish_time, status, created_time, updated_time, is_deleted
)
SELECT @seller_id, 2, 'Keychron K8 Pro 机械键盘 茶轴',
       '办公室备用键盘，蓝牙和有线连接均正常，键帽已清洁，附拔键器和连接线。',
       399.00, 698.00, 9, '深圳·南山区', 75, 8,
       NOW() - INTERVAL 3 DAY, 1, NOW(), NOW(), 0
WHERE @seller_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM pms_product WHERE title = 'Keychron K8 Pro 机械键盘 茶轴' AND is_deleted = 0);

INSERT INTO pms_product (
    seller_id, category_id, title, description, price, original_price,
    condition_level, location_text, view_count, favorite_count,
    publish_time, status, created_time, updated_time, is_deleted
)
SELECT @seller_id, 1, 'Sony WH-1000XM4 降噪耳机',
       '黑色国行，降噪和触控正常，耳罩有轻微使用痕迹。附原装收纳盒、音频线和充电线。',
       1099.00, 2899.00, 8, '武汉·洪山区', 133, 19,
       NOW() - INTERVAL 4 DAY, 1, NOW(), NOW(), 0
WHERE @seller_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM pms_product WHERE title = 'Sony WH-1000XM4 降噪耳机' AND is_deleted = 0);

INSERT INTO pms_product (
    seller_id, category_id, title, description, price, original_price,
    condition_level, location_text, view_count, favorite_count,
    publish_time, status, created_time, updated_time, is_deleted
)
SELECT @seller_id, 4, '雅马哈 F310 民谣吉他 含琴包',
       '入门练习使用，琴颈状态正常，弦距舒适。附琴包、变调夹和备用琴弦，优先同城自提。',
       599.00, 1199.00, 7, '广州·天河区', 64, 11,
       NOW() - INTERVAL 6 DAY, 1, NOW(), NOW(), 0
WHERE @seller_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM pms_product WHERE title = '雅马哈 F310 民谣吉他 含琴包' AND is_deleted = 0);

INSERT INTO pms_product_image (product_id, image_url, sort, is_main, created_time)
SELECT id, 'https://images.unsplash.com/photo-1592750475338-74b7b21085ab?auto=format&fit=crop&w=900&q=85', 0, 1, NOW()
FROM pms_product p
WHERE p.title = 'iPhone 13 128G 星光色 国行'
  AND NOT EXISTS (SELECT 1 FROM pms_product_image i WHERE i.product_id = p.id AND i.is_main = 1);

INSERT INTO pms_product_image (product_id, image_url, sort, is_main, created_time)
SELECT id, 'https://images.unsplash.com/photo-1502920917128-1aa500764cbd?auto=format&fit=crop&w=900&q=85', 0, 1, NOW()
FROM pms_product p
WHERE p.title = '索尼 A6400 微单相机 16-50 套机'
  AND NOT EXISTS (SELECT 1 FROM pms_product_image i WHERE i.product_id = p.id AND i.is_main = 1);

INSERT INTO pms_product_image (product_id, image_url, sort, is_main, created_time)
SELECT id, 'https://images.unsplash.com/photo-1517336714731-489689fd1ca8?auto=format&fit=crop&w=900&q=85', 0, 1, NOW()
FROM pms_product p
WHERE p.title = 'MacBook Air M1 8+256G 深空灰'
  AND NOT EXISTS (SELECT 1 FROM pms_product_image i WHERE i.product_id = p.id AND i.is_main = 1);

INSERT INTO pms_product_image (product_id, image_url, sort, is_main, created_time)
SELECT id, 'https://images.unsplash.com/photo-1591488320449-011701bb6704?auto=format&fit=crop&w=900&q=85', 0, 1, NOW()
FROM pms_product p
WHERE p.title = 'RTX 3060 12G 显卡 自用无拆修'
  AND NOT EXISTS (SELECT 1 FROM pms_product_image i WHERE i.product_id = p.id AND i.is_main = 1);

INSERT INTO pms_product_image (product_id, image_url, sort, is_main, created_time)
SELECT id, 'https://images.unsplash.com/photo-1578303512597-81e6cc155b3e?auto=format&fit=crop&w=900&q=85', 0, 1, NOW()
FROM pms_product p
WHERE p.title = 'Nintendo Switch OLED 白色套装'
  AND NOT EXISTS (SELECT 1 FROM pms_product_image i WHERE i.product_id = p.id AND i.is_main = 1);

INSERT INTO pms_product_image (product_id, image_url, sort, is_main, created_time)
SELECT id, 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=900&q=85', 0, 1, NOW()
FROM pms_product p
WHERE p.title = 'Keychron K8 Pro 机械键盘 茶轴'
  AND NOT EXISTS (SELECT 1 FROM pms_product_image i WHERE i.product_id = p.id AND i.is_main = 1);

INSERT INTO pms_product_image (product_id, image_url, sort, is_main, created_time)
SELECT id, 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=900&q=85', 0, 1, NOW()
FROM pms_product p
WHERE p.title = 'Sony WH-1000XM4 降噪耳机'
  AND NOT EXISTS (SELECT 1 FROM pms_product_image i WHERE i.product_id = p.id AND i.is_main = 1);

INSERT INTO pms_product_image (product_id, image_url, sort, is_main, created_time)
SELECT id, 'https://images.unsplash.com/photo-1510915361894-db8b60106cb1?auto=format&fit=crop&w=900&q=85', 0, 1, NOW()
FROM pms_product p
WHERE p.title = '雅马哈 F310 民谣吉他 含琴包'
  AND NOT EXISTS (SELECT 1 FROM pms_product_image i WHERE i.product_id = p.id AND i.is_main = 1);
