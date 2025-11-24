# Setting KEYSTORE_PASSWORD Environment Variable

## Option 1: Set for current session only (Git Bash)
```bash
export KEYSTORE_PASSWORD="your_password_here"
```

## Option 2: Set permanently in Git Bash
Add this line to your `~/.bashrc` or `~/.bash_profile` file:
```bash
export KEYSTORE_PASSWORD="your_password_here"
```

Then reload:
```bash
source ~/.bashrc
```

## Option 3: Set in PowerShell (Windows)
For current session:
```powershell
$env:KEYSTORE_PASSWORD="your_password_here"
```

For current user (permanent):
```powershell
[System.Environment]::SetEnvironmentVariable('KEYSTORE_PASSWORD', 'your_password_here', 'User')
```

## Option 4: Create a .env file (more secure)
Create a file called `.env` in your project root:
```
KEYSTORE_PASSWORD=your_password_here
```

Then source it before running release.sh:
```bash
source .env
./release.sh
```

Or modify release.sh to load it automatically:
```bash
# Add at the top of release.sh, after set -e
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi
```

**Security Note:** Make sure to add `.env` to your `.gitignore` file if you use Option 4!

