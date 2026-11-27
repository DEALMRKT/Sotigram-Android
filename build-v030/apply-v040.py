from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
main = root / "app/src/main/java/com/sotigram/app/MainActivity.kt"
gradle = root / "app/build.gradle.kts"
s = main.read_text(encoding="utf-8")

def replace_once(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f"Patch target not found: {label}")
    s = s.replace(old, new, 1)

replace_once(
    "private val Page = Color(0xFFF1F6FC)\nprivate val Ink = Color(0xFF17212B)\nprivate val Muted = Color(0xFF8A99A8)\nprivate val Divider = Color(0xFFE9EDF1)",
    "private val Page = Color(0xFF0E1722)\nprivate val SurfaceCard = Color(0xFF172534)\nprivate val SurfaceRaised = Color(0xFF1D2D3D)\nprivate val Ink = Color(0xFFF2F6FA)\nprivate val Muted = Color(0xFF9AAAB8)\nprivate val Divider = Color(0xFF2A3B4B)",
    "dark palette"
)
replace_once("surface = Color.White, onSurface = Ink, onBackground = Ink", "surface = SurfaceCard, onSurface = Ink, onBackground = Ink", "material surfaces")
replace_once("NavigationBar(containerColor = Color.White, tonalElevation = 8.dp)", "NavigationBar(containerColor = SurfaceCard.copy(alpha = 0.96f), tonalElevation = 8.dp)", "bottom navigation")
s = s.replace(".background(Color.White)", ".background(SurfaceCard)")
s = s.replace("background(Brush.linearGradient(listOf(Color.White, Color(0xFFF9FCFF))))", "background(Brush.linearGradient(listOf(SurfaceCard, SurfaceRaised)))")
s = s.replace("background(Color(0xFFECEFF2))", "background(SurfaceRaised)")
s = s.replace("background(Color(0xFFF7F8FA))", "background(SurfaceCard)")
s = s.replace("if (msg.senderId == session.userId) Color(0xFFD9FDD3) else Color.White", "if (msg.senderId == session.userId) Color(0xFF214C68) else SurfaceRaised")
s = s.replace("indicatorColor = Color(0xFFE7F3FC)", "indicatorColor = Color(0xFF263F55)")
s = s.replace("Color(0xFFE3F2FC)", "Color(0xFF20384D)")
s = s.replace("Color(0xFFE5F3FC)", "Color(0xFF20384D)")
s = s.replace("Color(0xFFEAF4FD)", "Color(0xFF20384D)")
s = s.replace("Color(0xFFEAF6EF)", "Color(0xFF1D4038)")
s = s.replace("Color(0xFFE7EDF2)", "Color(0xFF0B141E)")
s = s.replace("Color(0xFFEAF5FC)", "Color(0xFF0E1722)")
s = s.replace("Color(0xFFE7F3FF)", "Color(0xFF0E1722)")
s = s.replace("Color(0xFFB1BAC3)", "Color(0xFF718394)")
s = s.replace("Color(0xFFD8E3EC)", "Color(0xFF31475B)")
s = s.replace("Color(0xFFB4BEC7)", "Color(0xFF73879A)")
s = s.replace("Brush.verticalGradient(listOf(Color(0xFF0E1722), Page, Color.White))", "Brush.verticalGradient(listOf(Color(0xFF101F2E), Page, Color(0xFF0B121B)))")
s = s.replace("                    StoriesStrip()\n", "")
s, n = re.subn(r"@Composable\nprivate fun StoriesStrip\(\) \{.*?\n\}\n\n(?=@Composable)", "", s, count=1, flags=re.S)
if n != 1:
    raise SystemExit("Patch target not found: remove non-functional stories placeholder")
main.write_text(s, encoding="utf-8")

g = gradle.read_text(encoding="utf-8")
if 'versionName = "0.3.0"' not in g or "versionCode = 2" not in g:
    raise SystemExit("Unexpected Gradle version metadata")
g = g.replace("versionCode = 2", "versionCode = 3", 1).replace('versionName = "0.3.0"', 'versionName = "0.4.0"', 1)
gradle.write_text(g, encoding="utf-8")
print("Applied Sotigram 0.4.0 dark UI patch")
