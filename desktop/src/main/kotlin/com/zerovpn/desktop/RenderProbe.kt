package com.zerovpn.desktop

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.Direction
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import java.io.File

/**
 * Headless render probe: shapes Persian text with the bundled B Nazanin
 * through the same skia paragraph pipeline Compose uses, and writes a PNG so
 * the letter joining can be verified visually (zero GUI needed).
 * Run: java -cp ZeroVPN-all.jar com.zerovpn.desktop.RenderProbeKt <out.png>
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "render_probe.png")
    val cl = Store::class.java.classLoader
    fun res(name: String): ByteArray =
        cl.getResourceAsStream(name)?.readBytes()
            ?: error("resource $name NOT found")
    val face = FontMgr.default.makeFromData(Data.makeFromBytes(res("font/BNazanin.ttf")))!!
    val faceBold = FontMgr.default.makeFromData(Data.makeFromBytes(res("font/BNazanin-Bold.ttf")))!!
    println("typeface family: ${face.familyName} / ${faceBold.familyName}")

    val width = 470
    val height = 230
    val surface = Surface.makeRasterN32Premul(width, height)
    val canvas = surface.canvas
    canvas.clear(0xFFFFFFFF.toInt())

    val fc = FontCollection()
    fc.setDefaultFontManager(FontMgr.default)

    fun style(typeface: Typeface): TextStyle = TextStyle()
        .setTypeface(typeface)
        .setFontSize(24f)
        .setColor(0xFF0B1B33.toInt())

    val base = style(face)
    val pstyle = ParagraphStyle()
    pstyle.direction = Direction.RTL
    pstyle.textStyle = base

    val builder = ParagraphBuilder(pstyle, fc)
    builder.pushStyle(base)
    builder.addText("محافظت شده  ·  تست پینگ همه سرورها\n")
    builder.addText("حجم: 2632 ms از 50 GB  ·  جیتر\n")
    builder.addText("انقضا: 2026/12/31  ·  اتلاف بسته\n")
    builder.popStyle()
    builder.pushStyle(style(faceBold))
    builder.addText("در حال اتصال — بروزرسانی اشتراک‌ها\n")
    builder.popStyle()
    builder.pushStyle(base)
    builder.addText("زمان اتصال 04:33  ·  افزودن اشتراک\n")
    builder.popStyle()
    val paragraph = builder.build()
    paragraph.layout(width.toFloat() - 20f)
    paragraph.paint(canvas, 10f, 10f)

    val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!
    out.writeBytes(png.bytes)
    println("wrote ${out.absolutePath} (${out.length()} bytes)")
    println("RENDER PROBE DONE")
}
