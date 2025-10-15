# Save as randomPrefix.ps1 and run in your images directory
$files = Get-ChildItem -File -Filter *.png
$count = $files.Count
$numbers = 1..$count | Get-Random -Count $count

for ($i = 0; $i -lt $count; $i++) {
    $file = $files[$i]
    $prefix = $numbers[$i]
    $newName = "$prefix-$($file.Name)"
    Rename-Item -Path $file.FullName -NewName $newName
}