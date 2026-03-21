# StreamPark Dynamic Properties 快速参考

## 🎯 正确配置格式

在 StreamPark Web UI 的 **Dynamic Properties** 字段中输入：

```
-DAPP_ENV=docker
```

## 📋 配置步骤

1. 登录 StreamPark: http://localhost:10000
   - 用户名：`admin`
   - 密码：`streampark`

2. 进入 **Application** → 选择你的应用

3. 点击 **Edit** 编辑应用配置

4. 找到 **Dynamic Properties** 字段

5. 输入：`-DAPP_ENV=docker`

6. 点击 **Submit** 保存

7. 点击 **Start** 启动作业

## ✅ 验证方法

查看作业日志，搜索 `=== 诊断信息 ===`

**成功标志**:
```
System Property APP_ENV: docker
Loading configuration for environment: docker
Bootstrap Servers: kafka:9092
```

**失败标志**:
```
Loading configuration for environment: dev
Bootstrap Servers: localhost:9092
```

## 🔧 多个属性配置

每行一个属性：
```
-DAPP_ENV=docker
-Dparallelism.default=4
-Dtaskmanager.numberOfTaskSlots=2
```

或用空格分隔：
```
-DAPP_ENV=docker -Dparallelism.default=4
```

## 📚 更多信息

详细文档：`docs/202603212115-StreamPark配置-Dynamic-Properties正确格式说明.md`
