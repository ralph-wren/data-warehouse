#!/bin/bash

# Java 11 快速安装脚本
# 自动下载并安装 OpenJDK 11 (Temurin)

echo "=========================================="
echo "Java 11 快速安装"
echo "=========================================="

# 配置
JAVA_DIR="/e/DataFiles/Java"
JDK_NAME="jdk-11"
DOWNLOAD_URL="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.22%2B7/OpenJDK11U-jdk_x64_windows_hotspot_11.0.22_7.zip"
TEMP_FILE="/tmp/openjdk11.zip"

echo ""
echo "目标目录: $JAVA_DIR/$JDK_NAME"
echo "下载地址: $DOWNLOAD_URL"
echo ""

# 检查目标目录是否存在
if [ ! -d "$JAVA_DIR" ]; then
    echo "❌ 目录不存在: $JAVA_DIR"
    echo ""
    echo "请先创建目录:"
    echo "  mkdir -p '$JAVA_DIR'"
    exit 1
fi

# 检查 Java 11 是否已安装
if [ -d "$JAVA_DIR/$JDK_NAME" ]; then
    echo "⚠️  Java 11 已存在: $JAVA_DIR/$JDK_NAME"
    echo ""
    read -p "是否覆盖安装? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "取消安装"
        exit 0
    fi
    echo ""
    echo "删除旧版本..."
    rm -rf "$JAVA_DIR/$JDK_NAME"
fi

# 下载 OpenJDK 11
echo "=========================================="
echo "1. 下载 OpenJDK 11"
echo "=========================================="
echo ""

if [ -f "$TEMP_FILE" ]; then
    echo "⚠️  临时文件已存在: $TEMP_FILE"
    read -p "是否重新下载? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        rm -f "$TEMP_FILE"
    else
        echo "使用现有文件"
    fi
fi

if [ ! -f "$TEMP_FILE" ]; then
    echo "正在下载 OpenJDK 11..."
    echo "文件大小: 约 180 MB"
    echo ""
    
    if command -v curl &> /dev/null; then
        curl -L -o "$TEMP_FILE" "$DOWNLOAD_URL"
    elif command -v wget &> /dev/null; then
        wget -O "$TEMP_FILE" "$DOWNLOAD_URL"
    else
        echo "❌ 未找到 curl 或 wget 命令"
        echo ""
        echo "请手动下载:"
        echo "  $DOWNLOAD_URL"
        echo ""
        echo "并保存到:"
        echo "  $TEMP_FILE"
        exit 1
    fi
    
    if [ $? -ne 0 ]; then
        echo "❌ 下载失败"
        exit 1
    fi
    
    echo ""
    echo "✅ 下载完成"
fi

# 解压
echo ""
echo "=========================================="
echo "2. 解压 OpenJDK 11"
echo "=========================================="
echo ""

echo "正在解压到: $JAVA_DIR"
echo ""

if ! command -v unzip &> /dev/null; then
    echo "❌ 未找到 unzip 命令"
    echo ""
    echo "请安装 unzip:"
    echo "  # Windows (Git Bash)"
    echo "  pacman -S unzip"
    echo ""
    echo "  # Ubuntu/Debian"
    echo "  sudo apt-get install unzip"
    exit 1
fi

unzip -q "$TEMP_FILE" -d "$JAVA_DIR/"

if [ $? -ne 0 ]; then
    echo "❌ 解压失败"
    exit 1
fi

echo "✅ 解压完成"

# 重命名目录
echo ""
echo "=========================================="
echo "3. 重命名目录"
echo "=========================================="
echo ""

# 查找解压后的目录名
EXTRACTED_DIR=$(find "$JAVA_DIR" -maxdepth 1 -type d -name "jdk-11*" | head -1)

if [ -z "$EXTRACTED_DIR" ]; then
    echo "❌ 未找到解压后的目录"
    exit 1
fi

echo "原目录: $EXTRACTED_DIR"
echo "新目录: $JAVA_DIR/$JDK_NAME"
echo ""

mv "$EXTRACTED_DIR" "$JAVA_DIR/$JDK_NAME"

if [ $? -ne 0 ]; then
    echo "❌ 重命名失败"
    exit 1
fi

echo "✅ 重命名完成"

# 验证安装
echo ""
echo "=========================================="
echo "4. 验证安装"
echo "=========================================="
echo ""

if [ ! -f "$JAVA_DIR/$JDK_NAME/bin/java" ]; then
    echo "❌ Java 可执行文件不存在"
    exit 1
fi

JAVA_VERSION=$("$JAVA_DIR/$JDK_NAME/bin/java" -version 2>&1 | head -1)
echo "Java 版本: $JAVA_VERSION"

if echo "$JAVA_VERSION" | grep -q "11\."; then
    echo "✅ Java 11 安装成功"
else
    echo "⚠️  Java 版本可能不正确"
fi

# 清理临时文件
echo ""
echo "=========================================="
echo "5. 清理临时文件"
echo "=========================================="
echo ""

read -p "是否删除临时文件? (Y/n): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Nn]$ ]]; then
    rm -f "$TEMP_FILE"
    echo "✅ 临时文件已删除"
else
    echo "保留临时文件: $TEMP_FILE"
fi

# 完成
echo ""
echo "=========================================="
echo "✅ 安装完成"
echo "=========================================="
echo ""
echo "Java 11 安装路径: $JAVA_DIR/$JDK_NAME"
echo ""
echo "📌 下一步操作:"
echo ""
echo "1. 验证 Java 11:"
echo "   $JAVA_DIR/$JDK_NAME/bin/java -version"
echo ""
echo "2. 重启 StreamPark 容器:"
echo "   docker-compose -f docker-compose-streampark.yml down"
echo "   docker-compose -f docker-compose-streampark.yml up -d"
echo ""
echo "3. 验证 StreamPark Java 11 配置:"
echo "   bash test/verify-streampark-java11.sh"
echo ""
echo "4. 更新项目 pom.xml 使用 Java 11:"
echo "   <maven.compiler.source>11</maven.compiler.source>"
echo "   <maven.compiler.target>11</maven.compiler.target>"
echo ""
