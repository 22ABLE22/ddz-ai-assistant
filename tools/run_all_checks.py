# -*- coding: utf-8 -*-
"""
一键回归：手机端 DouZero 移植的完整验收。

依次执行：
  1. 导出权重 + 生成黄金向量（tools/export_android.py）
  2. 校验导出权重与 PyTorch 逐位一致（tools/verify_export.py）
  3. JVM 编译主源码 + 测试
  4. GoldenTest      —— forward 数值 + 特征编码
  5. EngineParityTest —— 引擎推理路径 + 规则判定
  6. RealEngineTest  —— 真实 DdzEngine 端到端 + 状态机/撤销/记牌器
  7. Gradle 构建 APK 并核验内容

用法：python tools/run_all_checks.py
"""
import os, subprocess, sys, json, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_HOME = r'C:\Android\jdk21\jdk-21.0.12.1+1'
ANDROID_SDK = r'C:\Android\Sdk'
GRADLE = r'C:\Android\gradle-9.6.0\bin\gradle.bat'
ANDROID_JAR = os.path.join(ANDROID_SDK, 'platforms', 'android-37.0', 'android.jar')
OUT = os.path.join(os.environ.get('TEMP', '.'), 'ddz-jvm-classes')
APK = os.path.join(ROOT, 'android-offline', 'app', 'build', 'outputs',
                   'apk', 'debug', 'app-debug.apk')

steps = []


def run(name, cmd, cwd=ROOT, env=None, show_tail=0, must_contain=None,
        must_not_contain=None):
    e = dict(os.environ)
    e['PYTHONIOENCODING'] = 'utf-8'
    # Java 在 Windows 上默认用控制台代码页写 stdout，会破坏中文输出；
    # 显式指定 stdout 编码，保证捕获到的文本可读、可断言。
    e['JAVA_TOOL_OPTIONS'] = ('-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 '
                              '-Dstderr.encoding=UTF-8')
    if env:
        e.update(env)
    print(f'\n{"=" * 74}\n{name}\n{"=" * 74}')
    p = subprocess.run(cmd, cwd=cwd, env=e, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    out = (p.stdout or '') + (p.stderr or '')
    if show_tail:
        print('\n'.join(out.splitlines()[-show_tail:]))
    # 以退出码为准（测试内部用 System.exit(1) 表示失败）
    ok = p.returncode == 0
    if ok and must_contain:
        for token in must_contain:
            if token not in out:
                ok = False
                print(f'  [缺少预期输出] {token}')
    if ok and must_not_contain:
        for token in must_not_contain:
            if token in out:
                ok = False
                print(f'  [出现不应有的输出] {token}')
    steps.append((name, ok, p.returncode))
    return out, ok


def java_files(sub):
    base = os.path.join(ROOT, 'android-offline', sub)
    files = []
    for dirpath, _, names in os.walk(base):
        for n in names:
            if n.endswith('.java'):
                files.append(os.path.join(dirpath, n))
    return files


def main():
    py = sys.executable

    # 1. 导出权重 + 黄金向量
    run('1) 导出权重与黄金向量', [py, 'tools/export_android.py'],
        show_tail=6, must_contain=['完成。'])

    # 2. 权重逐位校验
    out, ok = run('2) 校验导出权重 vs PyTorch', [py, 'tools/verify_export.py'],
                  show_tail=10)
    if ok and '96/96' not in out:
        steps[-1] = (steps[-1][0], False, 0)
        print('  [警告] 张量校验未达到 96/96')

    # 3. 编译
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        fp = os.path.join(OUT, f)
        if os.path.isdir(fp):
            import shutil
            shutil.rmtree(fp, ignore_errors=True)
        else:
            os.remove(fp)
    srcs = java_files('app/src/main/java') + java_files('jvmtest/java')
    run(f'3) JVM 编译（{len(srcs)} 个源文件）',
        ['javac', '-encoding', 'UTF-8', '-cp', ANDROID_JAR, '-d', OUT] + srcs,
        show_tail=8)

    # 4-9. 六个测试
    run('4) GoldenTest（forward + 特征编码）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.GoldenTest', ROOT],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('5) EngineParityTest（推理路径 + 规则）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.EngineParityTest', ROOT],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('6) RealEngineTest（真实引擎端到端）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.RealEngineTest', ROOT],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('7) LaiziTest（赖子规则）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.LaiziTest'],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('8) LaiziEngineTest（赖子模式引擎端到端）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.LaiziEngineTest', ROOT],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('9) CardInputTest（输入解析与一致性）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.CardInputTest'],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('10) BombTierTest（炸弹四分档位）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.BombTierTest'],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('11) LaiziConcretizeTest（具体化正确性）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.LaiziConcretizeTest', ROOT],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('12) LaiziReasoningTest（赖子推理能力实证）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.LaiziReasoningTest', ROOT],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('13) McSearchTest（蒙特卡洛搜索移植）',
        ['java', '-cp', OUT, 'com.example.ddzoffline.ai.McSearchTest', ROOT],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    run('14) HandRecognizerTest（手牌识别）',
        ['java', '-Djava.awt.headless=true', '-cp', OUT,
         'com.example.ddzoffline.ai.HandRecognizerTest'],
        show_tail=5, must_contain=['0 \u5931\u8d25'], must_not_contain=['FAIL'])

    # 15. 构建 APK
    run('15) Gradle 构建 APK',
        [GRADLE, 'assembleDebug', '--offline', '--console=plain'],
        cwd=os.path.join(ROOT, 'android-offline'),
        env={'ANDROID_HOME': ANDROID_SDK, 'ANDROID_SDK_ROOT': ANDROID_SDK,
             'JAVA_HOME': JAVA_HOME},
        show_tail=4, must_contain=['BUILD SUCCESSFUL'])

    # 16. APK 内容核验
    print(f'\n{"=" * 74}\n16) APK 内容核验\n{"=" * 74}')
    apk_ok = os.path.exists(APK)
    if apk_ok:
        with zipfile.ZipFile(APK) as z:
            names = z.namelist()
            models = [n for n in names if n.startswith('assets/ddz_')]
            leaks = [n for n in names if any(t in n for t in
                     ('GoldenTest', 'EngineParityTest', 'RealEngineTest',
                      'LaiziTest', 'LaiziEngineTest', 'CardInputTest',
                      'BombTierTest', 'LaiziConcretizeTest', 'LaiziReasoningTest',
                      'McSearchTest', 'HandRecognizerTest', 'MiniJson', 'CardEngine'))]
            dex = [n for n in names if n.endswith('.dex')]
            signed = any(n.startswith('META-INF/') and n.endswith('.RSA') for n in names)
        size_mb = os.path.getsize(APK) / 1024 / 1024
        print(f'  路径: {os.path.relpath(APK, ROOT)}')
        print(f'  体积: {size_mb:.2f} MB')
        print(f'  模型: {len(models)} 个')
        print(f'  dex : {len(dex)} 个')
        print(f'  签名: {"已签名" if signed else "未签名"}')
        print(f'  测试类泄漏: {"无" if not leaks else leaks}')
        apk_ok = len(models) == 7 and not leaks and len(dex) > 0
        steps.append(('16) APK 内容核验', apk_ok, 0))
    else:
        steps.append(('16) APK 内容核验', False, 1))
        print('  APK 不存在')

    # 汇总
    print(f'\n{"=" * 74}\n汇总\n{"=" * 74}')
    for name, ok, rc in steps:
        print(f'  {"PASS" if ok else "FAIL"}  {name}')
    failed = [n for n, ok, _ in steps if not ok]
    print(f'\n  {len(steps) - len(failed)}/{len(steps)} 步骤通过')
    if failed:
        print('  失败步骤:')
        for n in failed:
            print('    - ' + n)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
