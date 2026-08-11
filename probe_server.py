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

ps = r'''
$ErrorActionPreference = 'SilentlyContinue'

Write-Host "=== 1. System Info ==="
Get-CimInstance Win32_OperatingSystem | Format-List Caption, Version, OSArchitecture, TotalVisibleMemorySize

Write-Host "=== 2. Disk Space ==="
Get-PSDrive -PSProvider FileSystem | Format-Table Name, @{N='UsedGB';E={[math]::Round($_.Used/1GB,2)}}, @{N='FreeGB';E={[math]::Round($_.Free/1GB,2)}} -AutoSize

Write-Host "=== 3. Java ==="
$javaVer = & java -version 2>&1 | Out-String
if ($javaVer) { Write-Host $javaVer } else { Write-Host "Java NOT in PATH" }
Write-Host "JAVA_HOME = $($env:JAVA_HOME)"
Get-Command java -ErrorAction SilentlyContinue | Select-Object Source

Write-Host "=== 4. MySQL Service ==="
Get-Service *mysql* | Format-Table Name, Status, DisplayName -AutoSize
$mysqlPorts = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(3306,3308,3307,3309,3310) }
if ($mysqlPorts) { $mysqlPorts | Format-Table LocalPort, OwningProcess -AutoSize } else { Write-Host "MySQL port not listening" }

Write-Host "=== 5. Listening Ports (<60000, top 80) ==="
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Sort-Object LocalPort | Select-Object LocalPort, OwningProcess -Unique | Where-Object { $_.LocalPort -lt 60000 } | Select-Object -First 80 | Format-Table -AutoSize

Write-Host "=== 6. Key Processes ==="
Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -in @('java','mysqld','nginx','node','redis-server','mongod','python') } | Format-Table Name, Id, @{N='MemMB';E={[math]::Round($_.WorkingSet/1MB,2)}}, Path -AutoSize

Write-Host "=== 7. Deploy Dirs ==="
$candidates = @('C:\deploy','C:\projects','C:\apps','C:\www','C:\services','C:\xampp','C:\wamp64','D:\','D:\deploy','D:\projects','D:\apps','C:\Users\Administrator\Desktop','C:\Users\Administrator')
foreach ($d in $candidates) {
    if (Test-Path $d) {
        Write-Host "--- $d ---"
        Get-ChildItem $d -Directory -ErrorAction SilentlyContinue | Select-Object -First 15 Name
    }
}
'''

result = session.run_ps(ps)
print("=== STDOUT ===")
print(result.std_out.decode('utf-8', errors='ignore'))
stderr = result.std_err.decode('utf-8', errors='ignore').strip()
if stderr:
    print("\n=== STDERR ===")
    print(stderr[:3000])
print(f"\nExit: {result.status_code}")
