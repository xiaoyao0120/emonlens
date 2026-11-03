-- EmonLens 生产 MySQL DDL（由 schema-h2.sql 同步生成，勿手改两处——改 H2 后重新生成）
-- 执行：mysql -u<user> -p emonlens < emonlens-mysql.sql

-- 关键：声明客户端会话字符集为 utf8mb4。MySQL 官方的 mysql CLI 默认 latin1，
-- 而本文件是 UTF-8；缺这句会让 docker-entrypoint 加载种子数据时把每个 UTF-8 字节
-- 当成 latin1 字符再转存，导致全新部署里所有中文（工作空间名/通知模板/菜单/角色名）双重编码成乱码。
SET NAMES utf8mb4;

-- EmonLens 控制面/数据面表（H2 MySQL 兼容模式；生产 MySQL 使用 sql/emonlens-mysql.sql，列定义一致）

CREATE TABLE IF NOT EXISTS et_ingest_key (
    id           VARCHAR(36)  PRIMARY KEY,
    key_hash     VARCHAR(64)  NOT NULL,
    key_masked   VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL DEFAULT '',
    workspace_id    VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ingest_key_hash (key_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_span_event (
    id             VARCHAR(36)  PRIMARY KEY,
    workspace_id      VARCHAR(64)  NOT NULL,
    agent_key      VARCHAR(128) NOT NULL,
    agent_version  VARCHAR(64),
    trace_id       VARCHAR(64)  NOT NULL,
    span_id        VARCHAR(32)  NOT NULL,
    parent_span_id VARCHAR(32),
    span_type      VARCHAR(32)  NOT NULL,
    span_name      VARCHAR(256),
    -- 毫秒精度：duration_ms 由服务端按 start/end 重算，秒精度列会把亚秒 span 的耗时截成 0
    start_time     DATETIME(3)  NOT NULL,
    end_time       DATETIME(3),
    duration_ms    BIGINT,
    status         VARCHAR(16)  NOT NULL DEFAULT 'OK',
    error_type     VARCHAR(128),
    model_name     VARCHAR(128),
    tool_name      VARCHAR(128),
    input_tokens   BIGINT       NOT NULL DEFAULT 0,
    output_tokens  BIGINT       NOT NULL DEFAULT 0,
    attributes     TEXT,
    ingest_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_span (workspace_id, trace_id, span_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_trace_summary (
    id           VARCHAR(36)  PRIMARY KEY,
    workspace_id    VARCHAR(64)  NOT NULL,
    agent_key    VARCHAR(128) NOT NULL,
    agent_version VARCHAR(64),
    trace_id     VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    span_count   INT          NOT NULL DEFAULT 0,
    error_count  INT          NOT NULL DEFAULT 0,
    root_span_id VARCHAR(32),
    start_time   DATETIME(3)  NOT NULL,
    end_time     DATETIME(3),
    duration_ms  BIGINT,
    last_span_at DATETIME     NOT NULL,
    completed_at DATETIME,
    UNIQUE KEY uk_trace (workspace_id, trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_event (
    id            VARCHAR(36)  PRIMARY KEY,
    event_type    VARCHAR(32)  NOT NULL,
    workspace_id     VARCHAR(64)  NOT NULL,
    agent_key     VARCHAR(128) NOT NULL,
    payload       TEXT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    claim_owner   VARCHAR(64),
    claim_time    DATETIME,
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME     NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- fan-out 事件的 handler 级幂等账本：重试时跳过已成功的 handler（避免重复 webhook/重复评分）
CREATE TABLE IF NOT EXISTS et_event_handler_delivery (
    id          VARCHAR(36)  PRIMARY KEY,
    event_id    VARCHAR(36)  NOT NULL,
    handler_key VARCHAR(191) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'DONE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_handler (event_id, handler_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_monitor (
    id          VARCHAR(36)  PRIMARY KEY,
    workspace_id   VARCHAR(64)  NOT NULL,
    agent_key   VARCHAR(128) NOT NULL,
    monitor_key VARCHAR(128) NOT NULL,
    kind        VARCHAR(32)  NOT NULL,
    spec_json   TEXT         NOT NULL,
    severity    VARCHAR(16)  NOT NULL DEFAULT 'HIGH',
    dimension   VARCHAR(32)  NOT NULL DEFAULT 'OUTPUT',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    version     INT          NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_monitor (workspace_id, agent_key, monitor_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_finding (
    id             VARCHAR(36)  PRIMARY KEY,
    workspace_id      VARCHAR(64)  NOT NULL,
    agent_key      VARCHAR(128) NOT NULL,
    agent_version  VARCHAR(64),
    trace_id       VARCHAR(64),
    span_id        VARCHAR(32),
    dimension      VARCHAR(32)  NOT NULL,
    type           VARCHAR(64)  NOT NULL,
    severity       VARCHAR(16)  NOT NULL,
    fingerprint    VARCHAR(64)  NOT NULL,
    detector_id    VARCHAR(128) NOT NULL,
    message        VARCHAR(512),
    evidence       TEXT,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
-- uk_finding_occurrence 由文末 S7 段统一创建：存量库需要先清理重复行，
-- 新建库在同一脚本末尾获得同一唯一键，避免这里重复定义导致 fresh install 失败。

CREATE TABLE IF NOT EXISTS et_incident (
    id            VARCHAR(36)  PRIMARY KEY,
    workspace_id     VARCHAR(64)  NOT NULL,
    agent_key     VARCHAR(128) NOT NULL,
    fingerprint   VARCHAR(64)  NOT NULL,
    open_fp       VARCHAR(64),
    title         VARCHAR(512),
    severity      VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    finding_count INT          NOT NULL DEFAULT 0,
    first_seen_at DATETIME     NOT NULL,
    last_seen_at  DATETIME     NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_incident_open_fp (workspace_id, agent_key, open_fp)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_evaluator (
    id            VARCHAR(36)  PRIMARY KEY,
    workspace_id     VARCHAR(64)  NOT NULL,
    evaluator_key VARCHAR(128) NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    spec_json     TEXT         NOT NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    version       INT          NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_evaluator (workspace_id, evaluator_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_eval_result (
    id                VARCHAR(36)  PRIMARY KEY,
    evaluator_id      VARCHAR(36)  NOT NULL,
    evaluator_key     VARCHAR(128) NOT NULL,
    evaluator_version INT          NOT NULL,
    workspace_id         VARCHAR(64)  NOT NULL,
    agent_key         VARCHAR(128) NOT NULL,
    agent_version     VARCHAR(64),
    trace_id          VARCHAR(64)  NOT NULL,
    attempt           INT          NOT NULL DEFAULT 1,
    score             DECIMAL(5,2),
    label             VARCHAR(32)  NOT NULL,
    confidence        DECIMAL(4,3),
    reason            VARCHAR(1024),
    evidence          TEXT,
    criterion_results_json MEDIUMTEXT,
    judge_model       VARCHAR(128),
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_eval_result (evaluator_id, trace_id, attempt)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_monitor_state (
    id               VARCHAR(36)  PRIMARY KEY,
    monitor_id       VARCHAR(36)  NOT NULL,
    window_start     DATETIME     NOT NULL,
    consecutive_hits INT          NOT NULL DEFAULT 0,
    prev_value       DECIMAL(18,4),
    last_eval_at     DATETIME     NOT NULL,
    UNIQUE KEY uk_monitor_state (monitor_id, window_start)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_push_record (
    id            VARCHAR(36)  PRIMARY KEY,
    event_id      VARCHAR(36)  NOT NULL,
    event_type    VARCHAR(32)  NOT NULL,
    target_url    VARCHAR(512) NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    last_status   VARCHAR(32),
    last_response VARCHAR(512),
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_score (
    id             VARCHAR(36)  PRIMARY KEY,
    workspace_id   VARCHAR(64)  NOT NULL,
    scope_type     VARCHAR(32)  NOT NULL,
    scope_id       VARCHAR(128) NOT NULL,
    time_window    VARCHAR(32)  NOT NULL,
    score          DECIMAL(5,2),
    status         VARCHAR(32)  NOT NULL,
    score_version  INT          NOT NULL DEFAULT 1,
    evidence_status VARCHAR(32),
    coverage       DECIMAL(5,2),
    dimensions     TEXT         NOT NULL,
    critical_gates TEXT,
    computed_at    DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_score (workspace_id, scope_type, scope_id, time_window, computed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_content (
    id            VARCHAR(36)  PRIMARY KEY,
    workspace_id     VARCHAR(64)  NOT NULL,
    trace_id      VARCHAR(64)  NOT NULL,
    span_id       VARCHAR(32)  NOT NULL,
    kind          VARCHAR(32)  NOT NULL,
    capture_mode  VARCHAR(16)  NOT NULL,
    content_hash  VARCHAR(64)  NOT NULL,
    content_length BIGINT      NOT NULL DEFAULT 0,
    preview       VARCHAR(512),
    body          TEXT,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_span_kind (workspace_id, trace_id, span_id, kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============ 平台域（V3.0 独立产品） ============
CREATE TABLE IF NOT EXISTS et_workspace (
    id         VARCHAR(64)  PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    is_default TINYINT      NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ws_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_user (
    id                   VARCHAR(36)  PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL,
    password_hash        VARCHAR(240) NOT NULL,
    name                 VARCHAR(128),
    email                VARCHAR(128),
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    is_super             TINYINT      NOT NULL DEFAULT 0,
    default_workspace_id VARCHAR(64),
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_user_workspace (
    id           VARCHAR(36) PRIMARY KEY,
    user_id      VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    role_id      VARCHAR(36) NOT NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_ws (user_id, workspace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_role (
    id         VARCHAR(36) PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    remark     VARCHAR(256),
    is_builtin TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_role_auth (
    id         VARCHAR(36) PRIMARY KEY,
    role_id    VARCHAR(36)  NOT NULL,
    permission VARCHAR(128) NOT NULL,
    UNIQUE KEY uk_role_perm (role_id, permission)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_menu (
    id         VARCHAR(36) PRIMARY KEY,
    parent_id  VARCHAR(36),
    name       VARCHAR(64)  NOT NULL,
    path       VARCHAR(128),
    icon       VARCHAR(32),
    sort       INT          NOT NULL DEFAULT 0,
    type       VARCHAR(16)  NOT NULL,
    permission VARCHAR(128)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_audit_log (
    id            VARCHAR(36)  PRIMARY KEY,
    user_id       VARCHAR(36)  NOT NULL,
    username      VARCHAR(64),
    workspace_id  VARCHAR(64),
    action        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(64),
    resource_id   VARCHAR(128),
    detail        TEXT,
    request_id    VARCHAR(64),
    client_ip     VARCHAR(64),
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_span_trace ON et_span_event (trace_id);
CREATE INDEX idx_span_agent_time ON et_span_event (workspace_id, agent_key, start_time);
CREATE INDEX idx_trace_status ON et_trace_summary (status, last_span_at);
CREATE INDEX idx_trace_agent ON et_trace_summary (workspace_id, agent_key, start_time);
CREATE INDEX idx_event_claim ON et_event (status, next_retry_at, created_at);
CREATE INDEX idx_finding_trace ON et_finding (trace_id);
CREATE INDEX idx_finding_fp ON et_finding (workspace_id, agent_key, fingerprint);
CREATE INDEX idx_incident_agent ON et_incident (workspace_id, agent_key, status);
CREATE INDEX idx_eval_result_agent ON et_eval_result (workspace_id, agent_key, evaluator_key, created_at);
CREATE INDEX idx_push_event ON et_push_record (event_id);
CREATE INDEX idx_score_scope ON et_score (workspace_id, scope_type, scope_id, time_window);
CREATE INDEX idx_content_span ON et_content (trace_id, span_id);
CREATE INDEX idx_audit_ws_time ON et_audit_log (workspace_id, created_at);
-- 时间窗列表 / 保留期扫描索引
CREATE INDEX idx_trace_ws_start ON et_trace_summary (workspace_id, start_time);
CREATE INDEX idx_span_ws_start ON et_span_event (workspace_id, start_time);
CREATE INDEX idx_content_created ON et_content (created_at);
CREATE INDEX idx_score_computed ON et_score (computed_at);
CREATE INDEX idx_push_created ON et_push_record (created_at);
CREATE INDEX idx_event_delivery_created ON et_event_handler_delivery (created_at);


CREATE TABLE IF NOT EXISTS et_agent (
    id          VARCHAR(36)  PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    agent_key   VARCHAR(128) NOT NULL,
    name        VARCHAR(256) NOT NULL,
    version     VARCHAR(64),
    risk_level  VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    owner       VARCHAR(128),
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent UNIQUE (workspace_id, agent_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_agent_tool (
    id           VARCHAR(36)  PRIMARY KEY,
    workspace_id VARCHAR(64)  NOT NULL,
    agent_key    VARCHAR(128) NOT NULL,
    tool_name    VARCHAR(128) NOT NULL,
    side_effect  VARCHAR(32)  NOT NULL DEFAULT 'WRITE',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_tool UNIQUE (workspace_id, agent_key, tool_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_access_token (
    id           VARCHAR(36)  PRIMARY KEY,
    token_hash   VARCHAR(64)  NOT NULL,
    token_masked VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(64)  NOT NULL,
    scopes       VARCHAR(256) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME,
    CONSTRAINT uk_token_hash UNIQUE (token_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============ 通知域（V3.0 §3.5） ============
CREATE TABLE IF NOT EXISTS et_notify_template (
    id           VARCHAR(36)  NOT NULL,
    workspace_id VARCHAR(64)  NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    channel      VARCHAR(32)  NOT NULL,
    subject      VARCHAR(256),
    content      TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notify_tpl (workspace_id, code, channel)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_notify_sender (
    id           VARCHAR(36)  NOT NULL,
    workspace_id VARCHAR(64)  NOT NULL,
    channel      VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    config       TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_notify_record (
    id           VARCHAR(36)  NOT NULL,
    workspace_id VARCHAR(64)  NOT NULL,
    event_id     VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    channel      VARCHAR(32)  NOT NULL,
    sender_id    VARCHAR(36),
    target       VARCHAR(256),
    subject      VARCHAR(256),
    content      TEXT,
    status       VARCHAR(16)  NOT NULL,
    error        VARCHAR(512),
    retry_count  INT          NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notify_dedup (event_id, sender_id),
    KEY idx_notify_event (event_id),
    KEY idx_notify_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ============ 首启种子数据（幂等 INSERT IGNORE） ============
-- admin 默认密码 123456（prod 模式首登后必须修改，SecurityGate 启动时校验）

-- 工作空间
INSERT IGNORE INTO et_workspace (id, code, name, is_default, created_at)
VALUES ('ws-default', 'default', '默认工作空间', 1, CURRENT_TIMESTAMP);

-- 角色
INSERT IGNORE INTO et_role (id, code, name, remark, is_builtin)
VALUES ('role-admin', 'WORKSPACE_ADMIN', '工作空间管理员', '全部权限', 1);
INSERT IGNORE INTO et_role (id, code, name, remark, is_builtin)
VALUES ('role-operator', 'TRUST_OPERATOR', 'Trust 操作员', '业务读写', 1);
INSERT IGNORE INTO et_role (id, code, name, remark, is_builtin)
VALUES ('role-viewer', 'TRUST_VIEWER', 'Trust 只读', '只读', 1);

-- 角色 → 权限（role-admin: 全部 18 项）
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-001', 'role-admin', 'workspace:trust:overview:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-002', 'role-admin', 'workspace:trust:trace:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-003', 'role-admin', 'workspace:trust:trace:content');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-004', 'role-admin', 'workspace:trust:finding:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-005', 'role-admin', 'workspace:trust:incident:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-006', 'role-admin', 'workspace:trust:incident:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-007', 'role-admin', 'workspace:trust:monitor:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-008', 'role-admin', 'workspace:trust:monitor:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-009', 'role-admin', 'workspace:trust:evaluator:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-010', 'role-admin', 'workspace:trust:evaluator:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-011', 'role-admin', 'workspace:trust:evaluation:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-012', 'role-admin', 'workspace:trust:evaluation:run');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-013', 'role-admin', 'workspace:trust:agent:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-014', 'role-admin', 'workspace:trust:agent:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-015', 'role-admin', 'workspace:trust:key:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-016', 'role-admin', 'workspace:sys:user:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-017', 'role-admin', 'workspace:sys:role:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-018', 'role-admin', 'workspace:sys:audit:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-019', 'role-admin', 'workspace:sys:notify:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-020', 'role-admin', 'workspace:sys:openapi:manage');

-- role-operator: Trust 业务读写（15 项，无系统管理）
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-001', 'role-operator', 'workspace:trust:overview:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-002', 'role-operator', 'workspace:trust:trace:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-003', 'role-operator', 'workspace:trust:trace:content');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-004', 'role-operator', 'workspace:trust:finding:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-005', 'role-operator', 'workspace:trust:incident:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-006', 'role-operator', 'workspace:trust:incident:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-007', 'role-operator', 'workspace:trust:monitor:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-008', 'role-operator', 'workspace:trust:monitor:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-009', 'role-operator', 'workspace:trust:evaluator:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-010', 'role-operator', 'workspace:trust:evaluator:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-011', 'role-operator', 'workspace:trust:evaluation:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-012', 'role-operator', 'workspace:trust:evaluation:run');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-013', 'role-operator', 'workspace:trust:agent:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-014', 'role-operator', 'workspace:trust:agent:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-015', 'role-operator', 'workspace:trust:key:manage');

-- role-viewer: 只读（8 项）
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('rv-001', 'role-viewer', 'workspace:trust:overview:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('rv-002', 'role-viewer', 'workspace:trust:trace:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('rv-003', 'role-viewer', 'workspace:trust:finding:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('rv-004', 'role-viewer', 'workspace:trust:incident:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('rv-005', 'role-viewer', 'workspace:trust:monitor:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('rv-006', 'role-viewer', 'workspace:trust:evaluator:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('rv-007', 'role-viewer', 'workspace:trust:evaluation:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('rv-008', 'role-viewer', 'workspace:trust:agent:query');

-- 菜单
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-overview', 'Trust 总览', '/trust/overview', 10, 'PAGE', 'workspace:trust:overview:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-traces', 'Trace 查询', '/trust/traces', 20, 'PAGE', 'workspace:trust:trace:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-incidents', 'Incidents', '/trust/incidents', 30, 'PAGE', 'workspace:trust:incident:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-monitors', 'Monitor 规则', '/trust/monitors', 40, 'PAGE', 'workspace:trust:monitor:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-evaluators', 'Evaluator 评估器', '/trust/evaluators', 50, 'PAGE', 'workspace:trust:evaluator:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-evaluations', '评估结果', '/trust/evaluations', 60, 'PAGE', 'workspace:trust:evaluation:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-agents', 'Agent 接入', '/trust/agents', 70, 'PAGE', 'workspace:trust:agent:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-users', '用户管理', '/sys/users', 100, 'PAGE', 'workspace:sys:user:manage');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-roles', '角色权限', '/sys/roles', 110, 'PAGE', 'workspace:sys:role:manage');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-audit', '审计日志', '/sys/audit', 120, 'PAGE', 'workspace:sys:audit:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-notify', '通知配置', '/sys/notify', 130, 'PAGE', 'workspace:sys:notify:manage');

-- Admin 用户（pbkdf2$20000$... = 口令 123456；is_super=1 绕过权限检查）
INSERT IGNORE INTO et_user (id, username, password_hash, name, status, is_super, default_workspace_id, created_at, updated_at)
VALUES ('user-admin', 'admin', 'pbkdf2$20000$mcpBt+AelQJ9y5VzyxLNgw==$lpAEpY5uEBx9Kbjv3pITpPj7vJg9g5S1G2lqCgLf4u0=', '平台管理员', 'ACTIVE', 1, 'ws-default', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Admin → 默认工作空间
INSERT IGNORE INTO et_user_workspace (id, user_id, workspace_id, role_id, created_at)
VALUES ('uw-admin', 'user-admin', 'ws-default', 'role-admin', CURRENT_TIMESTAMP);

-- Ingest Key 不预置：dev 的 seed-key 由应用启动时自动注册；prod 由 EMONLENS_INGEST_KEY 提供

-- 默认通知模板
INSERT IGNORE INTO et_notify_template (id, workspace_id, code, name, channel, subject, content, status, created_at)
VALUES ('nt-incident-email', 'ws-default', 'incident-default', 'Incident 通知（邮件）', 'EMAIL', 'EmonLens Incident 通知', 'Agent {{agent}} 触发 {{severity}} 级 Incident：{{title}}', 'ACTIVE', CURRENT_TIMESTAMP);
INSERT IGNORE INTO et_notify_template (id, workspace_id, code, name, channel, subject, content, status, created_at)
VALUES ('nt-incident-webhook', 'ws-default', 'incident-default', 'Incident 通知（Webhook）', 'WEBHOOK', NULL, 'Agent {{agent}} 触发 {{severity}} 级 Incident：{{title}}', 'ACTIVE', CURRENT_TIMESTAMP);
INSERT IGNORE INTO et_notify_template (id, workspace_id, code, name, channel, subject, content, status, created_at)
VALUES ('nt-finding-webhook', 'ws-default', 'finding-default', 'CRITICAL Finding 通知（Webhook）', 'WEBHOOK', NULL, 'Agent {{agent}} 产生 {{severity}} 级 Finding：{{title}}', 'ACTIVE', CURRENT_TIMESTAMP);
INSERT IGNORE INTO et_notify_template (id, workspace_id, code, name, channel, subject, content, status, created_at)
VALUES ('nt-score-webhook', 'ws-default', 'score-default', 'Trust Score 变更通知（Webhook）', 'WEBHOOK', NULL, 'Agent {{agent}} 评分已变更', 'ACTIVE', CURRENT_TIMESTAMP);


-- ============ Environments / Annotation Queues ============
ALTER TABLE et_span_event ADD COLUMN environment VARCHAR(32) DEFAULT 'default';
ALTER TABLE et_trace_summary ADD COLUMN environment VARCHAR(32) DEFAULT 'default';
CREATE TABLE IF NOT EXISTS et_annotation_queue (
    id          VARCHAR(36)  PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_by  VARCHAR(64),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS et_annotation_queue_item (
    id          VARCHAR(36)  PRIMARY KEY,
    queue_id    VARCHAR(36)  NOT NULL,
    trace_id    VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    reviewer    VARCHAR(64),
    score       DECIMAL(5,2),
    comment     VARCHAR(1024),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,
    UNIQUE KEY uk_annotation_queue_trace (queue_id, trace_id)
);
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-annotation', '注解队列', '/annotation-queues', 75, 'PAGE', 'workspace:trust:trace:query');

-- ============ Prompt Management / Datasets / Model Prices / Tags / Comments ============
CREATE TABLE IF NOT EXISTS et_prompt (
    id          VARCHAR(36)  PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    version     INT          NOT NULL,
    prompt      TEXT         NOT NULL,
    config_json TEXT,
    labels      VARCHAR(256),
    created_by  VARCHAR(64),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prompt UNIQUE (workspace_id, name, version)
);
CREATE TABLE IF NOT EXISTS et_model_price (
    id                 VARCHAR(36) PRIMARY KEY,
    workspace_id       VARCHAR(64) NOT NULL,
    model              VARCHAR(128) NOT NULL,
    input_per_million  DECIMAL(12,6) NOT NULL DEFAULT 0,
    output_per_million DECIMAL(12,6) NOT NULL DEFAULT 0,
    -- 缓存命中输入价（$/1M）；NULL=未配置，回退为输入价的 10%（OpenAI/DeepSeek 惯例）
    cached_input_per_million DECIMAL(12,6) NULL DEFAULT NULL,
    currency           VARCHAR(8)   NOT NULL DEFAULT 'USD',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_model_price UNIQUE (workspace_id, model)
);
CREATE TABLE IF NOT EXISTS et_trace_tag (
    id          VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    trace_id    VARCHAR(64)  NOT NULL,
    tag         VARCHAR(64)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trace_tag UNIQUE (workspace_id, trace_id, tag)
);
CREATE INDEX idx_trace_tag_tag ON et_trace_tag (workspace_id, tag);
CREATE TABLE IF NOT EXISTS et_comment (
    id           VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id  VARCHAR(64) NOT NULL,
    username     VARCHAR(64),
    content      TEXT        NOT NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS et_dataset (
    id          VARCHAR(36)  PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    dataset_key VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    blueprint_json TEXT,
    domain_pack_id VARCHAR(36),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dataset UNIQUE (workspace_id, dataset_key)
);
CREATE TABLE IF NOT EXISTS et_dataset_item (
    id             VARCHAR(36) PRIMARY KEY,
    dataset_id     VARCHAR(36) NOT NULL,
    trace_id       VARCHAR(64),
    input          TEXT,
    expected_output TEXT,
    metadata_json  TEXT,
    schema_version INT NOT NULL DEFAULT 1,
    retrieval_context_json MEDIUMTEXT,
    expected_tools_json    MEDIUMTEXT,
    turns_json             MEDIUMTEXT,
    expectations_json      MEDIUMTEXT,
    content_hash   CHAR(64),
    source_type    VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    source_ref     VARCHAR(500),
    purpose_tags   VARCHAR(255),
    risk_level     VARCHAR(16) DEFAULT 'UNASSIGNED',
    group_key      VARCHAR(128),
    split          VARCHAR(16) DEFAULT 'UNASSIGNED',
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    invalid_reason       VARCHAR(500),
    invalidated_at       DATETIME,
    failure_mode   VARCHAR(128),
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_dataset_item_hash (dataset_id, content_hash)
);
CREATE TABLE IF NOT EXISTS et_dataset_synthesis_job (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    dataset_id        VARCHAR(36) NOT NULL,
    request_key       VARCHAR(128) NOT NULL,
    source_type       VARCHAR(24) NOT NULL,
    source_json       MEDIUMTEXT,
    config_json       MEDIUMTEXT NOT NULL,
    status            VARCHAR(24) NOT NULL,
    total_candidates  INT NOT NULL DEFAULT 0,
    accepted_count    INT NOT NULL DEFAULT 0,
    rejected_count    INT NOT NULL DEFAULT 0,
    model             VARCHAR(128),
    prompt_tokens     INT,
    completion_tokens INT,
    error             VARCHAR(1000),
    created_by        VARCHAR(64),
    created_at        DATETIME NOT NULL,
    started_at        DATETIME,
    finished_at       DATETIME,
    UNIQUE KEY uk_synthesis_request (workspace_id, request_key),
    KEY idx_synthesis_dataset (workspace_id, dataset_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_dataset_synthesis_candidate (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    job_id            VARCHAR(36) NOT NULL,
    candidate_key     CHAR(64) NOT NULL,
    evolution         VARCHAR(32),
    payload_json      MEDIUMTEXT NOT NULL,
    source_ref        VARCHAR(500),
    quality_score     DOUBLE,
    filter_reason     VARCHAR(500),
    status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_item_id   VARCHAR(36),
    reviewed_by       VARCHAR(64),
    reviewed_at       DATETIME,
    created_at        DATETIME NOT NULL,
    UNIQUE KEY uk_synthesis_candidate (job_id, candidate_key),
    KEY idx_synthesis_candidate_status (job_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_dataset_run (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64),
    dataset_id    VARCHAR(36) NOT NULL,
    dataset_revision_id VARCHAR(36),
    domain_pack_id VARCHAR(36),
    resumed_from_run_id VARCHAR(36),
    attempt       INT DEFAULT 1,
    external_calls_reserved INT,
    max_tokens    BIGINT,
    tokens_reserved BIGINT NOT NULL DEFAULT 0,
    tokens_spent  BIGINT NOT NULL DEFAULT 0,
    suite_id      VARCHAR(36),
    candidate_label VARCHAR(128),
    candidate_fingerprint CHAR(64),
    evaluator_id  VARCHAR(36) NOT NULL,
    evaluator_key VARCHAR(128),
    status        VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    item_count    INT NOT NULL DEFAULT 0,
    pass_count    INT NOT NULL DEFAULT 0,
    fail_count    INT NOT NULL DEFAULT 0,
    request_key   VARCHAR(128),
    request_hash  CHAR(64),
    config_snapshot_json MEDIUMTEXT,
    lease_token   VARCHAR(64),
    lease_expires_at DATETIME,
    started_at    DATETIME NOT NULL,
    finished_at   DATETIME,
    CONSTRAINT uk_dataset_run_request UNIQUE (workspace_id, request_key),
    CONSTRAINT uk_dataset_run_resume UNIQUE (workspace_id, resumed_from_run_id, attempt),
    KEY idx_dataset_run_evidence (workspace_id, status, finished_at)
);
CREATE TABLE IF NOT EXISTS et_dataset_run_slot (
    slot_key     VARCHAR(64)  PRIMARY KEY,
    run_id       VARCHAR(36)  NOT NULL,
    acquired_at  DATETIME     NOT NULL,
    expires_at   DATETIME     NOT NULL,
    UNIQUE KEY uk_dataset_run_slot_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_dataset_run_item (
    id       VARCHAR(36) PRIMARY KEY,
    run_id   VARCHAR(36) NOT NULL,
    item_id  VARCHAR(36) NOT NULL,
    trace_id VARCHAR(64),
    agent_key VARCHAR(128),
    agent_version VARCHAR(64),
    score    DECIMAL(5,2),
    label    VARCHAR(32),
    reason   VARCHAR(1024),
    sample_snapshot_json MEDIUMTEXT,
    result_details_json  MEDIUMTEXT,
    evaluator_results_json MEDIUMTEXT,
    CONSTRAINT uk_dataset_run_item UNIQUE (run_id, item_id),
    KEY idx_dataset_run_item_agent (agent_key)
);

-- Run/Task/Attempt 事实源：一个样本 × 一个评估器 = 一条任务；一次任务执行 = 一条 attempt。
-- 任务行只记录判定与用量，不保存候选正文；正文仍受内容权限约束。
CREATE TABLE IF NOT EXISTS et_eval_task (
    id                    VARCHAR(36) PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL,
    run_id                VARCHAR(36) NOT NULL,
    run_item_id           VARCHAR(36) NOT NULL,
    dataset_id            VARCHAR(36) NOT NULL,
    item_id               VARCHAR(36) NOT NULL,
    item_order            INT NOT NULL DEFAULT 0,
    evaluator_id          VARCHAR(36) NOT NULL,
    evaluator_key         VARCHAR(128) NOT NULL,
    evaluator_version     INT NOT NULL,
    evaluator_order       INT NOT NULL DEFAULT 0,
    evaluator_fingerprint CHAR(64),
    judge_template_key     VARCHAR(100),
    judge_template_version INT,
    judge_template_hash    CHAR(64),
    cache_key              CHAR(64),
    execution_status      VARCHAR(16) NOT NULL,
    verdict               VARCHAR(24),
    score                 DECIMAL(8,4),
    reason_code           VARCHAR(64),
    reason                VARCHAR(1024),
    model                 VARCHAR(128),
    prompt_tokens         INT,
    completion_tokens     INT,
    total_tokens          INT,
    latency_ms            INT,
    attempt_count         INT NOT NULL DEFAULT 0,
    result_json           MEDIUMTEXT,
    evidence_hash         CHAR(64),
    created_at            DATETIME(6) NOT NULL,
    started_at            DATETIME(6),
    finished_at           DATETIME(6),
    CONSTRAINT uk_eval_task UNIQUE (workspace_id, run_id, run_item_id, evaluator_id),
    KEY idx_eval_task_run (workspace_id, run_id, execution_status),
    KEY idx_eval_task_run_item (run_item_id),
    KEY idx_eval_task_open (execution_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_eval_attempt (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    run_id            VARCHAR(36) NOT NULL,
    task_id           VARCHAR(36) NOT NULL,
    call_seq          INT NOT NULL,
    outcome           VARCHAR(24) NOT NULL,
    verdict           VARCHAR(24),
    model             VARCHAR(128),
    prompt_tokens     INT,
    completion_tokens INT,
    total_tokens      INT,
    latency_ms        INT,
    reason_code       VARCHAR(64),
    detail_json       MEDIUMTEXT,
    created_at        DATETIME(6) NOT NULL,
    CONSTRAINT uk_eval_attempt UNIQUE (workspace_id, task_id, call_seq),
    KEY idx_eval_attempt_run (workspace_id, run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_judge_review (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    run_id            VARCHAR(36) NOT NULL,
    item_id           VARCHAR(36) NOT NULL,
    evaluator_key     VARCHAR(100) NOT NULL,
    evaluator_version INT NOT NULL,
    judge_label       VARCHAR(16) NOT NULL,
    human_label       VARCHAR(16) NOT NULL,
    reviewer_slot     VARCHAR(16) NOT NULL DEFAULT 'PRIMARY',
    protocol_version  VARCHAR(40) NOT NULL DEFAULT 'judge-calibration/1',
    note              VARCHAR(500),
    reviewed_by       VARCHAR(64),
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    UNIQUE KEY uk_judge_review_slot (run_id, item_id, evaluator_key, evaluator_version, reviewer_slot),
    UNIQUE KEY uk_judge_review_reviewer (run_id, item_id, evaluator_key, evaluator_version, reviewed_by),
    KEY idx_judge_review_run (workspace_id, run_id, evaluator_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_dataset_health_snapshot (
    id                     VARCHAR(36) PRIMARY KEY,
    workspace_id           VARCHAR(64) NOT NULL,
    dataset_id             VARCHAR(36) NOT NULL,
    status                 VARCHAR(24) NOT NULL,
    item_count             INT         NOT NULL DEFAULT 0,
    requirement_coverage   DOUBLE,
    stale                  BOOLEAN     NOT NULL DEFAULT FALSE,
    stale_days             BIGINT      NOT NULL DEFAULT 0,
    latest_revision        INT,
    health_json            LONGTEXT,
    created_at             DATETIME     NOT NULL,
    KEY idx_health_snapshot_dataset (workspace_id, dataset_id, created_at),
    KEY idx_health_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_domain_pack (
    id                 VARCHAR(36) PRIMARY KEY,
    workspace_id       VARCHAR(64) NOT NULL,
    pack_key           VARCHAR(64) NOT NULL,
    version            VARCHAR(32) NOT NULL,
    schema_version     VARCHAR(32) NOT NULL,
    name               VARCHAR(200) NOT NULL,
    description        VARCHAR(1000),
    manifest_json      LONGTEXT    NOT NULL,
    digest             VARCHAR(64) NOT NULL,
    status             VARCHAR(24) NOT NULL,
    compatibility      VARCHAR(64),
    created_by         VARCHAR(64),
    created_at         DATETIME    NOT NULL,
    deprecated_at      DATETIME,
    UNIQUE KEY uk_domain_pack_version (workspace_id, pack_key, version),
    KEY idx_domain_pack_key (workspace_id, pack_key, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_quality_case (
    id                   VARCHAR(36) PRIMARY KEY,
    workspace_id         VARCHAR(64) NOT NULL,
    dataset_id           VARCHAR(36),
    source_run_id        VARCHAR(36),
    source_item_id       VARCHAR(36),
    agent_key            VARCHAR(200),
    title                VARCHAR(300) NOT NULL,
    phenomenon           TEXT NOT NULL,
    evidence_json        TEXT,
    root_cause_category  VARCHAR(40),
    owner                VARCHAR(100),
    status               VARCHAR(24) NOT NULL,
    fix_fingerprint      VARCHAR(128),
    fix_note             TEXT,
    regression_run_id    VARCHAR(36),
    verified_by          VARCHAR(64),
    verified_at          DATETIME,
    created_by           VARCHAR(64),
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    KEY idx_quality_case_run (workspace_id, source_run_id),
    KEY idx_quality_case_status (workspace_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_pairwise_evaluation (
    id                    VARCHAR(36) PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL,
    dataset_id            VARCHAR(36) NOT NULL,
    run_a_id              VARCHAR(36) NOT NULL,
    run_b_id              VARCHAR(36) NOT NULL,
    protocol              VARCHAR(40) NOT NULL,
    protocol_version      VARCHAR(40),
    samples_per_direction INT NOT NULL DEFAULT 1,
    position_randomized   BOOLEAN NOT NULL DEFAULT FALSE,
    position_seed         BIGINT,
    position_a_first_count INT NOT NULL DEFAULT 0,
    position_b_first_count INT NOT NULL DEFAULT 0,
    judge_model           VARCHAR(100),
    judge_models_json     TEXT,
    judge_count           INT NOT NULL DEFAULT 1,
    item_count            INT NOT NULL DEFAULT 0,
    judged_count          INT NOT NULL DEFAULT 0,
    a_wins                INT NOT NULL DEFAULT 0,
    b_wins                INT NOT NULL DEFAULT 0,
    ties                  INT NOT NULL DEFAULT 0,
    unknown_count         INT NOT NULL DEFAULT 0,
    preference_rate       DOUBLE,
    status                VARCHAR(24) NOT NULL,
    created_by            VARCHAR(64),
    created_at            DATETIME     NOT NULL,
    KEY idx_pairwise_runs (workspace_id, run_a_id, run_b_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_pairwise_item (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    evaluation_id VARCHAR(36) NOT NULL,
    item_id       VARCHAR(36) NOT NULL,
    preference    VARCHAR(16) NOT NULL,
    reason        VARCHAR(1000),
    calls_json    TEXT,
    UNIQUE KEY uk_pairwise_item (evaluation_id, item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_business_experiment (
    id                     VARCHAR(36) PRIMARY KEY,
    workspace_id           VARCHAR(64) NOT NULL,
    source                 VARCHAR(100) NOT NULL,
    external_experiment_id VARCHAR(200) NOT NULL,
    agent_key              VARCHAR(200),
    run_id                 VARCHAR(36),
    candidate_fingerprint  VARCHAR(128),
    control_fingerprint    VARCHAR(128),
    target                 VARCHAR(500),
    design_type            VARCHAR(24) NOT NULL,
    design_json            TEXT,
    primary_metric         VARCHAR(100) NOT NULL,
    guardrails_json        TEXT,
    min_sample_size        INT NOT NULL DEFAULT 0,
    require_signature      BOOLEAN NOT NULL DEFAULT FALSE,
    started_at             DATETIME,
    ended_at               DATETIME,
    created_by             VARCHAR(64),
    created_at             DATETIME     NOT NULL,
    updated_at             DATETIME     NOT NULL,
    UNIQUE KEY uk_business_experiment_external (workspace_id, source, external_experiment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_business_source_credential (
    id             VARCHAR(36) PRIMARY KEY,
    workspace_id   VARCHAR(64) NOT NULL,
    key_id         VARCHAR(64) NOT NULL,
    source         VARCHAR(100) NOT NULL,
    name           VARCHAR(200),
    public_key     TEXT NOT NULL,
    status         VARCHAR(16) NOT NULL,
    created_by     VARCHAR(64),
    created_at     DATETIME NOT NULL,
    revoked_at     DATETIME,
    UNIQUE KEY uk_business_source_key (workspace_id, key_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_business_evidence (
    id                   VARCHAR(36) PRIMARY KEY,
    workspace_id         VARCHAR(64) NOT NULL,
    experiment_id        VARCHAR(36) NOT NULL,
    report_version       VARCHAR(64) NOT NULL,
    payload_hash         VARCHAR(64) NOT NULL,
    design_json          TEXT,
    metrics_json         TEXT,
    quality_checks_json  TEXT,
    comparisons_json     TEXT,
    adjustment_method    VARCHAR(40),
    decision             VARCHAR(24) NOT NULL,
    reported_at          DATETIME,
    received_by          VARCHAR(64),
    received_at          DATETIME     NOT NULL,
    source_key_id        VARCHAR(64),
    signature            VARCHAR(1024),
    signed_at            VARCHAR(64),
    nonce                VARCHAR(128),
    signature_verified   BOOLEAN,
    UNIQUE KEY uk_business_evidence_version (experiment_id, report_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_business_evidence_nonce (
    id             VARCHAR(36) PRIMARY KEY,
    workspace_id   VARCHAR(64) NOT NULL,
    source_key_id  VARCHAR(64) NOT NULL,
    nonce          VARCHAR(128) NOT NULL,
    created_at     DATETIME NOT NULL,
    UNIQUE KEY uk_business_evidence_nonce (workspace_id, source_key_id, nonce)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_quality_gate (
    id                    VARCHAR(36) PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL,
    run_id                VARCHAR(36) NOT NULL,
    dataset_id            VARCHAR(36) NOT NULL,
    policy_key            VARCHAR(64) NOT NULL,
    policy_version        VARCHAR(32) NOT NULL,
    decision              VARCHAR(16) NOT NULL,
    summary               VARCHAR(512),
    candidate_label       VARCHAR(128),
    candidate_fingerprint CHAR(64),
    run_status            VARCHAR(16),
    coverage              DECIMAL(5,2),
    pass_count            INT NOT NULL DEFAULT 0,
    fail_count            INT NOT NULL DEFAULT 0,
    unknown_count         INT NOT NULL DEFAULT 0,
    error_count           INT NOT NULL DEFAULT 0,
    checks_json           MEDIUMTEXT NOT NULL,
    valid_until           DATETIME,
    created_by            VARCHAR(64),
    created_at            DATETIME(6) NOT NULL,
    KEY idx_quality_gate_run (workspace_id, run_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_evaluator_version (
    id           VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    evaluator_id VARCHAR(36) NOT NULL,
    version      INT NOT NULL,
    type         VARCHAR(32) NOT NULL,
    spec_json    MEDIUMTEXT NOT NULL,
    fingerprint  CHAR(64) NOT NULL,
    template_key     VARCHAR(100),
    template_version INT,
    template_hash    CHAR(64),
    created_at   DATETIME NOT NULL,
    UNIQUE KEY uk_evaluator_version (workspace_id, evaluator_id, version),
    KEY idx_evaluator_version (workspace_id, evaluator_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_judge_template (
    id             VARCHAR(36) PRIMARY KEY,
    workspace_id   VARCHAR(64) NOT NULL,
    template_key   VARCHAR(100) NOT NULL,
    version        INT NOT NULL,
    name           VARCHAR(200),
    system_prompt  MEDIUMTEXT NOT NULL,
    user_prompt    MEDIUMTEXT,
    output_schema  MEDIUMTEXT,
    status         VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    fingerprint    CHAR(64) NOT NULL,
    created_by     VARCHAR(64),
    created_at     DATETIME NOT NULL,
    UNIQUE KEY uk_judge_template_version (workspace_id, template_key, version),
    KEY idx_judge_template_key (workspace_id, template_key, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_eval_cache (
    id                    VARCHAR(36) PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL,
    cache_key             CHAR(64) NOT NULL,
    sample_hash           CHAR(64) NOT NULL,
    evaluator_fingerprint CHAR(64) NOT NULL,
    judge_model           VARCHAR(128),
    template_hash         CHAR(64),
    protocol_version      VARCHAR(40),
    label                 VARCHAR(24) NOT NULL,
    score                 DECIMAL(10,4),
    reason                VARCHAR(1024),
    result_json           MEDIUMTEXT,
    prompt_tokens         INT,
    completion_tokens     INT,
    created_at            DATETIME(6) NOT NULL,
    expires_at            DATETIME(6),
    UNIQUE KEY uk_eval_cache_key (workspace_id, cache_key),
    KEY idx_eval_cache_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_eval_suite (
    id           VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    suite_key    VARCHAR(100) NOT NULL,
    name         VARCHAR(200),
    description  VARCHAR(500),
    version      INT NOT NULL,
    spec_json    MEDIUMTEXT NOT NULL,
    fingerprint  CHAR(64) NOT NULL,
    created_at   DATETIME NOT NULL,
    UNIQUE KEY uk_eval_suite_version (workspace_id, suite_key, version),
    KEY idx_eval_suite_key (workspace_id, suite_key, version),
    KEY idx_eval_suite_fingerprint (workspace_id, suite_key, fingerprint)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_dataset_revision (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    dataset_id    VARCHAR(36) NOT NULL,
    revision      INT NOT NULL,
    label         VARCHAR(128),
    manifest_hash CHAR(64) NOT NULL,
    item_count    INT NOT NULL DEFAULT 0,
    created_by    VARCHAR(64),
    created_at    DATETIME NOT NULL,
    UNIQUE KEY uk_dataset_revision (workspace_id, dataset_id, revision),
    KEY idx_dataset_revision (workspace_id, dataset_id, revision)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_dataset_revision_item (
    id              VARCHAR(36) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    revision_id     VARCHAR(36) NOT NULL,
    item_key        VARCHAR(64) NOT NULL,
    trace_id        VARCHAR(64),
    input           MEDIUMTEXT,
    expected_output MEDIUMTEXT,
    metadata_json   MEDIUMTEXT,
    schema_version  INT NOT NULL DEFAULT 1,
    retrieval_context_json MEDIUMTEXT,
    expected_tools_json    MEDIUMTEXT,
    turns_json             MEDIUMTEXT,
    expectations_json      MEDIUMTEXT,
    purpose_tags    VARCHAR(255),
    risk_level      VARCHAR(16),
    group_key       VARCHAR(128),
    split           VARCHAR(16),
    failure_mode    VARCHAR(128),
    content_hash    CHAR(64) NOT NULL,
    item_order      INT NOT NULL,
    created_at      DATETIME NOT NULL,
    UNIQUE KEY uk_dataset_revision_item (revision_id, item_key),
    KEY idx_dataset_revision_item (revision_id, item_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 新权限授予 role-admin / role-operator
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-101', 'role-admin', 'workspace:trust:prompt:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-102', 'role-admin', 'workspace:trust:prompt:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-103', 'role-admin', 'workspace:trust:dataset:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-104', 'role-admin', 'workspace:trust:dataset:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-105', 'role-admin', 'workspace:trust:model:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-106', 'role-admin', 'workspace:trust:model:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-101', 'role-operator', 'workspace:trust:prompt:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-102', 'role-operator', 'workspace:trust:prompt:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-103', 'role-operator', 'workspace:trust:dataset:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-104', 'role-operator', 'workspace:trust:dataset:manage');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-105', 'role-operator', 'workspace:trust:model:query');
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ro-106', 'role-operator', 'workspace:trust:model:manage');

-- 演示数据：提示词（2 版本，v1=production）、模型价格、标签、评论、数据集


INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-prompts', '提示词', '/prompts', 60, 'PAGE', 'workspace:trust:prompt:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-datasets', '数据集', '/datasets', 65, 'PAGE', 'workspace:trust:dataset:query');
INSERT IGNORE INTO et_menu (id, name, path, sort, type, permission) VALUES ('m-models', '模型价格', '/models', 70, 'PAGE', 'workspace:trust:model:query');

-- ============ S1 可用性增强（V2.0 重设计） ============
ALTER TABLE et_span_event ADD COLUMN session_id VARCHAR(64);
ALTER TABLE et_span_event ADD COLUMN input_text TEXT;
ALTER TABLE et_span_event ADD COLUMN output_text TEXT;
ALTER TABLE et_span_event ADD COLUMN cost_usd DECIMAL(10,6) DEFAULT 0;
ALTER TABLE et_span_event ADD COLUMN level VARCHAR(16) DEFAULT 'INFO';
ALTER TABLE et_trace_summary ADD COLUMN session_id VARCHAR(64);
ALTER TABLE et_trace_summary ADD COLUMN input_preview VARCHAR(512);
ALTER TABLE et_trace_summary ADD COLUMN output_preview VARCHAR(512);
ALTER TABLE et_trace_summary ADD COLUMN total_cost_usd DECIMAL(10,6) DEFAULT 0;
ALTER TABLE et_trace_summary ADD COLUMN model_name VARCHAR(128);
ALTER TABLE et_trace_summary ADD COLUMN user_id VARCHAR(64);
ALTER TABLE et_trace_summary ADD COLUMN prompt_name VARCHAR(128);
ALTER TABLE et_trace_summary ADD COLUMN prompt_version VARCHAR(32);
CREATE INDEX idx_span_session ON et_span_event (session_id);
CREATE INDEX idx_trace_session ON et_trace_summary (session_id);

-- ============ S2 生产可用性（运行时设置 + 权限） ============
CREATE TABLE IF NOT EXISTS et_sys_setting (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(64) NOT NULL DEFAULT 'GLOBAL',
  setting_key VARCHAR(64) NOT NULL,
  setting_value TEXT,
  updated_at DATETIME,
  CONSTRAINT uk_sys_setting UNIQUE (workspace_id, setting_key)
);
INSERT IGNORE INTO et_role_auth (id, role_id, permission) VALUES ('ra-201', 'role-admin', 'workspace:sys:setting:manage');
ALTER TABLE et_eval_result ADD COLUMN prompt_tokens INT;
ALTER TABLE et_eval_result ADD COLUMN completion_tokens INT;

-- ============ S3 安全加固（JWT 登出吊销） ============
CREATE TABLE IF NOT EXISTS et_auth_token_blacklist (
  token_hash CHAR(64) PRIMARY KEY COMMENT '原始 token 的 SHA-256',
  expire_at BIGINT NOT NULL COMMENT '原 token 过期时间（epoch 秒），用于惰性回收',
  created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已登出的 JWT 黑名单';

-- ============ S4 用户洞察（span 终端用户归属） ============
ALTER TABLE et_span_event ADD COLUMN user_id VARCHAR(64);
CREATE INDEX idx_trace_user ON et_trace_summary (user_id);

-- ============ S5 数据集重放（REPLAY 模式） ============
ALTER TABLE et_dataset_run ADD COLUMN run_mode VARCHAR(16) DEFAULT 'TRACE';
ALTER TABLE et_dataset_run ADD COLUMN model VARCHAR(128);
ALTER TABLE et_dataset_run_item ADD COLUMN source_trace_id VARCHAR(64);
ALTER TABLE et_dataset_run_item ADD COLUMN replay_output TEXT;

-- ============ 缓存命中 tokens（计费/缓存率分析） ============
ALTER TABLE et_span_event ADD COLUMN cached_input_tokens BIGINT NOT NULL DEFAULT 0;

-- ============ S6 通知模板唯一键修正（channel 维度缺失导致 WEBHOOK 模板从未入库） ============
-- 旧 uk (workspace_id, code) 下同 code 不同 channel 的第二条 INSERT IGNORE 被静默丢弃；
-- EMAIL 与 WEBHOOK 模板 code 相同是合法设计（派发按 channel 匹配模板）。
ALTER TABLE et_notify_template DROP INDEX uk_notify_tpl;
ALTER TABLE et_notify_template ADD UNIQUE KEY uk_notify_tpl (workspace_id, code, channel);
-- 补齐被丢掉的 WEBHOOK 种子模板
INSERT IGNORE INTO et_notify_template (id, workspace_id, code, name, channel, subject, content, status, created_at)
VALUES ('nt-incident-webhook', 'ws-default', 'incident-default', 'Incident 通知（Webhook）', 'WEBHOOK', NULL, 'Agent {{agent}} 触发 {{severity}} 级 Incident：{{title}}', 'ACTIVE', CURRENT_TIMESTAMP);


-- ============ S7 Finding 幂等唯一键（事件重试防重复计数/重复通知） ============
-- 事件投递 at-least-once，TRACE_COMPLETED 重试会重跑检测；无 uk 时同一 trace 同一
-- 检测器的 Finding 重复入库，推高 incident finding_count 与信任分罚分。
-- 存量重复行先清理（每组保留最早一条），再建唯一键。
-- 按 id 留最早一条（created_at 秒级精度下同秒重复删不干净，主键单调可比）
DELETE f1 FROM et_finding f1
JOIN et_finding f2
  ON f1.workspace_id = f2.workspace_id AND f1.trace_id = f2.trace_id
 AND f1.type = f2.type AND f1.detector_id = f2.detector_id
 AND f1.id > f2.id;
ALTER TABLE et_finding ADD UNIQUE KEY uk_finding_occurrence (workspace_id, trace_id, type, detector_id);


CREATE TABLE IF NOT EXISTS et_auto_rubric_run (
    id              VARCHAR(36) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    dataset_id      VARCHAR(36) NOT NULL,
    source_run_id   VARCHAR(36) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    audit_split     VARCHAR(24) NOT NULL DEFAULT 'HOLDOUT',
    audit_count     INT NOT NULL DEFAULT 0,
    candidate_count INT NOT NULL DEFAULT 0,
    proposed_count  INT NOT NULL DEFAULT 0,
    max_judge_calls INT NOT NULL DEFAULT 200,
    judge_calls     INT NOT NULL DEFAULT 0,
    generation_calls INT NOT NULL DEFAULT 0,
    bootstrap_rounds INT NOT NULL DEFAULT 200,
    bootstrap_seed  BIGINT,
    min_selection_frequency DOUBLE NOT NULL DEFAULT 0.6,
    judge_model     VARCHAR(100),
    generation_model VARCHAR(100),
    config_json     TEXT,
    selection_json  TEXT,
    created_by      VARCHAR(64),
    created_at      DATETIME NOT NULL,
    finished_at     DATETIME,
    KEY idx_auto_rubric_run_dataset (workspace_id, dataset_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_auto_rubric_candidate (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    run_id            VARCHAR(36) NOT NULL,
    rule_key          VARCHAR(100) NOT NULL,
    rule_text         VARCHAR(2000) NOT NULL,
    origin            VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    decision          VARCHAR(24) NOT NULL,
    audit_count       INT NOT NULL DEFAULT 0,
    violation_count   INT NOT NULL DEFAULT 0,
    true_positive     INT NOT NULL DEFAULT 0,
    false_positive    INT NOT NULL DEFAULT 0,
    false_negative    INT NOT NULL DEFAULT 0,
    true_negative     INT NOT NULL DEFAULT 0,
    precision_rate    DOUBLE,
    recall            DOUBLE,
    support_count     INT NOT NULL DEFAULT 0,
    selection_frequency DOUBLE,
    stable             BOOLEAN,
    violation_rate    DOUBLE,
    min_precision     DOUBLE NOT NULL DEFAULT 0.8,
    min_support       INT NOT NULL DEFAULT 3,
    details_json      TEXT,
    review_note       VARCHAR(1000),
    reviewed_by       VARCHAR(64),
    reviewed_at       DATETIME,
    created_at        DATETIME NOT NULL,
    UNIQUE KEY uk_auto_rubric_rule (run_id, rule_key),
    KEY idx_auto_rubric_candidate_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


CREATE TABLE IF NOT EXISTS et_pairwise_human_review (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    evaluation_id VARCHAR(36) NOT NULL,
    item_id       VARCHAR(36) NOT NULL,
    reviewer      VARCHAR(64) NOT NULL,
    preference    VARCHAR(16) NOT NULL,
    note          VARCHAR(1000),
    created_at    DATETIME NOT NULL,
    UNIQUE KEY uk_pairwise_human_review (evaluation_id, item_id, reviewer),
    KEY idx_pairwise_human_review_eval (evaluation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============ R1-2 Playground 矩阵实验（列 × 样本网格，试跑产物） ============
CREATE TABLE IF NOT EXISTS et_playground_matrix (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    request_key       VARCHAR(128) NOT NULL,
    request_hash      CHAR(64) NOT NULL,
    dataset_id        VARCHAR(36) NOT NULL,
    dataset_revision_id VARCHAR(36),
    name              VARCHAR(128),
    status            VARCHAR(16) NOT NULL,
    columns_json      MEDIUMTEXT NOT NULL,
    estimate_json     MEDIUMTEXT,
    evaluator_ids_json MEDIUMTEXT,
    item_count        INT NOT NULL DEFAULT 0,
    cell_count        INT NOT NULL DEFAULT 0,
    completed_cells   INT NOT NULL DEFAULT 0,
    failed_cells      INT NOT NULL DEFAULT 0,
    prompt_tokens     BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    cost              DECIMAL(18,6),
    currency          VARCHAR(8),
    unpriced_models   VARCHAR(512),
    error             VARCHAR(1000),
    created_by        VARCHAR(64),
    created_at        DATETIME(3) NOT NULL,
    started_at        DATETIME(3),
    finished_at       DATETIME(3),
    UNIQUE KEY uk_playground_matrix_request (workspace_id, request_key),
    KEY idx_playground_matrix_created (workspace_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_playground_cell (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    matrix_id         VARCHAR(36) NOT NULL,
    column_key        VARCHAR(64) NOT NULL,
    item_id           VARCHAR(36) NOT NULL,
    item_order        INT NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL,
    output            MEDIUMTEXT,
    output_hash       CHAR(64),
    rendered_prompt   MEDIUMTEXT,
    input_text        MEDIUMTEXT,
    expected_text     MEDIUMTEXT,
    error             VARCHAR(1000),
    prompt_tokens     INT,
    completion_tokens INT,
    latency_ms        INT,
    cost              DECIMAL(18,6),
    scores_json       MEDIUMTEXT,
    aggregate_label   VARCHAR(16),
    score             DECIMAL(8,4),
    created_at        DATETIME(3) NOT NULL,
    finished_at       DATETIME(3),
    UNIQUE KEY uk_playground_cell (matrix_id, column_key, item_id),
    KEY idx_playground_cell_matrix (matrix_id, column_key, item_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_playground_diff (
    id                  VARCHAR(36) PRIMARY KEY,
    workspace_id        VARCHAR(64) NOT NULL,
    matrix_id           VARCHAR(36) NOT NULL,
    base_column         VARCHAR(64) NOT NULL,
    target_column       VARCHAR(64) NOT NULL,
    protocol_version    VARCHAR(32) NOT NULL,
    judge_models_json   TEXT,
    position_randomized TINYINT(1),
    position_seed       BIGINT,
    item_count          INT NOT NULL DEFAULT 0,
    same_count          INT NOT NULL DEFAULT 0,
    changed_count       INT NOT NULL DEFAULT 0,
    improved_count      INT NOT NULL DEFAULT 0,
    regressed_count     INT NOT NULL DEFAULT 0,
    tradeoff_count      INT NOT NULL DEFAULT 0,
    tie_count           INT NOT NULL DEFAULT 0,
    unknown_count       INT NOT NULL DEFAULT 0,
    status              VARCHAR(16) NOT NULL,
    created_by          VARCHAR(64),
    created_at          DATETIME(3) NOT NULL,
    KEY idx_playground_diff_matrix (matrix_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_playground_diff_item (
    id              VARCHAR(36) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    diff_id         VARCHAR(36) NOT NULL,
    item_id         VARCHAR(36) NOT NULL,
    deterministic   VARCHAR(16) NOT NULL,
    preference      VARCHAR(16) NOT NULL,
    reason_code     VARCHAR(64),
    reason          VARCHAR(512),
    score_delta_json TEXT,
    calls_json      MEDIUMTEXT,
    UNIQUE KEY uk_playground_diff_item (diff_id, item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============ R1-3 Prompt 版本 × 实验打通 / R1-4 CI 基线 ============
ALTER TABLE et_dataset_run ADD COLUMN prompt_name VARCHAR(128);
ALTER TABLE et_dataset_run ADD COLUMN prompt_version INT;
ALTER TABLE et_dataset_run ADD COLUMN prompt_label VARCHAR(32);
ALTER TABLE et_dataset_run ADD COLUMN prompt_hash CHAR(64);
ALTER TABLE et_dataset_run ADD COLUMN run_label VARCHAR(64);
ALTER TABLE et_dataset_run ADD COLUMN commit_sha VARCHAR(64);
CREATE INDEX idx_dataset_run_label ON et_dataset_run (workspace_id, dataset_id, run_label);

CREATE TABLE IF NOT EXISTS et_prompt_resolution (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    label         VARCHAR(32) NOT NULL,
    version       INT NOT NULL,
    prompt_hash   CHAR(64),
    source        VARCHAR(32),
    requested_by  VARCHAR(64),
    created_at    DATETIME(3) NOT NULL,
    KEY idx_prompt_resolution_name (workspace_id, name, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============ R2-1 评估规则 v2（scope / filter / 回溯） ============
CREATE TABLE IF NOT EXISTS et_eval_rule (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    rule_key          VARCHAR(100) NOT NULL,
    name              VARCHAR(200),
    enabled           TINYINT(1) NOT NULL DEFAULT 1,
    scope             VARCHAR(16) NOT NULL DEFAULT 'TRACE',
    filters_json      TEXT,
    evaluator_ids_json TEXT NOT NULL,
    sample_rate       DOUBLE NOT NULL DEFAULT 1.0,
    error_forced      TINYINT(1) NOT NULL DEFAULT 1,
    max_per_hour      INT NOT NULL DEFAULT 0,
    session_idle_minutes INT NOT NULL DEFAULT 5,
    priority          INT NOT NULL DEFAULT 100,
    created_by        VARCHAR(64),
    created_at        DATETIME(3) NOT NULL,
    updated_at        DATETIME(3) NOT NULL,
    UNIQUE KEY uk_eval_rule (workspace_id, rule_key),
    KEY idx_eval_rule_scope (workspace_id, enabled, scope)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_eval_rule_hit (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    rule_key      VARCHAR(100) NOT NULL,
    scope         VARCHAR(16) NOT NULL,
    subject_id    VARCHAR(64) NOT NULL,
    decision      VARCHAR(24) NOT NULL,
    eval_count    INT NOT NULL DEFAULT 0,
    detail        VARCHAR(500),
    created_at    DATETIME(3) NOT NULL,
    UNIQUE KEY uk_eval_rule_hit (rule_key, subject_id),
    KEY idx_eval_rule_hit_budget (rule_key, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_eval_rewind_job (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    request_key       VARCHAR(128) NOT NULL,
    rule_key          VARCHAR(100) NOT NULL,
    filter_json       TEXT,
    status            VARCHAR(16) NOT NULL,
    rate_per_minute   INT NOT NULL DEFAULT 60,
    total             INT NOT NULL DEFAULT 0,
    processed         INT NOT NULL DEFAULT 0,
    evaluated         INT NOT NULL DEFAULT 0,
    deduped           INT NOT NULL DEFAULT 0,
    failed            INT NOT NULL DEFAULT 0,
    cursor_start_time DATETIME(3),
    cursor_trace_id   VARCHAR(64),
    error             VARCHAR(1000),
    created_by        VARCHAR(64),
    created_at        DATETIME(3) NOT NULL,
    started_at        DATETIME(3),
    finished_at       DATETIME(3),
    UNIQUE KEY uk_eval_rewind_request (workspace_id, request_key),
    KEY idx_eval_rewind_status (workspace_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE et_eval_result ADD COLUMN scope VARCHAR(16);
ALTER TABLE et_eval_result ADD COLUMN session_id VARCHAR(64);
ALTER TABLE et_eval_result ADD COLUMN rule_key VARCHAR(100);
CREATE INDEX idx_eval_result_scope ON et_eval_result (workspace_id, scope, evaluator_key, created_at);
