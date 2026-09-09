@echo off
REM NeoForge 1.21.1 模组开发环境启动脚本
set JAVA_HOME=C:\Program Files\Java\jdk-21
set PATH=%JAVA_HOME%\bin;%PATH%

echo ========================================
echo   Minecraft 1.21.1 NeoForge 开发环境
echo ========================================
echo.
echo Java版本:
java -version
echo.
echo Gradle任务列表:
echo   runClient      - 启动Minecraft客户端测试
echo   runServer      - 启动专用服务器测试
echo   runData        - 运行数据生成器
echo   build          - 构建模组JAR文件
echo   idea           - 生成IntelliJ IDEA项目文件
echo.
echo 当前目录: %cd%
echo.
echo 输入 gradlew 任务名 来执行，例如: gradlew runClient
echo.
cmd /k
