param([string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

function New-Bitmap([int]$Size, [bool]$Round) {
    $bmp = [Drawing.Bitmap]::new($Size, $Size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([Drawing.Color]::Transparent)
    $s = $Size / 1024.0
    $bg = [Drawing.Drawing2D.GraphicsPath]::new()
    if ($Round) { $bg.AddEllipse(0, 0, $Size, $Size) }
    else {
        $d = 220 * $s
        $bg.AddArc(0, 0, $d, $d, 180, 90)
        $bg.AddArc($Size-$d, 0, $d, $d, 270, 90)
        $bg.AddArc($Size-$d, $Size-$d, $d, $d, 0, 90)
        $bg.AddArc(0, $Size-$d, $d, $d, 90, 90)
        $bg.CloseFigure()
    }
    $fill = [Drawing.Drawing2D.LinearGradientBrush]::new([Drawing.RectangleF]::new(0,0,$Size,$Size), [Drawing.Color]::FromArgb(255,10,18,38), [Drawing.Color]::FromArgb(255,27,67,126), 35)
    $g.FillPath($fill, $bg); $fill.Dispose(); $bg.Dispose()
    $g.FillEllipse([Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(42,56,189,248)), -170*$s, -260*$s, 720*$s, 720*$s)
    function Poly([float[]]$v) {
        $p = [Drawing.Drawing2D.GraphicsPath]::new()
        $pts = [Drawing.PointF[]]::new($v.Count/2)
        for($i=0;$i -lt $pts.Count;$i++){ $pts[$i]=[Drawing.PointF]::new($v[$i*2]*$s,$v[$i*2+1]*$s) }
        $p.AddPolygon($pts); return $p
    }
    $p = Poly @(185,350, 345,350, 410,415, 805,415, 760,745, 230,745)
    $b=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(255,3,169,244)); $g.FillPath($b,$p); $b.Dispose(); $p.Dispose()
    $p = Poly @(345,205, 535,205, 625,300, 625,505, 345,505)
    $b=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(255,248,250,252)); $g.FillPath($b,$p); $b.Dispose(); $p.Dispose()
    $p = Poly @(535,205, 625,300, 535,300)
    $b=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(255,191,219,254)); $g.FillPath($b,$p); $b.Dispose(); $p.Dispose()
    $b=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(255,37,99,235))
    foreach($l in @(@(395,255,500,255),@(395,295,555,295),@(395,335,535,335),@(395,375,500,375))){ $g.FillRectangle($b,$l[0]*$s,$l[1]*$s,($l[2]-$l[0])*$s,14*$s) }; $b.Dispose()
    $p = Poly @(210,425, 780,425, 735,715, 260,715)
    $b=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(255,2,132,199)); $g.FillPath($b,$p); $b.Dispose(); $p.Dispose()
    $b=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(255,125,211,252)); $g.FillRectangle($b,275*$s,445*$s,440*$s,42*$s); $b.Dispose()
    $g.Dispose(); return $bmp
}

function Save-Icon($Source, [string]$Path, [int]$Size) {
    $out=[Drawing.Bitmap]::new($Size,$Size,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g=[Drawing.Graphics]::FromImage($out); $g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($Source,0,0,$Size,$Size); $g.Dispose()
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $out.Save($Path,[Drawing.Imaging.ImageFormat]::Png); $out.Dispose()
}

$square=New-Bitmap 1024 $false; $round=New-Bitmap 1024 $true
try {
    $densities=[ordered]@{mdpi=48;hdpi=72;xhdpi=96;xxhdpi=144;xxxhdpi=192}
    foreach($d in $densities.GetEnumerator()){
        $dir=Join-Path $ProjectRoot "app\src\main\res\mipmap-$($d.Key)"
        Save-Icon $square (Join-Path $dir 'ic_launcher.png') $d.Value
        Save-Icon $round (Join-Path $dir 'ic_launcher_round.png') $d.Value
    }
    Save-Icon $square (Join-Path $ProjectRoot 'assets\app_icon.png') 800
} finally { $square.Dispose(); $round.Dispose() }
Write-Host 'Generated ZenLabs launcher icons.'
