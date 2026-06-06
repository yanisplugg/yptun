$ErrorActionPreference = "Continue"
$src = "C:\Users\Stanislav\Desktop\NPU\olcvpn-client\zygisk-vpnhide\module"
$dst = "C:\Users\Stanislav\Desktop\NPU\olcvpn-client\zygisk-vpnhide\olcvpnhide.zip"
$adb = "C:\Android\sdk\platform-tools\adb.exe"

if (Test-Path $dst) { Remove-Item $dst }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($dst, 'Create')
function AddEntry($f, $n) { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f, $n, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null }
AddEntry "$src\module.prop" "module.prop"
AddEntry "$src\zygisk\arm64-v8a.so" "zygisk/arm64-v8a.so"
AddEntry "$src\zygisk\armeabi-v7a.so" "zygisk/armeabi-v7a.so"
$zip.Dispose()

& $adb push $dst "/data/local/tmp/olcvpnhide.zip" | Out-Null
& $adb shell "su -c 'magisk --install-module /data/local/tmp/olcvpnhide.zip'" | Select-Object -Last 1
& $adb logcat -c 2>$null
& $adb reboot
& $adb wait-for-device
for ($i = 0; $i -lt 80; $i++) {
    $b = (& $adb shell getprop sys.boot_completed 2>$null)
    if ($b -ne $null) { $b = "$b".Trim() }
    if ($b -eq "1") { Write-Output "BOOTED ~$($i*3)s"; break }
    Start-Sleep -Seconds 3
}
Start-Sleep -Seconds 5
# Recreate a dummy VPN-looking interface so we can prove the hook hides it.
& $adb shell "su -c 'ip tuntap add dev tun_olctest mode tun 2>/dev/null; ip addr add 10.9.9.9/24 dev tun_olctest 2>/dev/null; ip link set tun_olctest up; echo dummy: ; ip -br addr show tun_olctest'"
Write-Output "=== launch chrome (hooked, self-test) ==="
& $adb shell "am force-stop com.android.chrome"
& $adb shell "am start -n com.android.chrome/com.google.android.apps.chrome.Main" | Out-Null
Start-Sleep -Seconds 6
Write-Output "=== java self-test logs ==="
& $adb shell "su -c 'logcat -d -s olcvpnhide'" | Select-String -Pattern "java self-test|commit" | Select-Object -Last 20
