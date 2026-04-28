FROM php:8.2-apache

ARG REPORTICO_VERSION=8.1.0
ARG REPORTICO_ZIP_URL="https://github.com/reportico-web/reports/releases/download/${REPORTICO_VERSION}/reportico-${REPORTICO_VERSION}.zip"

# 说明：
# - Reportico 是 MIT 许可开源报表工具，此镜像用于“帆软开源替代”本地部署。
# - 安装包来源：GitHub release zip。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip libpq-dev \
    && docker-php-ext-install pdo pdo_mysql pdo_pgsql \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/reportico.zip "${REPORTICO_ZIP_URL}" \
    && rm -rf /tmp/reportico-src \
    && mkdir -p /tmp/reportico-src \
    && unzip -q /tmp/reportico.zip -d /tmp/reportico-src \
    && REPORTICO_DIR="$(find /tmp/reportico-src -mindepth 1 -maxdepth 1 -type d | head -n 1)" \
    && rm -rf /var/www/html/reportico \
    && mv "${REPORTICO_DIR}" /var/www/html/reportico \
    && chown -R www-data:www-data /var/www/html/reportico \
    && rm -rf /tmp/reportico.zip /tmp/reportico-src

EXPOSE 80
