// Usage: swift export.swift foreground-source.png output-directory
// Raster export only: preserves the supplied cutout and prepares Android layers.
import AppKit
import CoreGraphics
let sourceURL = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let image = NSImage(contentsOf: sourceURL)!
let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil)!
let w = cg.width, h = cg.height
func context(_ size: Int) -> CGContext {
    let c = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    c.interpolationQuality = .high
    return c
}
let raw = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
raw.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
let pixels = raw.data!.assumingMemoryBound(to: UInt8.self)
var minX = w, minY = h, maxX = 0, maxY = 0
for y in 0..<h { for x in 0..<w { if pixels[(y*w+x)*4+3] > 16 {
    minX = min(minX,x); maxX = max(maxX,x); minY = min(minY,y); maxY = max(maxY,y)
}}}
let cx = Double(minX+maxX)/2, cy = Double(minY+maxY)/2
var radius = 0.0
for y in minY...maxY { for x in minX...maxX { if pixels[(y*w+x)*4+3] > 16 {
    radius = max(radius, hypot(Double(x)-cx, Double(y)-cy))
}}}
// Every meaningful pixel stays within a centered 65dp diameter circle (66dp safe zone).
let scale = 130.0 / radius
let fg = context(432)
fg.draw(cg, in: CGRect(x: 216-cx*scale, y: 216-cy*scale, width: Double(w)*scale, height: Double(h)*scale))
func save(_ image: CGImage, _ path: String) {
    let url = output.appendingPathComponent(path)
    try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let rep = NSBitmapImageRep(cgImage: image)
    try! rep.representation(using: .png, properties: [:])!.write(to: url)
}
let foreground = fg.makeImage()!
save(foreground, "../../app/src/main/res/drawable-nodpi/ic_launcher_foreground.png")
let mono = context(432)
mono.draw(foreground, in: CGRect(x:0,y:0,width:432,height:432))
let m = mono.data!.assumingMemoryBound(to: UInt8.self)
for i in 0..<(432*432) {
    let j=i*4, a=Double(m[j+3])/255
    let r=a > 0 ? Double(m[j])/a : 255
    let g=a > 0 ? Double(m[j+1])/a : 255
    let b=a > 0 ? Double(m[j+2])/a : 255
    // Preserve the character's brown contours and face as tintable line art.
    let contour = min(1, max(0, (160-max(r,max(g*1.3,b*1.5)))/55))
    let key = i / 432 > 260 ? min(1, max(0, (min(r,min(g,b))-225)/20)) : 0
    let ink = max(contour,key) * a
    let alpha=UInt8(ink*255)
    m[j]=alpha; m[j+1]=alpha; m[j+2]=alpha; m[j+3]=alpha
}
let monochrome=mono.makeImage()!
save(monochrome, "../../app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png")
func composite(_ size:Int, round:Bool = false, themed:Bool = false) -> CGImage {
    let c=context(size), s=Double(size)
    if round { c.addEllipse(in:CGRect(x:0,y:0,width:size,height:size)); c.clip() }
    c.setFillColor(CGColor(red: themed ? 0.86 : 250/255, green: themed ? 0.91 : 245/255, blue: themed ? 0.82 : 236/255, alpha:1))
    c.fill(CGRect(x:0,y:0,width:size,height:size))
    let layer = themed ? monochrome : foreground
    if themed {
        c.saveGState()
        c.clip(to: CGRect(x:-s/4,y:-s/4,width:s*1.5,height:s*1.5), mask: layer)
        c.setFillColor(CGColor(red:0.17,green:0.25,blue:0.14,alpha:1))
        c.fill(CGRect(x:0,y:0,width:size,height:size))
        c.restoreGState()
    } else {
        c.draw(layer, in:CGRect(x:-s/4,y:-s/4,width:s*1.5,height:s*1.5))
    }
    return c.makeImage()!
}
for (density,size) in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)] {
    save(composite(size), "../../app/src/main/res/mipmap-\(density)/ic_launcher.png")
    save(composite(size,round:true), "../../app/src/main/res/mipmap-\(density)/ic_launcher_round.png")
}
save(composite(512),"play-store-icon.png")
save(composite(256,round:true),"preview-circle.png")
save(composite(256,round:true,themed:true),"preview-themed.png")
print("Exported adaptive layers, five legacy densities, store icon, and previews. Safe radius: 32.5dp.")
