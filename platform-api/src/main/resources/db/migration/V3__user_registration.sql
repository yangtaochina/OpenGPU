-- =====================================================================
-- V3: 开放注册 + 管理端用户管理
-- =====================================================================

ALTER TABLE app_user
    ADD COLUMN last_login_at TIMESTAMPTZ;

COMMENT ON COLUMN app_user.last_login_at IS '最近一次登录成功时间，供管理端查看';

-- 用户名唯一性改为「不区分大小写」。
-- 应用层已经用 existsByUsernameIgnoreCase 做前置校验，这里是并发下的最终防线：
-- 两个请求同时注册同名的不同大小写写法时，只有一个能成功。
CREATE UNIQUE INDEX uk_app_user_username_lower ON app_user (lower(username));
