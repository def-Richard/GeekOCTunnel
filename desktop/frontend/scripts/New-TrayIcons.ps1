Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$destination = Join-Path (Split-Path $PSScriptRoot -Parent) 'src-tauri/icons/tray-states.png'
# Keep the terminal mark from app-icon.svg; render at 4x for small Windows tray sizes.
$scale = 4
$atlas = [Drawing.Bitmap]::new(160,256)
$atlasGraphics = [Drawing.Graphics]::FromImage($atlas)
$colors = @('#8491A6','#25BE87','#F3AF36','#25BE87','#EF646D')
try {
    for ($row=0; $row -lt 8; $row++) {
        $count = if ($row -eq 0) { 0 } else { $row+1 }
        for ($state=0; $state -lt 5; $state++) {
            $tile = [Drawing.Bitmap]::new(32*$scale,32*$scale)
            $g = [Drawing.Graphics]::FromImage($tile)
            $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
            $g.ScaleTransform($scale,$scale)
            $dark = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#141D2B'))
            $color = [Drawing.ColorTranslator]::FromHtml($colors[$state])
            $ring = [Drawing.Pen]::new($color,3)
            $white = [Drawing.Pen]::new([Drawing.Color]::White,2.3)
            $ring.StartCap=$ring.EndCap=$white.StartCap=$white.EndCap=[Drawing.Drawing2D.LineCap]::Round
            $badge = [Drawing.SolidBrush]::new($(if ($state -eq 3) { [Drawing.ColorTranslator]::FromHtml('#F3AF36') } else { $color }))
            $font = [Drawing.Font]::new('Segoe UI',12,[Drawing.FontStyle]::Bold,[Drawing.GraphicsUnit]::Pixel)
            $format = [Drawing.StringFormat]::new()
            $format.Alignment=$format.LineAlignment=[Drawing.StringAlignment]::Center
            try {
                $g.FillEllipse($dark,1,1,30,30)
                $g.DrawArc($ring,4,4,22,22,40,290)
                $g.DrawLines($white,[Drawing.PointF[]]@([Drawing.PointF]::new(10,10),[Drawing.PointF]::new(15,15),[Drawing.PointF]::new(10,20)))
                $g.DrawLine($white,18,20,22,20)
                $g.FillEllipse($dark,17,17,15,15)
                $g.FillEllipse($badge,19,19,13,13)
                if ($count -ge 2) {
                    $g.DrawString([string]$count,$font,$dark,[Drawing.RectangleF]::new(19,18,13,14),$format)
                } elseif ($state -eq 0) {
                    $g.DrawLine($white,22,25,28,25)
                } elseif ($state -eq 1) {
                    $g.DrawLines($white,[Drawing.PointF[]]@([Drawing.PointF]::new(22,25),[Drawing.PointF]::new(24,27),[Drawing.PointF]::new(28,23)))
                } elseif ($state -eq 2) {
                    $g.DrawLine($white,25,22,25,25); $g.DrawLine($white,25,25,28,25)
                } else {
                    $g.DrawLine($white,25,22,25,25); $g.FillEllipse([Drawing.Brushes]::White,24,27,2,2)
                }
                $atlasGraphics.DrawImage($tile,[Drawing.Rectangle]::new($state*32,$row*32,32,32))
            } finally {
                $format.Dispose();$font.Dispose();$badge.Dispose();$white.Dispose();$ring.Dispose();$dark.Dispose();$g.Dispose();$tile.Dispose()
            }
        }
    }
    $atlas.Save($destination,[Drawing.Imaging.ImageFormat]::Png)
} finally { $atlasGraphics.Dispose();$atlas.Dispose() }
Write-Output 'Generated five tray states with connected-count badges (2-8).'
