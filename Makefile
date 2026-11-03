# EmonLens 常用命令（make <target>）
SHELL := /bin/bash

VERSION := 0.2.0

.PHONY: help ui build test sdk-test sdk-dist dist image image-tag image-offline release smoke verify version clean \
        up down logs ps

help:
	@echo "make ui        - 构建 UI 并内嵌到 server static"
	@echo "make build     - 本地构建 server fat jar（含 UI）"
	@echo "make test      - 运行后端全量测试"
	@echo "make sdk-test  - 运行 Python SDK 测试"
	@echo "make sdk-dist  - 构建 Python SDK sdist/wheel 到 emonlens-sdk-python/dist/"
	@echo "make dist      - 构建发布分发件 tar.gz（fat jar + 启停脚本 + SQL + 配置样例 + SDK + 文档）"
	@echo "make image     - 构建生产 Docker 镜像（无需 .env；网络不通时用 make image-offline）"
	@echo "make release   - 发布：全量测试 + fat jar + 镜像 tag + 分发件 $(VERSION)"
	@echo "make smoke     - 对运行中的实例冒烟（密码必传：make smoke PASS=<密码>；默认 http://127.0.0.1:8300）"
	@echo "make verify    - 全功能黑盒验证（密码必传：make verify PASS=<密码>）"
	@echo "make version   - 打印当前版本"
	@echo "make up        - docker compose 启动生产栈（需先 cp .env.example .env 并填写必填项）"
	@echo "make down     - 停止生产栈（保留数据卷）"
	@echo "make logs      - 跟随 server 日志"
	@echo "make ps        - 查看栈状态（未建 .env 也能用）"
	@echo "make clean     - 清理构建产物"

ui:
	cd emonlens-ui && npm ci --no-audit --no-fund && npm run build

build: ui
	mvn -q -B clean package -DskipTests
	@echo "jar: emonlens-server/target/emonlens-server-$(VERSION).jar"

test:
	mvn -q -B test

image:
	docker build $(DOCKER_BUILD_ARGS) -t emonlens/server:$(VERSION) -t emonlens/server:latest .

image-tag:
	docker build $(DOCKER_BUILD_ARGS) -t emonlens/server:$(VERSION) -t emonlens/server:latest .

# 离线镜像构建：直接装配宿主机已打好的 jar（构建机无法访问 Maven/npm 仓库时使用）
image-offline:
	cp emonlens-server/target/emonlens-server-$(VERSION).jar ./emonlens-server-$(VERSION).jar
	docker build $(DOCKER_BUILD_ARGS) --build-arg BUILD_STAGE=hostjar \
		-t emonlens/server:$(VERSION) -t emonlens/server:latest .
	rm -f ./emonlens-server-$(VERSION).jar

sdk-test:
	cd emonlens-sdk-python && test -x .venv/bin/python || python3 -m venv .venv
	cd emonlens-sdk-python && .venv/bin/pip install --quiet -e . pytest
	cd emonlens-sdk-python && .venv/bin/python -m pytest tests/ -q

sdk-dist:
	cd emonlens-sdk-python && rm -rf dist build *.egg-info src/*.egg-info
	cd emonlens-sdk-python && test -x .venv/bin/python || python3 -m venv .venv
	# 安装构建依赖并关闭 build 隔离：避免每次打包都在临时 env 里重复解析/下载 setuptools
	cd emonlens-sdk-python && .venv/bin/pip install --quiet --upgrade build setuptools wheel
	cd emonlens-sdk-python && .venv/bin/python -m build --sdist --wheel --no-isolation
	@echo "dist: emonlens-sdk-python/dist/"

# 发布分发件（无 Docker 的单机部署形态）：UI 内嵌 → SDK 打包 → tar.gz
dist: ui sdk-dist
	mvn -q -B -Prelease clean package -DskipTests
	@echo "dist: emonlens-dist/target/emonlens-$(VERSION)-bin.tar.gz"

# dist 会完成 UI 构建、SDK 打包、Spring Boot 打包并生成 tar.gz；随后用该 jar 构建 offline 镜像，
# 避免 release 过程再次依赖 Docker 内 npm/Maven 远程下载。
release: test sdk-test dist image-offline
	@echo "== 发布完成 $(VERSION) =="
	@echo "jar:  emonlens-server/target/emonlens-server-$(VERSION).jar"
	@echo "dist: emonlens-dist/target/emonlens-$(VERSION)-bin.tar.gz"
	@echo "镜像: emonlens/server:$(VERSION)（含 latest）"
	@echo "SDK:  emonlens-sdk-python/dist/（发布到 PyPI）"
	@echo "冒烟: make smoke（服务启动后执行）"

# 登录名用 ADMIN_USER 而非 USER：USER 是 POSIX 环境变量（= 系统用户名），
# $(or $(USER),admin) 永远解析成当前系统用户名，导致 make smoke 拿它当登录名去认证。
smoke:
	@echo "（密码经 make smoke PASS=<密码> 传入；忘记密码可在 .env 设 EMONLENS_ADMIN_PASSWORD 后重启轮换）"
	@bash scripts/smoke.sh $(or $(BASE),http://127.0.0.1:8300) $(or $(ADMIN_USER),admin) $(PASS)

# 全功能黑盒验证（84 项，幂等可重复；发布验收用）
verify:
	@python3 scripts/verify_all.py $(or $(BASE),http://127.0.0.1:8300) $(or $(ADMIN_USER),admin) $(PASS)

version:
	@echo "$(VERSION)"

up:
	@bash scripts/compose.sh up -d --build
	@echo "服务地址: http://localhost:$$(grep -E '^EMONLENS_PORT=' .env | cut -d= -f2 || echo 8300)"

# ps/logs/down 走脚本走 .env 兜底：compose 的 :? 必填变量在没建 .env 时，
# 会让只读命令也一起插值失败。凭据校验仍在 up 一侧（含 .env 缺失检查）。
down:
	@bash scripts/compose.sh down

logs:
	@bash scripts/compose.sh logs -f emonlens

ps:
	@bash scripts/compose.sh ps

clean:
	mvn -q clean
	rm -rf emonlens-dist/target
	rm -rf emonlens-ui/node_modules emonlens-server/src/main/resources/static/assets
	rm -rf emonlens-sdk-python/dist emonlens-sdk-python/build emonlens-sdk-python/src/emonlens.egg-info
