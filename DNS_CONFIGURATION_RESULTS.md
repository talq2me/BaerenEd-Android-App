# DNS Configuration Test Results

## Current Status
- **Emulator Running**: Yes (emulator-5554)
- **Android Version**: 15
- **Current DNS**: 10.0.2.3 (default emulator DNS)
- **Internet Connectivity**: ❌ NOT WORKING
  - Cannot ping 8.8.8.8 (100% packet loss)
  - Cannot resolve DNS (unknown host google.com)

## Problem Identified
The emulator is using the default DNS server (10.0.2.3) which forwards DNS requests to the host machine. When connected to a hotspot, this forwarding may not work properly, causing DNS resolution failures.

## Solution: Restart Emulator with Custom DNS

The emulator needs to be **restarted** with DNS flags. DNS cannot be changed on a running emulator without root access (which the emulator doesn't have).

### Option 1: Use the Launch Scripts (Recommended)

I've created two launch scripts for you:

1. **`launch_emulator_with_dns.bat`** - Windows batch file
2. **`launch_emulator_with_dns.ps1`** - PowerShell script

**To use:**
1. Close the current emulator
2. Run one of the scripts (double-click the .bat file or run the .ps1 in PowerShell)
3. The emulator will start with Google DNS (8.8.8.8, 8.8.4.4)

### Option 2: Manual Command Line

If you prefer to launch manually:

```bash
emulator -avd Medium_Tablet_2 -dns-server 8.8.8.8,8.8.4.4
```

Or with Cloudflare DNS:
```bash
emulator -avd Medium_Tablet_2 -dns-server 1.1.1.1,1.0.0.1
```

### Option 3: Configure in Android Studio AVD Settings

1. Open Android Studio
2. Go to **Tools → Device Manager**
3. Find **Medium_Tablet_2** and click the **pencil icon** (Edit)
4. Click **Show Advanced Settings**
5. Find **Network** section
6. Set **DNS servers** to: `8.8.8.8,8.8.4.4`
7. Click **Finish**
8. Restart the emulator

## Testing DNS After Configuration

After restarting with DNS configuration, test connectivity:

```bash
# Test DNS resolution
adb shell ping -c 3 google.com

# Test direct IP connectivity
adb shell ping -c 3 8.8.8.8

# Check DNS servers
adb shell getprop net.dns1
adb shell getprop net.dns2
```

## Why This Should Work

- **Direct DNS**: Bypasses the host's DNS forwarding which may be broken with hotspot
- **Public DNS**: Google DNS (8.8.8.8) and Cloudflare DNS (1.1.1.1) are reliable public DNS servers
- **Persistent**: If configured in AVD settings, it will persist across restarts

## Next Steps

1. **Close the current emulator**
2. **Run `launch_emulator_with_dns.bat`** (or the PowerShell version)
3. **Wait for emulator to fully boot**
4. **Test connectivity** using the commands above
5. **Run your tests** - they should now have internet access

## Troubleshooting

If DNS still doesn't work after restarting:

1. **Check hotspot connectivity**: Ensure your phone hotspot is actually sharing internet (test on another device)
2. **Try different DNS**: Use Cloudflare (1.1.1.1, 1.0.0.1) instead of Google DNS
3. **Check Windows Firewall**: Temporarily disable to test if it's blocking emulator traffic
4. **AP Isolation**: Some hotspots have "AP Isolation" enabled which blocks device-to-device communication - check your phone's hotspot settings
