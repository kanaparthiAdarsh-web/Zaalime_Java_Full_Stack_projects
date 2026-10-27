INSERT INTO products (sku, name, description, price) VALUES
    ('PS5-SLIM',   'PlayStation 5 Slim',      'Flash sale - limited units',        449.99),
    ('IPHONE-16',  'iPhone 16 128GB',         'Launch day stock',                  799.00),
    ('RTX-5080',   'GeForce RTX 5080',        'Founders Edition',                  999.00),
    ('AIRPODS-P2', 'AirPods Pro 2',           'USB-C case',                        249.00),
    ('KINDLE-PW',  'Kindle Paperwhite',       '16GB, ad-supported',                159.99),
    ('SWITCH-OLED','Nintendo Switch OLED',    'White Joy-Con',                     349.99);

INSERT INTO inventory (product_id, quantity)
SELECT id, CASE sku
               WHEN 'PS5-SLIM' THEN 50
               WHEN 'RTX-5080' THEN 20
               WHEN 'IPHONE-16' THEN 100
               ELSE 200
           END
FROM products;
