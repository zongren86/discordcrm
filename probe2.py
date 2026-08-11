import winrm

host = "120.196.70.142"
user = "Administrator"
password = "36085688Zyt"

session = winrm.Session(
    f'http://{host}:5985/wsman',
    auth=(user, password),
    transport='ntlm',
    server_cert_validation='ignore'
)

# 探测MySQL安装目录、已有数据库、密码
ps = r'''
Write-Host "=== MySQL Bin ==="
$mysqlBin = Get-ChildItem "C:\mysql" -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue | Select-Object -First 1 FullName
if ($mysqlBin) { $mysqlBin.FullName } else { Write-Host "mysql.exe not in C:\mysql, searching PATH..." ; Get-Command mysql -ErrorAction SilentlyContinue | Select-Object Source }

Write-Host "=== MySQL my.ini ==="
$iniFiles = Get-ChildItem "C:\mysql","C:\" -Recurse -Filter my.ini -ErrorAction SilentlyContinue 2>$null
if ($iniFiles) { $iniFiles | Select-Object FullName } else { "no my.ini found"}

Write-Host "=== Try MySQL no password ==="
$mysqlPath = ""
if (Test-Path "C:\mysql\mysql\current\bin\mysql.exe") { $mysqlPath = "C:\mysql\mysql\current\bin\mysql.exe" }
elseif (Test-Path "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe") { $mysqlPath = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" }
else { $mysqlPath = "mysql" }

Write-Host "Using mysql path: $mysqlPath"

# 尝试几种常见密码
$passwords = @('', 'root', '123456', 'Discord123!', 'admin', 'password', '12345678', 'Root@123', 'mysql')
foreach ($pw in $passwords) {
    $args = @("-uroot", "-p$pw", "-e", "SHOW DATABASES;")
    try {
        $proc = Start-Process -FilePath $mysqlPath -ArgumentList $args -NoNewWindow -Wait -RedirectStandardOutput "/tmp/mysql_out.txt" -RedirectStandardError "/tmp/mysql_err.txt" -PassThru
        if ($proc.ExitCode -eq 0) {
            Write-Host "SUCCESS: password=[$pw]"
            Get-Content "/tmp/mysql_out.txt"
            break
        }
    } catch {
        # fallback to cmd style
    }
}
'''
result = session.run_cmd('cmd', ['/c', 'echo test'])
out = result.std_out.decode()

# 改用 cmd方式测试mysql密码
mysql_candidates = [
    r'C:\mysql\mysql\current\bin\mysql.exe',
    r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    r'mysql'
]
passwords = ['', 'root', '123456', 'Discord123!', 'admin', 'password', '12345678', 'Root@123', 'mysql', '1234567890', 'Abc123456', 'Admin@123']
for mp in mysql_candidates:
    for pw in passwords:
        args = [mp, '-uroot', f'-p{pw}', '-e', 'SELECT 1;']
        r = session.run_cmd('cmd', ['/c'] + args)
        stdout = r.std_out.decode('utf-8', errors='ignore').strip()
        stderr = r.std_err.decode('utf-8', errors='ignore').strip()
        if 'ERROR' not in stderr and 'Access denied' not in stderr:
            print(f"✅ MySQL OK: path={mp}, password=[{pw}]")
            print(f"  stdout: {stdout[:200]}")
            # 列出数据库
            r2 = session.run_cmd('cmd', ['/c', mp, '-uroot', f'-p{pw}', '-e', 'SHOW DATABASES;'])
            print(f"  databases: {r2.std_out.decode('utf-8', errors='ignore').strip()}")
            exit(0)
        elif 'Access denied' in stderr:
            print(f"  ❌ Access denied: pw=[{pw}]")
print("所有密码均失败，请查看更详细的MySQL配置")

# 查找 my.ini
r3 = session.run_cmd('cmd', ['/c', 'dir /s /b C:\my.ini 2>nul & dir /s /b C:\mysql\*.ini 2>nul & dir /s /b "C:\Program Files\MySQL\*.ini" 2>nul'])
print(f"my.ini search: {r3.std_out.decode(errors='ignore')[:1000]}")

# 检查是否是wamp/xampp
r4 = session.run_cmd('cmd', ['/c', 'dir C:\wamp64 2>nul & dir C:\xampp 2>nul & dir C:\phpstudy 2>nul'])
print(f"WAMP/XAMPP: {r4.std_out.decode(errors='ignore')[:500]}")
