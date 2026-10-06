-- 基础预设免导入（spec: docs/CATEGORY_PRESETS_SPEC_20261002.md 第 5.0 节）：
-- category_collections 增加 baseline_seeded_at 初始化标志。
--
-- 语义：
--   * NULL  = 存量集合，尚未执行过基础包播撒（由一次性回填脚本处理）；
--   * 非 NULL = 已播撒过（或回填时已确认无需播撒），此后绝不重复播撒，
--     用户删光分类也不会被复活。
-- 新集合在创建同事务播撒基础包并写入 NOW(3)；回填脚本对存量空集合播撒后写 NOW(3)，
-- 对存量非空集合只写标志不播撒（已初始化过，删光后不复活）。
--
-- DDL 在 MySQL 隐式提交，不能靠 ROLLBACK 撤销。幂等：INFORMATION_SCHEMA 检查后动态执行。

SET @cc_baseline_col = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `category_collections`
     ADD COLUMN `baseline_seeded_at` DATETIME(3) NULL DEFAULT NULL
     COMMENT ''基础预设播撒时间；NULL=存量未播撒，非空后绝不重复播撒''',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'category_collections' AND COLUMN_NAME = 'baseline_seeded_at');
PREPARE cc_stmt FROM @cc_baseline_col;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;
