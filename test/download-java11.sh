#!/bin/bash

# Java 11 下载脚本
# 用于下载 Oracle JDK 11.0.50 并解压到指定目录

echo "=========================================="
echo "Java 11 下载和安装"
echo "=========================================="

# 目标目录
JAVA_DIR="/e/DataFiles/Java"
JDK_VERSION="11.0.50"
JDK_DIR="jdk-${JDK_VERSION}"

echo ""
echo "目标目录: $JAVA_DIR"
echo "JDK 版本: $JDK_VERSION"
echo ""

# 检查目录是否存在
if [ ! -d "$JAVA_DIR" ]; then
    echo "❌ 目录不存在: $JAVA_DIR"
    exit 1
fi

echo "📌 下载说明："
echo ""
echo "由于 Oracle JDK 需要登录才能下载，请手动下载："
echo ""
echo "1. 访问 Oracle JDK 下载页面："
echo "   https://www.oracle.com/java/technologies/javase/jdk11-archive-downloads.html"
echo ""
echo "2. 下载以下文件之一："
echo "   - Windows x64 Compressed Archive (jdk-11.0.50_windows-x64_bin.zip)"
echo "   - 或者使用 OpenJDK 11 (推荐)："
echo "     https://adoptium.net/temurin/releases/?version=11"
echo ""
echo "3. 解压到目录: $JAVA_DIR"
echo ""
echo "=========================================="
echo "推荐使用 OpenJDK 11 (Temurin)"
echo "=========================================="
echo ""
echo "下载命令 (使用 curl):"
echo ""
echo "# 下载 OpenJDK 11 (Temurin)"
echo "curl -L -o /tmp/openjdk11.zip 'https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.22%2B7/OpenJDK11U-jdk_x64_windows_hotspot_11.0.22_7.zip'"
echo ""
echo "# 解压到 Java 目录"
echo "unzip /tmp/openjdk11.zip -d '$JAVA_DIR/'"
echo ""
echo "# 重命名目录"
echo "mv '$JAVA_DIR/jdk-11.0.22+7' '$JAVA_DIR/jdk-11'"
echo ""
echo "=========================================="
echo ""
echo "或者，如果你已经下载了 JDK 11 压缩包："
echo ""
echo "1. 将压缩包放到 /tmp 目录"
echo "2. 运行以下命令解压："
echo ""
echo "   unzip /tmp/jdk-11.0.50_windows-x64_bin.zip -d '$JAVA_DIR/'"
echo ""
echo "=========================================="
