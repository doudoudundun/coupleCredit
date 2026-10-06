-- 基础预设免导入（spec: docs/CATEGORY_PRESETS_SPEC_20261002.md 第 5.0 节，三模块统一收口）：
-- housework_spaces 增加 baseline_seeded_at 初始化标志。
--
-- 语义（与迁移 032 的 category_collections.baseline_seeded_at 完全一致）：
--   * NULL  = 存量空间，尚未执行过基础包播撒（由 scripts/dev/seed-baseline-housework.js 一次性回填）；
--   * 非 NULL = 已播撒过（或回填时已确认无需播撒），此后绝不重复播撒，
--     用户删光分类/模板也不会被复活。
-- 新空间在创建同事务播撒基础包并随 INSERT 写 NOW(3)；closed（归档周期）空间只读，
-- 回填对它只写标志、绝不播撒。
--
-- DDL 在 MySQL 隐式提交，不能靠 ROLLBACK 撤销。幂等：INFORMATION_SCHEMA 检查后动态执行。

SET @hw_baseline_col = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `housework_spaces`
     ADD COLUMN `baseline_seeded_at` DATETIME(3) NULL DEFAULT NULL
     COMMENT ''基础预设播撒时间；NULL=存量未播撒，非空后绝不重复播撒''',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'housework_spaces' AND COLUMN_NAME = 'baseline_seeded_at');
PREPARE hw_stmt FROM @hw_baseline_col;
EXECUTE hw_stmt;
DEALLOCATE PREPARE hw_stmt;
